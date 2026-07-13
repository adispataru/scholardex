package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.notification.DirectedNotification;
import ro.uvt.pokedex.core.model.notification.DirectedNotification.NudgeKind;
import ro.uvt.pokedex.core.repository.notification.DirectedNotificationRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NudgeServiceTest {

    @Mock private DirectedNotificationRepository repository;

    private NudgeService service() {
        return new NudgeService(repository);
    }

    @Test
    void firstNudgeCreatesAFreshNotification() {
        when(repository.findByRecipientUserIdAndSenderUserIdAndKindAndDismissedAtIsNull(
                "bob@uvt.ro", "ana@uvt.ro", NudgeKind.ONBOARD)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().nudge("ana@uvt.ro", "bob@uvt.ro", NudgeKind.ONBOARD, "please set up");

        ArgumentCaptor<DirectedNotification> saved = ArgumentCaptor.forClass(DirectedNotification.class);
        org.mockito.Mockito.verify(repository).save(saved.capture());
        DirectedNotification n = saved.getValue();
        assertEquals("bob@uvt.ro", n.getRecipientUserId());
        assertEquals("ana@uvt.ro", n.getSenderUserId());
        assertEquals(NudgeKind.ONBOARD, n.getKind());
        assertEquals("please set up", n.getNote());
        assertNull(n.getId(), "a brand-new notification has no id yet");
    }

    @Test
    void reNudgeReusesTheActiveNotificationInsteadOfDuplicating() {
        DirectedNotification existing = new DirectedNotification();
        existing.setId("nudge-1");
        existing.setRecipientUserId("bob@uvt.ro");
        existing.setSenderUserId("ana@uvt.ro");
        existing.setKind(NudgeKind.ONBOARD);
        when(repository.findByRecipientUserIdAndSenderUserIdAndKindAndDismissedAtIsNull(
                "bob@uvt.ro", "ana@uvt.ro", NudgeKind.ONBOARD)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DirectedNotification result = service().nudge("ana@uvt.ro", "bob@uvt.ro", NudgeKind.ONBOARD, null);

        assertSame(existing, result, "re-nudge updates the SAME row, not a new one");
        assertEquals("nudge-1", result.getId());
        assertNull(result.getNote(), "a blank/absent note clears to null");
    }

    @Test
    void oversizedNoteIsCappedAtTheMaxLength() {
        when(repository.findByRecipientUserIdAndSenderUserIdAndKindAndDismissedAtIsNull(
                any(), any(), any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String longNote = "x".repeat(NudgeService.MAX_NOTE_LENGTH + 50);
        DirectedNotification n = service().nudge("ana@uvt.ro", "bob@uvt.ro", NudgeKind.REFRESH_REPORT, longNote);

        assertEquals(NudgeService.MAX_NOTE_LENGTH, n.getNote().length());
    }
}
