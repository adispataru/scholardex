package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.activities.Activity;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H145 — a declared activity stores only what its type accepts. */
class ActivityRecordValidatorTest {

    private static Activity.Field field(String name, boolean number, String... options) {
        Activity.Field f = new Activity.Field();
        f.setName(name);
        f.setNumber(number);
        f.setAllowedValues(List.of(options));
        return f;
    }

    private static Activity type(List<Activity.ReferenceField> references, Activity.Field... fields) {
        Activity type = new Activity();
        type.setName("T");
        type.setFields(List.of(fields));
        type.setReferenceFields(references);
        return type;
    }

    private static Map<String, String> values(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    @Test
    void anOptionNotOfferedAFieldNotDeclaredAndAnUnboundedNumberAreRefused() {
        Activity t = type(List.of(), field("Rol", false, "Membru", "Director"), field("Buget", true), field("IF_sursa", true));

        var result = ActivityRecordValidator.validate(t, values("Rol", "x", "Buget", "Infinity", "IF_sursa", "1e4",
                "Coeficient_m", "m = 2"), Map.of());

        assertEquals(List.of("Rol: «x»", "Buget: «Infinity»", "IF_sursa: «1e4»", "Coeficient_m: «m = 2»"), result.problems());
    }

    @Test
    void plainValuesPassBlanksAreDroppedAndADecimalCommaIsRead() {
        Activity t = type(List.of(), field("Rol", false, "Membru"), field("Buget", true), field("Titlu", false));

        var result = ActivityRecordValidator.validate(t, values("Rol", "Membru", "Buget", "45000,50", "Titlu", "  "), Map.of());

        assertTrue(result.valid());
        assertEquals(Map.of("Rol", "Membru", "Buget", "45000.50"), result.fields());
    }

    @Test
    void yearsAreYearsOfACareerAndInOrder() {
        Activity t = type(List.of(), field("An_inceput", true), field("An_sfarsit", true));

        assertEquals(List.of("An_inceput: «1»"), ActivityRecordValidator.validate(t, values("An_inceput", "1"), Map.of()).problems());
        assertEquals(List.of("An_inceput: «2020.5»"), ActivityRecordValidator.validate(t, values("An_inceput", "2020.5"), Map.of()).problems());
        assertEquals(List.of("An_inceput–An_sfarsit: «2020–2010»"),
                ActivityRecordValidator.validate(t, values("An_inceput", "2020", "An_sfarsit", "2010"), Map.of()).problems());
        assertTrue(ActivityRecordValidator.validate(t, values("An_inceput", "2010", "An_sfarsit", "2020"), Map.of()).valid());
    }

    @Test
    void countsStartAtOne() {
        Activity t = type(List.of(), field("N_autori", true), field("N_numere_speciale", true));

        assertEquals(List.of("N_autori: «0»", "N_numere_speciale: «500»"),
                ActivityRecordValidator.validate(t, values("N_autori", "0", "N_numere_speciale", "500"), Map.of()).problems());
    }

    @Test
    void aRecordNamesOnlyTheReferencesItsTypeDeclaresAndOneEntity() {
        Activity t = type(List.of(Activity.ReferenceField.CONFERENCE_NAME, Activity.ReferenceField.EVENT_NAME));
        Map<Activity.ReferenceField, String> refs = new EnumMap<>(Activity.ReferenceField.class);
        refs.put(Activity.ReferenceField.CONFERENCE_NAME, "Conferința X");
        refs.put(Activity.ReferenceField.ORGANIZATION_NAME, "UNESCO");

        assertEquals(List.of("ORGANIZATION_NAME: «UNESCO»"), ActivityRecordValidator.validate(t, Map.of(), refs).problems());

        refs.remove(Activity.ReferenceField.ORGANIZATION_NAME);
        refs.put(Activity.ReferenceField.EVENT_NAME, "Festivalul George Enescu");
        assertEquals(List.of("EVENT_NAME + CONFERENCE_NAME"), ActivityRecordValidator.validate(t, Map.of(), refs).problems());

        refs.remove(Activity.ReferenceField.EVENT_NAME);
        assertTrue(ActivityRecordValidator.validate(t, Map.of(), refs).valid());
    }
}
