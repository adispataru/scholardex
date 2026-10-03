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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H144, H145 — what a formula reads instead of a level the researcher picked. */
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

    /** A record whose type declares exactly the references it fills. */
    private static Map<String, Object> bind(Map<Activity.ReferenceField, String> refs, Map<String, String> fields) {
        return bind(List.copyOf(refs.keySet()), refs, fields);
    }

    private static Map<String, Object> bind(List<Activity.ReferenceField> declared, Map<Activity.ReferenceField, String> refs,
                                            Map<String, String> fields) {
        Activity type = new Activity();
        type.setId("t");
        type.setReferenceFields(declared);
        ActivityInstance record = new ActivityInstance();
        record.setActivity(type);
        record.setDate("2024-05-01");
        record.setReferenceFields(new HashMap<>(refs));
        record.setFields(new HashMap<>(fields));
        Map<String, Object> variables = new HashMap<>();
        RegistryScoringSupport.bind(record, variables);
        return variables;
    }

    @Test
    void onlyARankedNameHasALevelAndARejectedOneIsNotValid() {
        entry(RegistryKind.SCIENTIFIC_EVENT, "ECER 2024", RegistryStatus.CONFIRMED, "INTERNATIONAL", "CONFERENCE", "Cyprus");
        entry(RegistryKind.SCIENTIFIC_EVENT, "Webinar", RegistryStatus.REJECTED, null, null, null);

        Map<String, Object> ranked = bind(Map.of(Activity.ReferenceField.CONFERENCE_NAME, "ECER 2024"), Map.of());
        assertEquals("INTERNATIONAL", ranked.get("Nivel_entitate"));
        assertEquals(true, ranked.get("International"));
        assertEquals(true, ranked.get("In_strainatate"), "its country is the registry's");
        assertEquals(true, ranked.get("Entitate_valida"));

        Map<String, Object> waiting = bind(Map.of(Activity.ReferenceField.CONFERENCE_NAME, "Simpozionul de la Iași"), Map.of());
        assertNull(waiting.get("Nivel_entitate"), "no floor: a standard that counts a waiting name says so itself");
        assertEquals(false, waiting.get("Recunoscut"));
        assertEquals(true, waiting.get("Entitate_valida"), "Comisia 28 counts it national through Entitate_valida");

        Map<String, Object> rejected = bind(Map.of(Activity.ReferenceField.CONFERENCE_NAME, "Webinar"), Map.of());
        assertNull(rejected.get("Nivel_entitate"));
        assertEquals(false, rejected.get("Entitate_valida"));
        assertEquals(true, rejected.get("Entitate_numita"));

        Map<String, Object> blank = bind(Map.of(), Map.of());
        assertEquals(false, blank.get("Entitate_numita"));
        assertEquals(false, blank.get("Entitate_valida"));
    }

    @Test
    void anOrganisationOrAnAwardCountsOnceRanked() {
        entry(RegistryKind.ORGANIZATION, "UCMR", RegistryStatus.CONFIRMED, "NATIONAL", "ASSOCIATION", "România");
        entry(RegistryKind.AWARD, "Premiul EERA", RegistryStatus.CONFIRMED, "INTERNATIONAL", "SCIENTIFIC", null);
        entry(RegistryKind.AWARD, "Ordinul Meritul Cultural", RegistryStatus.CONFIRMED, "NATIONAL", "STATE", "România");
        assertEquals(true, bind(Map.of(Activity.ReferenceField.ORGANIZATION_NAME, "ucmr"), Map.of()).get("Recunoscut"));
        assertEquals(false, bind(Map.of(Activity.ReferenceField.ORGANIZATION_NAME, "UCMR"), Map.of()).get("In_strainatate"));
        Map<String, Object> waiting = bind(Map.of(Activity.ReferenceField.ORGANIZATION_NAME, "Asociația X"), Map.of());
        assertEquals(false, waiting.get("Recunoscut"));
        Map<String, Object> award = bind(Map.of(Activity.ReferenceField.AWARD_NAME, "Premiul EERA"), Map.of());
        assertEquals(true, award.get("International"));
        assertEquals(true, award.get("Premiu_stiintific"));
        assertEquals(false, award.get("Premiu_de_stat"));
        assertEquals(true, bind(Map.of(Activity.ReferenceField.AWARD_NAME, "Ordinul Meritul Cultural"), Map.of()).get("Premiu_de_stat"));
    }

    @Test
    void anArtisticEventCountsAtItsRankAndARecordNamesOneEntity() {
        ArtisticEventRankSupport.register(List.of(
                event("Festivalul George Enescu", ArtisticEvent.Rank.INTERNATIONAL_TOP, ArtisticEvent.Kind.FESTIVAL),
                event("Concursul Enescu", ArtisticEvent.Rank.INTERNATIONAL_TOP, ArtisticEvent.Kind.COMPETITION),
                event("Serile din cartier", ArtisticEvent.Rank.LOCAL, ArtisticEvent.Kind.SEASON)));
        Map<String, Object> festival = bind(Map.of(Activity.ReferenceField.EVENT_NAME, "Festivalul George Enescu"), Map.of());
        assertEquals("INTERNATIONAL", festival.get("Nivel_entitate"));
        assertEquals(false, festival.get("Concurs"));
        assertEquals(true, bind(Map.of(Activity.ReferenceField.EVENT_NAME, "Concursul Enescu"), Map.of()).get("Concurs"));
        Map<String, Object> local = bind(Map.of(Activity.ReferenceField.EVENT_NAME, "Serile din cartier"), Map.of());
        assertEquals("LOCAL", local.get("Nivel_entitate"));
        assertEquals(false, local.get("Recunoscut"));
        assertNull(bind(Map.of(Activity.ReferenceField.EVENT_NAME, "Un festival nou"), Map.of()).get("Nivel_entitate"));

        // H145: a second name never lifts the first — two named entities count as none
        Map<String, Object> both = bind(Map.of(Activity.ReferenceField.EVENT_NAME, "Festivalul George Enescu",
                Activity.ReferenceField.CONFERENCE_NAME, "Simpozionul de la Iași"), Map.of());
        assertNull(both.get("Nivel_entitate"));
        assertEquals(false, both.get("Entitate_valida"));
    }

    @Test
    void aReferenceTheTypeDoesNotDeclareIsNeverRead() {
        entry(RegistryKind.ORGANIZATION, "UNESCO", RegistryStatus.CONFIRMED, "INTERNATIONAL", "AGENCY", null);
        Map<String, Object> keynote = bind(List.of(Activity.ReferenceField.CONFERENCE_NAME),
                Map.of(Activity.ReferenceField.ORGANIZATION_NAME, "UNESCO"), Map.of());
        assertEquals(false, keynote.get("International"));
        assertEquals(false, keynote.get("Entitate_numita"));
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
                return "University of Helsinki".equals(university) ? Optional.of("Finland")
                        : "West University of Timisoara".equals(university) ? Optional.of("Romania") : Optional.empty();
            }

            @Override
            public Optional<RegistryScoringSupport.JournalFacts> journal(String issn, int year) {
                return "1234-567X".equals(issn)
                        ? Optional.of(new RegistryScoringSupport.JournalFacts(true, false, true, true,
                        Set.of("ESCI", "SCOPUS", "DOAJ", "DBLP", "OPENALEX"), 0.8))
                        : Optional.empty();
            }
        });
        Map<String, Object> helsinki = bind(Map.of(Activity.ReferenceField.UNIVERSITY_NAME, "University of Helsinki"), Map.of());
        assertEquals(true, helsinki.get("Top500_URAP"));
        assertEquals(true, helsinki.get("Top1000_mondial"));
        assertEquals(true, helsinki.get("In_strainatate"));
        assertEquals(true, helsinki.get("Universitate_numita"));
        Map<String, Object> made = bind(Map.of(Activity.ReferenceField.UNIVERSITY_NAME, "Universitatea X"), Map.of());
        assertEquals(false, made.get("Universitate_numita"), "a name no ranking knows is not a university the lists vouch for");
        assertEquals(false, made.get("Entitate_valida"));
        Map<String, Object> own = bind(Map.of(Activity.ReferenceField.UNIVERSITY_NAME, "West University of Timisoara"), Map.of());
        assertEquals(true, own.get("Universitate_proprie"));
        assertEquals(false, own.get("Universitate_numita"), "a visit to one's own university is no visit");

        Map<String, Object> journal = bind(Map.of(Activity.ReferenceField.FORUM_ISSN, "1234567x"), Map.of());
        assertEquals(true, journal.get("Revista_cu_taxa"));
        assertEquals(false, journal.get("Revista_WoS"));
        assertEquals(true, journal.get("Revista_WoS_CC"));
        assertEquals(true, journal.get("Revista_Scopus"));
        assertEquals(3, journal.get("N_baze_date"), "Web of Science once, Scopus, DOAJ — never DBLP or OpenAlex");
        assertEquals(0.8, journal.get("IF_revista"));
        Map<String, Object> unknown = bind(Map.of(Activity.ReferenceField.FORUM_ISSN, "0000-0000"), Map.of());
        assertNull(unknown.get("Revista_cu_taxa"));
        assertNull(unknown.get("IF_revista"), "no journal the lists know: no impact factor — nobody types one");
        assertTrue(unknown.containsKey("IF_revista"));
    }

    @Test
    void eachStandardCountsTheDatabasesItRecognises() {
        Set<String> dbs = Set.of("SCIE", "ESCI", "SCOPUS", "ERIH", "DOAJ", "DBLP", "OPENALEX");
        assertEquals(4, RegistryScoringSupport.recognisedDatabases(dbs, null), "WoS once, Scopus, ERIH, DOAJ");
        assertEquals(List.of("Web of Science", "DOAJ", "ERIH", "SCOPUS"), RegistryScoringSupport.recognisedDatabaseNames(dbs));
        assertEquals(0, RegistryScoringSupport.recognisedDatabases(Set.of("DBLP", "OPENALEX"), null));
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
        assertEquals("TRIADIC", bind(Map.of(), Map.of("Cod brevet", "EP 1234567 B1; US 9876543 B2; JP 6789012 B2")).get("Tip_brevet"));
    }

    @Test
    void proseAndApplicationsAreNotPatentsOfAnOffice() {
        // H145: the old parser read these as Germany, India, Australia, the EPO and WIPO
        assertEquals("NATIONAL", RegistryScoringSupport.patentType("RO 123456 B1", "OSIM, acordat in 2020, data de 15.06.2021"));
        assertNull(RegistryScoringSupport.patentType("au 3 autori", "depozit la Iasi, compilatie de documente"));
        assertNull(RegistryScoringSupport.patentType("EP 1234567 A1", null), "an application is not a granted patent");
        assertNull(RegistryScoringSupport.patentType("US 2021/0123456 A1", null));
        assertEquals("NATIONAL", RegistryScoringSupport.patentType("RO 123456 B1; PCT/RO2019/000123", null),
                "a PCT application is neither a Romanian patent nor a WIPO registration");
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
        assertTrue(RegistryScoringSupport.isOwnUniversity("Universitatea de Vest din Timișoara"));
        assertFalse(RegistryScoringSupport.isOwnUniversity("Universitatea din București"));
    }

    private static ArtisticEvent event(String name, ArtisticEvent.Rank rank, ArtisticEvent.Kind kind) {
        ArtisticEvent e = new ArtisticEvent();
        e.setName(name);
        e.setRank(rank);
        e.setKind(kind);
        e.setDomainId("Muzică");
        return e;
    }
}
