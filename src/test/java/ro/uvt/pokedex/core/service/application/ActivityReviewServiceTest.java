package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H142 slice 2 — reviewing imported activities many at once. */
class ActivityReviewServiceTest {

    private static final String EMAIL = "ion.popescu@e-uvt.ro";

    private final ActivityInstanceRepository repository = mock(ActivityInstanceRepository.class);
    private final ActivityReviewService service = new ActivityReviewService(repository);

    private static Activity eventType() {
        Activity a = new Activity();
        a.setName("Participare eveniment artistic");
        Activity.Field rol = new Activity.Field();
        rol.setName("Rol");
        rol.setAllowedValues(List.of("Dirijor", "Solist"));
        Activity.Field size = new Activity.Field();
        size.setName("Marime_formatie");
        size.setNumber(true);
        a.setFields(List.of(rol, size));
        return a;
    }

    private static Activity juryType() {
        Activity a = new Activity();
        a.setName("Membru în juriul unui concurs (Comisia 35, RIA 3.3)");
        Activity.Field concursul = new Activity.Field();
        concursul.setName("Concursul");
        a.setFields(List.of(concursul));
        return a;
    }

    private static ActivityInstance instance(String id, String owner, Activity type, Map<String, String> fields) {
        ActivityInstance i = new ActivityInstance();
        i.setId(id);
        i.setResearcherId(owner);
        i.setActivity(type);
        i.setFields(new HashMap<>(fields));
        i.setNeedsReview(Boolean.TRUE);
        return i;
    }

    @SuppressWarnings("unchecked")
    private List<ActivityInstance> saved() {
        ArgumentCaptor<List<ActivityInstance>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        return new ArrayList<>(captor.getValue());
    }

    @Test
    void aFieldIsSetOnEverySelectedRecordWhoseTypeHasIt() {
        ActivityInstance concert = instance("c1", EMAIL, eventType(), Map.of());
        ActivityInstance other = instance("c2", EMAIL, eventType(), Map.of("Rol", "Dirijor", "Marime_formatie", "30"));
        ActivityInstance jury = instance("j1", EMAIL, juryType(), Map.of("Concursul", "X"));
        when(repository.findAllById(any())).thenReturn(List.of(concert, other, jury));

        ActivityReviewService.BulkResult result = service.setFields(EMAIL, List.of("c1", "c2", "j1"),
                Map.of("Rol", "Dirijor", "Marime_formatie", "30"));

        assertEquals(1, result.changed(), "the second concert already had both values; the jury has neither field");
        assertEquals(2, result.unchanged());
        assertTrue(result.problems().isEmpty());
        ActivityInstance changed = saved().getFirst();
        assertEquals("c1", changed.getId());
        assertEquals("Dirijor", changed.getFields().get("Rol"));
        assertEquals("30", changed.getFields().get("Marime_formatie"));
    }

    @Test
    void aValueThatIsNotAnOptionOrNotANumberIsRefused() {
        when(repository.findAllById(any())).thenReturn(List.of(instance("c1", EMAIL, eventType(), Map.of())));
        ActivityReviewService.BulkResult result = service.setFields(EMAIL, List.of("c1"),
                Map.of("Rol", "Corist", "Marime_formatie", "mulți"));
        assertEquals(0, result.changed());
        assertEquals(2, result.problems().size());
    }

    @Test
    void anEmptyValueClearsTheField() {
        when(repository.findAllById(any())).thenReturn(List.of(instance("c1", EMAIL, eventType(), Map.of("Rol", "Solist"))));
        service.setFields(EMAIL, List.of("c1"), Map.of("Rol", ""));
        assertNull(saved().getFirst().getFields().get("Rol"));
    }

    @Test
    void onlyThePersonsOwnRecordsAreTouched() {
        ActivityInstance mine = instance("m", EMAIL, juryType(), Map.of());
        ActivityInstance theirs = instance("t", "altcineva@e-uvt.ro", juryType(), Map.of());
        when(repository.findAllById(any())).thenReturn(List.of(mine, theirs));

        assertEquals(1, service.markReviewed(EMAIL, List.of("m", "t")).changed());
        assertFalse(saved().getFirst().getNeedsReview());
        assertTrue(theirs.getNeedsReview());

        ActivityReviewService.BulkResult deleted = service.delete(EMAIL, List.of("m", "t"));
        assertEquals(1, deleted.changed());
        verify(repository).deleteAll(List.of(mine));
    }
}
