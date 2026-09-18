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

    @Test
    void updateActivityInstanceUpdatesFieldsOnly() {
        ActivityInstance existing = new ActivityInstance();
        existing.setId("i1");
        ActivityInstance incoming = new ActivityInstance();
        incoming.setId("i1");
        incoming.setFields(Map.of());
        incoming.setReferenceFields(Map.of());
        when(activityInstanceRepository.findById("i1")).thenReturn(Optional.of(existing));

        facade.updateActivityInstance(incoming);

        verify(activityInstanceRepository).save(existing);
    }

    @Test
    void findAndDeleteDelegateToRepository() {
        when(activityInstanceRepository.findById("i1")).thenReturn(Optional.of(new ActivityInstance()));
        assertTrue(facade.findActivityInstance("i1").isPresent());
        facade.deleteActivityInstance("i1");
        verify(activityInstanceRepository).deleteById("i1");
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
}
