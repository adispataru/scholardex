package ro.uvt.pokedex.core.service.issn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.issn.IssnVerification;
import ro.uvt.pokedex.core.model.issn.IssnVerification.Status;
import ro.uvt.pokedex.core.repository.issn.IssnVerificationRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IssnVerificationServiceTest {

    @Mock private IssnVerificationRepository repository;
    @Mock private IssnPortalClient portalClient;
    @Mock private ro.uvt.pokedex.core.repository.ActivityInstanceRepository activityInstanceRepository;
    @InjectMocks private IssnVerificationService service;

    @Test
    void aConfirmedIssnIsStoredWithItsOfficialTitle() {
        when(repository.findById("1583-7165")).thenReturn(Optional.empty());
        when(portalClient.lookup("1583-7165")).thenReturn(new IssnPortalClient.Lookup(true, Optional.of("Anale. Seria Informatică")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IssnVerification v = service.verify("1583-7165");

        assertEquals(Status.VERIFIED, v.getStatus());
        assertEquals("Anale. Seria Informatică", v.getKeyTitle());
        assertEquals(1, v.getAttempts());
    }

    @Test
    void couldNotAskIsUnverifiedAndAClearNoIsNotFound() {
        when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(portalClient.lookup("1583-7165")).thenThrow(new IssnPortalClient.IssnPortalUnavailableException("down", null));
        when(portalClient.lookup("9999-9994")).thenReturn(new IssnPortalClient.Lookup(false, Optional.empty()));

        assertEquals(Status.UNVERIFIED, service.verify("1583-7165").getStatus());
        assertEquals(Status.NOT_FOUND, service.verify("9999-9994").getStatus());
    }

    @Test
    void aVerifiedRecordIsFinalAndNotAskedAgain() {
        IssnVerification done = new IssnVerification();
        done.setIssn("1583-7165");
        done.setStatus(Status.VERIFIED);
        when(repository.findById("1583-7165")).thenReturn(Optional.of(done));

        service.verify("1583-7165");

        verify(portalClient, never()).lookup(any());
        verify(repository, never()).save(any());
    }

    @Test
    void theNightlyRetryPromotesWhatTheRegisterNowConfirms() {
        IssnVerification pending = new IssnVerification();
        pending.setIssn("2068-3227");
        pending.setStatus(Status.UNVERIFIED);
        pending.setAttempts(1);
        when(repository.findByStatus(Status.UNVERIFIED)).thenReturn(List.of(pending));
        when(repository.findById("2068-3227")).thenReturn(Optional.of(pending));
        when(portalClient.lookup("2068-3227")).thenReturn(new IssnPortalClient.Lookup(true, Optional.of("GeoGebra")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.retryUnverified();

        assertEquals(Status.VERIFIED, pending.getStatus());
        assertEquals(2, pending.getAttempts());
    }

    @Test
    void anOutageDuringRetryKeepsTheRecordUnverified() {
        IssnVerification pending = new IssnVerification();
        pending.setIssn("2068-3227");
        pending.setStatus(Status.UNVERIFIED);
        when(repository.findByStatus(Status.UNVERIFIED)).thenReturn(List.of(pending));
        when(repository.findById("2068-3227")).thenReturn(Optional.of(pending));
        when(portalClient.lookup("2068-3227")).thenThrow(new IssnPortalClient.IssnPortalUnavailableException("down", null));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.retryUnverified();

        assertEquals(Status.UNVERIFIED, pending.getStatus());
    }

    @Test
    void issnsOnEntriesThatPredateTheCheckAreQueuedOnceAndMistypedOnesIgnored() {
        ro.uvt.pokedex.core.model.activities.ActivityInstance old = new ro.uvt.pokedex.core.model.activities.ActivityInstance();
        old.setReferenceFields(new java.util.EnumMap<>(java.util.Map.of(
                ro.uvt.pokedex.core.model.activities.Activity.ReferenceField.FORUM_ISSN, "1583-7165",
                ro.uvt.pokedex.core.model.activities.Activity.ReferenceField.FORUM_EISSN, "1234-5678")));
        ro.uvt.pokedex.core.model.activities.ActivityInstance known = new ro.uvt.pokedex.core.model.activities.ActivityInstance();
        known.setReferenceFields(new java.util.EnumMap<>(java.util.Map.of(
                ro.uvt.pokedex.core.model.activities.Activity.ReferenceField.FORUM_ISSN, "2068-3227")));
        when(activityInstanceRepository.findAll()).thenReturn(List.of(old, known, new ro.uvt.pokedex.core.model.activities.ActivityInstance()));
        when(repository.existsById("1583-7165")).thenReturn(false);
        when(repository.existsById("2068-3227")).thenReturn(true);

        assertEquals(1, service.enqueueFromActivities());

        org.mockito.ArgumentCaptor<IssnVerification> saved = org.mockito.ArgumentCaptor.forClass(IssnVerification.class);
        verify(repository).save(saved.capture());
        assertEquals("1583-7165", saved.getValue().getIssn());
        assertEquals(Status.UNVERIFIED, saved.getValue().getStatus());
    }
}
