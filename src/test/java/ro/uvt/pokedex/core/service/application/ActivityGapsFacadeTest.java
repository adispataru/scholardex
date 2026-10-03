package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.activities.PublisherClaim;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.service.reporting.RegistryScoringSupport;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** H142 slice 7 — what a record lacks to count that its owner can supply. */
class ActivityGapsFacadeTest {

    private final ActivityInstanceRepository repository = mock(ActivityInstanceRepository.class);
    private final ActivityGapsFacade facade = new ActivityGapsFacade(repository);

    @AfterEach
    void resetLookups() {
        RegistryScoringSupport.reset();
    }

    private static Activity type(List<String> fields, List<Activity.ReferenceField> references) {
        Activity a = new Activity();
        a.setId("t");
        a.setName("t");
        a.setFields(fields.stream().map(n -> { Activity.Field f = new Activity.Field(); f.setName(n); return f; }).toList());
        a.setReferenceFields(references);
        return a;
    }

    private static final Activity PERFORMANCE = type(List.of("Rol", "Marime_formatie", "Rezultat", "Dovezi"),
            List.of(Activity.ReferenceField.EVENT_NAME));
    private static final Activity ARTICLE = type(List.of("Titlu", "Dovezi", PublisherClaim.REQUEST_FIELD, PublisherClaim.EVIDENCE_FIELD),
            List.of(Activity.ReferenceField.FORUM_ISSN));

    private static ActivityInstance record(String id, Activity type, Map<String, String> fields, Map<Activity.ReferenceField, String> refs) {
        ActivityInstance r = new ActivityInstance();
        r.setId(id);
        r.setActivity(type);
        r.setDate("2024-01-01");
        r.setFields(fields);
        r.setReferenceFields(refs);
        return r;
    }

    private static List<ActivityGapsFacade.Gap> gaps(ActivityInstance r) {
        return ActivityGapsFacade.gapsOf(r).stream().map(ActivityGapsFacade.RecordGap::gap).toList();
    }

    @Test
    void aPerformanceLacksItsRoleOnlyWhenTheEnsemblesSizeDoesNotAnswerForIt() {
        assertEquals(List.of(ActivityGapsFacade.Gap.ROLE), gaps(record("a", PERFORMANCE,
                Map.of("Marime_formatie", "5", "Rezultat", "Participare"), Map.of())), "a collective of 5 or more");
        assertEquals(List.of(ActivityGapsFacade.Gap.ROLE), gaps(record("b", PERFORMANCE, Map.of("Rezultat", "Participare"), Map.of())),
                "no size");
        assertEquals(List.of(), gaps(record("c", PERFORMANCE, Map.of("Marime_formatie", "2", "Rezultat", "Participare"), Map.of())),
                "a group of two to four counts without a role");
        assertEquals(List.of(), gaps(record("d", PERFORMANCE, Map.of("Marime_formatie", "5", "Rol", "Dirijor"), Map.of())));
        assertEquals(List.of(), gaps(record("e", PERFORMANCE, Map.of("Marime_formatie", "5", "Rezultat", "Premiu"), Map.of())),
                "a prize needs no role");
        assertEquals(List.of(), gaps(record("f", PERFORMANCE,
                Map.of("Rol", "Membru într-un ansamblu de peste 10 persoane"), Map.of())), "a role the standard leaves out is no gap");
    }

    @Test
    void anArticleLacksItsIssnOrARequestForAJournalNoListCovers() {
        RegistryScoringSupport.Lookups lookups = mock(RegistryScoringSupport.Lookups.class);
        when(lookups.journal(anyString(), anyInt())).thenReturn(Optional.empty());
        when(lookups.journal(org.mockito.ArgumentMatchers.eq("1234-5679"), anyInt())).thenReturn(Optional.of(
                new RegistryScoringSupport.JournalFacts(false, false, false, false, Set.of("ERIH"), null, null)));
        when(lookups.journal(org.mockito.ArgumentMatchers.eq("2734-6897"), anyInt())).thenReturn(Optional.of(
                new RegistryScoringSupport.JournalFacts(false, false, false, false, Set.of(), null, null)));
        RegistryScoringSupport.register(lookups);

        assertEquals(List.of(ActivityGapsFacade.Gap.ISSN), gaps(record("a", ARTICLE, Map.of("Titlu", "x"), Map.of())));
        List<ActivityGapsFacade.RecordGap> unlisted = ActivityGapsFacade.gapsOf(record("b", ARTICLE, Map.of(),
                Map.of(Activity.ReferenceField.FORUM_ISSN, "2734-6897")));
        assertEquals(List.of(new ActivityGapsFacade.RecordGap(ActivityGapsFacade.Gap.JOURNAL_UNLISTED, "2734-6897")), unlisted);
        assertEquals(List.of(ActivityGapsFacade.Gap.JOURNAL_UNLISTED), gaps(record("c", ARTICLE, Map.of(),
                Map.of(Activity.ReferenceField.FORUM_ISSN, "0000-0000"))), "a journal the platform does not hold");
        assertEquals(List.of(), gaps(record("d", ARTICLE, Map.of(), Map.of(Activity.ReferenceField.FORUM_ISSN, "1234-5679"))),
                "in a database the lists know");

        ActivityInstance asked = record("e", ARTICLE, Map.of(PublisherClaim.REQUEST_FIELD, "ERIH PLUS"), Map.of());
        PublisherClaim claim = new PublisherClaim();
        claim.setStatus(PublisherClaim.Status.PENDING);
        asked.setPublisherClaim(claim);
        assertEquals(List.of(), gaps(asked), "a request the head is deciding shows with the request");
        claim.setStatus(PublisherClaim.Status.REJECTED);
        assertEquals(List.of(ActivityGapsFacade.Gap.ISSN), gaps(asked), "a rejected request leaves the gap");
    }

    @Test
    void theResearchersRecordsThatLackSomethingAreListedByTheirId() {
        when(repository.findAllByResearcherId("ion@e-uvt.ro")).thenReturn(List.of(
                record("gap", PERFORMANCE, Map.of("Marime_formatie", "5"), Map.of()),
                record("ok", PERFORMANCE, Map.of("Marime_formatie", "1"), Map.of())));

        assertEquals(Set.of("gap"), facade.gapsFor("ion@e-uvt.ro").keySet());
        assertEquals(Map.of(), facade.gapsFor(" "));
    }
}
