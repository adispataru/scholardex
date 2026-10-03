package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.activities.PublisherClaim;
import ro.uvt.pokedex.core.model.registry.RegistryEntry;
import ro.uvt.pokedex.core.model.registry.RegistryKind;
import ro.uvt.pokedex.core.model.registry.RegistryStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H144 — what a formula reads instead of a level the researcher picked. */
class RegistryScoringSupportTest {

    private final List<RegistryEntry> entries = new ArrayList<>();

    @AfterEach
    void reset() {
        RegistrySupport.reset();
        ArtisticEventRankSupport.reset();
        RegistryScoringSupport.reset();
    }

    private void entry(RegistryKind kind, String name, RegistryStatus status, String level, String category, String country) {
        RegistryEntry e = RegistryEntry.of(kind);
        e.setName(name);
        e.setStatus(status);
        e.setLevel(level);
        e.setCategory(category);
        e.setCountry(country);
        entries.add(e);
        RegistrySupport.register(entries);
    }

    private static Map<String, Object> bind(Map<Activity.ReferenceField, String> refs, Map<String, String> fields) {
        ActivityInstance record = new ActivityInstance();
        record.setDate("2024-05-01");
        record.setReferenceFields(new HashMap<>(refs));
        record.setFields(new HashMap<>(fields));
        Map<String, Object> variables = new HashMap<>();
        RegistryScoringSupport.bind(record, variables);
        return variables;
    }

    @Test
    void aConferenceWaitingForTheExpertsCountsNationalAndARejectedOneCountsNothing() {
        entry(RegistryKind.SCIENTIFIC_EVENT, "ECER 2024", RegistryStatus.CONFIRMED, "INTERNATIONAL", "CONFERENCE", "Cyprus");
        entry(RegistryKind.SCIENTIFIC_EVENT, "Webinar", RegistryStatus.REJECTED, null, null, null);

        Map<String, Object> ranked = bind(Map.of(Activity.ReferenceField.CONFERENCE_NAME, "ECER 2024"), Map.of());
        assertEquals("INTERNATIONAL", ranked.get("Nivel_entitate"));
        assertEquals(true, ranked.get("International"));
        assertEquals(true, ranked.get("In_strainatate"), "its country is the registry's");

        Map<String, Object> waiting = bind(Map.of(Activity.ReferenceField.CONFERENCE_NAME, "Simpozionul de la Iași"), Map.of());
        assertEquals("NATIONAL", waiting.get("Nivel_entitate"), "Comisia 28: national until shown international");
        assertEquals(false, waiting.get("International"));
        assertEquals(true, waiting.get("Recunoscut"));

        Map<String, Object> rejected = bind(Map.of(Activity.ReferenceField.CONFERENCE_NAME, "webinar"), Map.of());
        assertNull(rejected.get("Nivel_entitate"));
        assertEquals(false, rejected.get("Recunoscut"));
    }

    @Test
    void anOrganisationOrAnAwardWaitingForTheExpertsPassesNoGate() {
        entry(RegistryKind.ORGANIZATION, "UCMR", RegistryStatus.CONFIRMED, "NATIONAL", "ASSOCIATION", "România");
        entry(RegistryKind.AWARD, "EERA Award", RegistryStatus.CONFIRMED, "INTERNATIONAL", "SCIENTIFIC", null);

        assertEquals(true, bind(Map.of(Activity.ReferenceField.ORGANIZATION_NAME, "ucmr"), Map.of()).get("Recunoscut"));
        assertEquals(false, bind(Map.of(Activity.ReferenceField.ORGANIZATION_NAME, "UCMR"), Map.of()).get("In_strainatate"));
        Map<String, Object> waiting = bind(Map.of(Activity.ReferenceField.ORGANIZATION_NAME, "Asociația nouă"), Map.of());
        assertEquals(false, waiting.get("Recunoscut"));
        assertEquals(true, waiting.get("Entitate_numita"));

        Map<String, Object> award = bind(Map.of(Activity.ReferenceField.AWARD_NAME, "EERA Award"), Map.of());
        assertEquals(true, award.get("International"));
        assertEquals(true, award.get("Premiu_stiintific"));
        assertEquals(false, bind(Map.of(), Map.of()).get("Entitate_numita"));
    }

    @Test
    void anArtisticEventCountsAtItsRankAndTheBestNamedEntityWins() {
        ArtisticEventRankSupport.register(List.of(event("Festivalul George Enescu", ArtisticEvent.Rank.INTERNATIONAL_TOP),
                event("Serile din cartier", ArtisticEvent.Rank.LOCAL)));
        assertEquals("INTERNATIONAL", bind(Map.of(Activity.ReferenceField.EVENT_NAME, "Festivalul George Enescu"), Map.of())
                .get("Nivel_entitate"));
        Map<String, Object> local = bind(Map.of(Activity.ReferenceField.EVENT_NAME, "Serile din cartier"), Map.of());
        assertEquals("LOCAL", local.get("Nivel_entitate"));
        assertEquals(false, local.get("Recunoscut"));
        assertNull(bind(Map.of(Activity.ReferenceField.EVENT_NAME, "Un festival nou"), Map.of()).get("Nivel_entitate"),
                "an artistic event waiting for the experts has no level");

        Map<String, Object> both = bind(Map.of(Activity.ReferenceField.EVENT_NAME, "Serile din cartier",
                Activity.ReferenceField.CONFERENCE_NAME, "Simpozionul de la Iași"), Map.of());
        assertEquals("NATIONAL", both.get("Nivel_entitate"));
    }

