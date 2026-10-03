package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.ActivityRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserActivityInstanceFacadeTest {

    @Mock
    private ActivityInstanceRepository activityInstanceRepository;
    @Mock
    private ActivityRepository activityRepository;

    @Mock
    private ro.uvt.pokedex.core.service.issn.IssnVerificationService issnVerificationService;
    @Mock
    private ro.uvt.pokedex.core.service.reporting.ReportingLookupPort reportingLookupPort;

    @InjectMocks
    private UserActivityInstanceFacade facade;

    @Test
    void buildViewPopulatesListsAndMetrics() {
        Activity activity = new Activity();
        activity.setId("a1");
        activity.setName("Teaching");
        ActivityInstance instance = new ActivityInstance();
        instance.setId("i1");
        instance.setActivity(activity);
        instance.setResearcherId("r1");

        when(activityRepository.findAll()).thenReturn(List.of(activity));
        when(activityInstanceRepository.findAllByResearcherId("r1")).thenReturn(List.of(instance));

        var vm = facade.buildActivityInstancesView("r1");
        assertEquals(1, vm.activities().size());
        assertEquals(1, vm.activityInstances().size());
        assertEquals(1, vm.activityLabels().size());
    }

    private static Activity typeWithRole() {
        Activity type = new Activity();
        type.setId("t1");
        Activity.Field role = new Activity.Field();
        role.setName("Rol");
        role.setAllowedValues(List.of("Membru", "Director"));
        Activity.Field budget = new Activity.Field();
        budget.setName("Buget");
        budget.setNumber(true);
        type.setFields(List.of(role, budget));
        return type;
    }

    private static ActivityInstance owned(String owner) {
        ActivityInstance existing = new ActivityInstance();
        existing.setId("i1");
        existing.setResearcherId(owner);
        existing.setActivity(typeWithRole());
        return existing;
    }

    @Test
    void updateActivityInstanceUpdatesFieldsOnly() {
        ActivityInstance existing = owned("r1@uvt.ro");
        ActivityInstance incoming = new ActivityInstance();
        incoming.setId("i1");
        incoming.setFields(Map.of("Rol", "Membru"));
        incoming.setReferenceFields(Map.of());
        when(activityInstanceRepository.findById("i1")).thenReturn(Optional.of(existing));

        assertTrue(facade.updateActivityInstance(incoming, "R1@uvt.ro"));

        verify(activityInstanceRepository).save(existing);
        assertEquals("Membru", existing.getFields().get("Rol"));
    }

    @Test
    void findAndDeleteDelegateToRepository() {
        when(activityInstanceRepository.findById("i1")).thenReturn(Optional.of(owned("r1@uvt.ro")));
        assertTrue(facade.findActivityInstance("i1").isPresent());
        assertTrue(facade.deleteActivityInstance("i1", "r1@uvt.ro"));
        verify(activityInstanceRepository).deleteById("i1");
    }

    @Test
    void anotherResearchersRecordIsNeitherChangedNorRemoved() {
        when(activityInstanceRepository.findById("i1")).thenReturn(Optional.of(owned("owner@uvt.ro")));
        ActivityInstance incoming = new ActivityInstance();
        incoming.setId("i1");
        incoming.setFields(Map.of("Rol", "Director"));

        org.junit.jupiter.api.Assertions.assertFalse(facade.updateActivityInstance(incoming, "intruder@uvt.ro"));
        org.junit.jupiter.api.Assertions.assertFalse(facade.deleteActivityInstance("i1", "intruder@uvt.ro"));
        org.junit.jupiter.api.Assertions.assertFalse(facade.deleteActivityInstance("i1", null));
        verify(activityInstanceRepository, never()).save(any());
        verify(activityInstanceRepository, never()).deleteById(any());
    }

    @Test
    void aValueTheTypeDoesNotAcceptStopsTheSave() {
        when(activityInstanceRepository.findById("i1")).thenReturn(Optional.of(owned("r1@uvt.ro")));
        ActivityInstance incoming = new ActivityInstance();
        incoming.setId("i1");
        incoming.setFields(Map.of("Buget", "Infinity"));

        ActivityValidationException e = org.junit.jupiter.api.Assertions.assertThrows(ActivityValidationException.class,
                () -> facade.updateActivityInstance(incoming, "r1@uvt.ro"));
        assertEquals(List.of("Buget: «Infinity»"), e.getProblems());
        verify(activityInstanceRepository, never()).save(any());
    }

    @Test
    void aNewRecordNeverCarriesAnApprovalFromTheCaller() {
        ActivityInstance fresh = new ActivityInstance();
        fresh.setResearcherId("r1@uvt.ro");
        fresh.setActivity(typeWithRole());
        fresh.setFields(Map.of("Rol", "Director"));
        ro.uvt.pokedex.core.model.activities.PublisherClaim forged = new ro.uvt.pokedex.core.model.activities.PublisherClaim();
        forged.setStatus(ro.uvt.pokedex.core.model.activities.PublisherClaim.Status.APPROVED);
        fresh.setPublisherClaim(forged);
        when(activityInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ActivityInstance saved = facade.saveActivityInstance(fresh);

        org.junit.jupiter.api.Assertions.assertNull(saved.getPublisherClaim());
    }

    // ── H110: journals named by ISSN ───────────────────────────────────────────────────────────────────────

    private static ro.uvt.pokedex.core.model.activities.ActivityInstance journalEntry(String issn) {
        ro.uvt.pokedex.core.model.activities.ActivityInstance instance = new ro.uvt.pokedex.core.model.activities.ActivityInstance();
        instance.setReferenceFields(new java.util.EnumMap<>(java.util.Map.of(
                ro.uvt.pokedex.core.model.activities.Activity.ReferenceField.FORUM_ISSN, issn)));
        return instance;
    }

    private static ro.uvt.pokedex.core.model.issn.IssnVerification verification(ro.uvt.pokedex.core.model.issn.IssnVerification.Status status) {
        ro.uvt.pokedex.core.model.issn.IssnVerification v = new ro.uvt.pokedex.core.model.issn.IssnVerification();
        v.setStatus(status);
        return v;
    }

    @org.junit.jupiter.api.Test
    void aMistypedIssnIsRejectedBeforeAnythingIsAsked() {
        ro.uvt.pokedex.core.service.issn.InvalidIssnException e = org.junit.jupiter.api.Assertions.assertThrows(
                ro.uvt.pokedex.core.service.issn.InvalidIssnException.class,
                () -> facade.validateJournalIssns(journalEntry("1234-5678")));
        org.junit.jupiter.api.Assertions.assertEquals("workspace.activities.issn.invalid", e.getMessageKey());
        org.mockito.Mockito.verifyNoInteractions(issnVerificationService);
    }

    @org.junit.jupiter.api.Test
    void aJournalWeAlreadyHoldIsNotSentToTheRegisterAndItsIssnIsNormalized() {
        org.mockito.Mockito.when(reportingLookupPort.getRankingsByIssn("1583-7165")).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(reportingLookupPort.findForumIdsByIssn("1583-7165", null)).thenReturn(java.util.List.of("sforum_1"));
        ro.uvt.pokedex.core.model.activities.ActivityInstance entry = journalEntry("15837165");

        facade.validateJournalIssns(entry);

        org.junit.jupiter.api.Assertions.assertEquals("1583-7165",
                entry.getReferenceFields().get(ro.uvt.pokedex.core.model.activities.Activity.ReferenceField.FORUM_ISSN));
        org.mockito.Mockito.verifyNoInteractions(issnVerificationService);
    }

    @org.junit.jupiter.api.Test
    void anIssnTheRegisterDeniesIsRejectedButCouldNotAskIsAccepted() {
        org.mockito.Mockito.when(reportingLookupPort.getRankingsByIssn(org.mockito.ArgumentMatchers.anyString())).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(reportingLookupPort.findForumIdsByIssn(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(java.util.List.of());
        org.mockito.Mockito.when(issnVerificationService.verify("9999-9994"))
                .thenReturn(verification(ro.uvt.pokedex.core.model.issn.IssnVerification.Status.NOT_FOUND));
        org.mockito.Mockito.when(issnVerificationService.verify("2068-3227"))
                .thenReturn(verification(ro.uvt.pokedex.core.model.issn.IssnVerification.Status.UNVERIFIED));

        ro.uvt.pokedex.core.service.issn.InvalidIssnException e = org.junit.jupiter.api.Assertions.assertThrows(
                ro.uvt.pokedex.core.service.issn.InvalidIssnException.class,
                () -> facade.validateJournalIssns(journalEntry("9999-9994")));
        org.junit.jupiter.api.Assertions.assertEquals("workspace.activities.issn.notFound", e.getMessageKey());

        facade.validateJournalIssns(journalEntry("2068-3227")); // must not throw
    }

    // ── H142 slice 7: the change log and the move to another type ──

    private static Activity type(String id, String name, List<String> fields, List<Activity.ReferenceField> references) {
        Activity a = new Activity();
        a.setId(id);
        a.setName(name);
        a.setFields(fields.stream().map(n -> { Activity.Field f = new Activity.Field(); f.setName(n); return f; }).toList());
        a.setReferenceFields(references);
        return a;
    }

    @Test
    void anEditIsLoggedOnTheRecordValueByValue() {
        ActivityInstance existing = owned("r1@uvt.ro");
        existing.setFields(new java.util.HashMap<>(Map.of("Rol", "Membru")));
        ActivityInstance incoming = new ActivityInstance();
        incoming.setId("i1");
        incoming.setFields(Map.of("Rol", "Director"));
        when(activityInstanceRepository.findById("i1")).thenReturn(Optional.of(existing));

        assertTrue(facade.updateActivityInstance(incoming, "r1@uvt.ro"));
        assertTrue(facade.updateActivityInstance(incoming, "r1@uvt.ro"), "the same values again");

        assertEquals(1, existing.getChanges().size(), "nothing changed the second time: nothing logged");
        assertEquals("EDITED", existing.getChanges().getFirst().getAction());
        assertEquals("r1@uvt.ro", existing.getChanges().getFirst().getBy());
        assertEquals("Rol: «Membru» → «Director»", existing.getChanges().getFirst().getNote());
    }

    @Test
    void aMovedRecordKeepsItsDateSourceAndImportKeyAndWhatTheNewTypeHasNoFieldForGoesToItsEvidence() {
        Activity article = type("t-art", "Articol (CS 2.1)", List.of("Titlu", "Revista_sau_volumul", "Dovezi"),
                List.of(Activity.ReferenceField.FORUM_ISSN));
        Activity edition = type("t-ed", "Ediție critică (DID 1.4)", List.of("Titlu", "Editura", "Dovezi"), List.of());
        ActivityInstance record = new ActivityInstance();
        record.setId("i1");
        record.setResearcherId("r1@uvt.ro");
        record.setActivity(article);
        record.setName("Sabin V. Drăgoi, 303 Colinde");
        record.setDate("2024-01-01");
        record.setImportSource("Raportare CNFIS 2025 (depusă de facultate) — Anexa 5 CNFIS: x.xlsx");
        record.setImportKey("key-1");
        record.setFields(new java.util.HashMap<>(Map.of("Titlu", "303 Colinde", "Revista_sau_volumul", "Eurostampa")));
        record.setReferenceFields(new java.util.EnumMap<>(Map.of(Activity.ReferenceField.FORUM_ISSN, "2734-6897")));
        when(activityInstanceRepository.findById("i1")).thenReturn(Optional.of(record));
        when(activityRepository.findById("t-ed")).thenReturn(Optional.of(edition));

        UserActivityInstanceFacade.MoveResult result = facade.moveActivityInstance("i1", "t-ed", "r1@uvt.ro");

        assertTrue(result.moved());
        assertEquals(List.of("Revista_sau_volumul: Eurostampa", "FORUM_ISSN: 2734-6897"), result.dropped());
        assertEquals("t-ed", record.getActivity().getId());
        assertEquals("2024-01-01", record.getDate());
        assertEquals("Sabin V. Drăgoi, 303 Colinde", record.getName());
        assertEquals("key-1", record.getImportKey(), "a re-import of the same file does not bring it back");
        assertTrue(record.getImportSource().startsWith("Raportare CNFIS 2025"));
        assertEquals("303 Colinde", record.getFields().get("Titlu"));
        assertEquals("Revista_sau_volumul: Eurostampa | FORUM_ISSN: 2734-6897", record.getFields().get("Dovezi"));
        assertTrue(record.getReferenceFields().isEmpty());
        var change = record.getChanges().getLast();
        assertEquals("MOVED", change.getAction());
        assertEquals("Articol (CS 2.1)", change.getFrom());
        assertEquals("Ediție critică (DID 1.4)", change.getTo());
        assertEquals("Revista_sau_volumul: Eurostampa; FORUM_ISSN: 2734-6897", change.getNote());
        verify(activityInstanceRepository).save(record);
    }

    @Test
    void onlyItsOwnerMovesARecordAndOnlyToATypeThatExists() {
        when(activityInstanceRepository.findById("i1")).thenReturn(Optional.of(owned("owner@uvt.ro")));
        when(activityRepository.findById("t-x")).thenReturn(Optional.of(type("t-x", "X", List.of(), List.of())));

        org.junit.jupiter.api.Assertions.assertFalse(facade.moveActivityInstance("i1", "t-x", "intruder@uvt.ro").moved());
        org.junit.jupiter.api.Assertions.assertFalse(facade.moveActivityInstance("i1", "t-none", "owner@uvt.ro").moved());
        verify(activityInstanceRepository, never()).save(any());
    }

    @Test
    void aTypeHeldOnceIsNotTakenASecondTimeByAMove() {
        Activity scholar = type("t-gs", "Google Scholar", List.of(), List.of());
        scholar.setSinglePerResearcher(true);
        ActivityInstance record = owned("r1@uvt.ro");
        ActivityInstance held = new ActivityInstance();
        held.setId("i2");
        held.setActivity(scholar);
        when(activityInstanceRepository.findById("i1")).thenReturn(Optional.of(record));
        when(activityRepository.findById("t-gs")).thenReturn(Optional.of(scholar));
        when(activityInstanceRepository.findAllByResearcherId("r1@uvt.ro")).thenReturn(List.of(record, held));

        org.junit.jupiter.api.Assertions.assertThrows(ActivitySingleRecordException.class,
                () -> facade.moveActivityInstance("i1", "t-gs", "r1@uvt.ro"));
        verify(activityInstanceRepository, never()).save(any());
    }
}