    @Test
    void theAppsListsDecideUniversitiesJournalsAndPatents() {
        RegistryScoringSupport.register(new RegistryScoringSupport.Lookups() {
            @Override
            public Optional<Integer> urapRank(String university, int year) {
                return "University of Helsinki".equals(university) ? Optional.of(101) : Optional.empty();
            }

            @Override
            public Optional<Integer> worldRank(String university, int year) {
                return "University of Helsinki".equals(university) ? Optional.of(115) : Optional.empty();
            }

            @Override
            public Optional<String> universityCountry(String university) {
                return "University of Helsinki".equals(university) ? Optional.of("Finland") : Optional.of("Romania");
            }

            @Override
            public Optional<RegistryScoringSupport.JournalFacts> journal(String issn, int year) {
                return "1234-567X".equals(issn)
                        ? Optional.of(new RegistryScoringSupport.JournalFacts(true, false, true, true, 3, 0.8))
                        : Optional.empty();
            }
        });
        Map<String, Object> helsinki = bind(Map.of(Activity.ReferenceField.UNIVERSITY_NAME, "University of Helsinki"), Map.of());
        assertEquals(true, helsinki.get("Top500_URAP"));
        assertEquals(true, helsinki.get("Top1000_mondial"));
        assertEquals(true, helsinki.get("In_strainatate"));
        assertEquals(true, helsinki.get("Universitate_numita"));
        Map<String, Object> other = bind(Map.of(Activity.ReferenceField.UNIVERSITY_NAME, "Universitatea X"), Map.of());
        assertEquals(false, other.get("Top500_URAP"));
        assertEquals(false, other.get("In_strainatate"));

        Map<String, Object> journal = bind(Map.of(Activity.ReferenceField.FORUM_ISSN, "1234567x"), Map.of());
        assertEquals(true, journal.get("Revista_cu_taxa"));
        assertEquals(false, journal.get("Revista_WoS"));
        assertEquals(true, journal.get("Revista_WoS_CC"));
        assertEquals(true, journal.get("Revista_Scopus"));
        assertEquals(3, journal.get("N_baze_date"));
        assertEquals(0.8, journal.get("IF_revista"));
        Map<String, Object> unknown = bind(Map.of(Activity.ReferenceField.FORUM_ISSN, "0000-0000"), Map.of());
        assertNull(unknown.get("Revista_cu_taxa"));
        assertFalse(unknown.containsKey("IF_revista"), "a record that names no known journal keeps whatever it typed");
    }

    @Test
    void aPatentsKindFollowsItsCodesAndOffices() {
        assertEquals("NATIONAL", RegistryScoringSupport.patentType("RO 123456 B1", null));
        assertEquals("NATIONAL", RegistryScoringSupport.patentType("123456", "OSIM"));
        assertEquals("EUROPEAN", RegistryScoringSupport.patentType("EP1234567", null));
        assertEquals("INTERNATIONAL", RegistryScoringSupport.patentType("WO2019/123456", null));
        assertEquals("INTERNATIONAL", RegistryScoringSupport.patentType("US 9,876,543", null));
        assertEquals("TRIADIC", RegistryScoringSupport.patentType("EP1234567; US9876543; JP2020-123456", null));
        assertEquals("EUROPEAN", RegistryScoringSupport.patentType("RO 123456; EP 1234567", null), "the most favourable");
        assertNull(RegistryScoringSupport.patentType("nr. 123456", null), "a number names no office");
        assertNull(RegistryScoringSupport.patentType("No. 123456", ""));
        assertEquals("TRIADIC", bind(Map.of(), Map.of("Cod brevet", "EP1; US2; JP3")).get("Tip_brevet"));
    }

    @Test
    void anApprovedRequestCountsWhileTheRecordStillAsksForIt() {
        ActivityInstance record = new ActivityInstance();
        record.setDate("2024-05-01");
        record.setReferenceFields(new HashMap<>());
        record.setFields(new HashMap<>(Map.of("Incadrare_solicitata", "Revistă indexată în cel puțin 3 baze de date")));
        PublisherClaim claim = new PublisherClaim();
        claim.setStatus(PublisherClaim.Status.APPROVED);
        claim.setRequested("Revistă indexată în cel puțin 3 baze de date");
        record.setPublisherClaim(claim);
        Map<String, Object> variables = new HashMap<>();
        RegistryScoringSupport.bind(record, variables);
        assertEquals("Revistă indexată în cel puțin 3 baze de date", variables.get("Incadrare_aprobata"));

        claim.setStatus(PublisherClaim.Status.PENDING);
        RegistryScoringSupport.bind(record, variables);
        assertNull(variables.get("Incadrare_aprobata"));
    }

    @Test
    void anIssnIsReadInEveryUsualForm() {
        assertEquals("1234-567X", RegistryScoringSupport.canonicalIssn(" 1234-567x "));
        assertEquals("1234-5678", RegistryScoringSupport.canonicalIssn("12345678"));
        assertNull(RegistryScoringSupport.canonicalIssn("1234-56"));
        assertTrue(RegistryScoringSupport.isAbroad("Austria"));
        assertFalse(RegistryScoringSupport.isAbroad("România"));
        assertFalse(RegistryScoringSupport.isAbroad(null));
    }

    private static ArtisticEvent event(String name, ArtisticEvent.Rank rank) {
        ArtisticEvent e = new ArtisticEvent();
        e.setName(name);
        e.setRank(rank);
        return e;
    }
}
