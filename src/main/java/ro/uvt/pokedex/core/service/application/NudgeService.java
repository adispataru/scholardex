package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.notification.DirectedNotification;
import ro.uvt.pokedex.core.model.notification.DirectedNotification.NudgeKind;
import ro.uvt.pokedex.core.repository.notification.DirectedNotificationRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Supervisor → researcher nudges. A nudge is a persisted {@link DirectedNotification} the recipient
 * sees in their workspace bell. Idempotent per (recipient, sender, kind): re-nudging an active one
 * bumps its timestamp and note rather than stacking duplicates, so a supervisor can't spam.
 */
@Service
@RequiredArgsConstructor
public class NudgeService {

    /** Free-text note cap — enforced here so no call site can persist an oversized note. */
    public static final int MAX_NOTE_LENGTH = 200;

    private final DirectedNotificationRepository directedNotificationRepository;

    /**
     * Sends (or refreshes) a nudge. Returns the persisted notification.
     */
    public DirectedNotification nudge(String senderUserId, String recipientUserId, NudgeKind kind, String note) {
        String trimmedNote = normalizeNote(note);
        Optional<DirectedNotification> active = directedNotificationRepository
                .findByRecipientUserIdAndSenderUserIdAndKindAndDismissedAtIsNull(recipientUserId, senderUserId, kind);
        DirectedNotification notification = active.orElseGet(DirectedNotification::new);
        notification.setRecipientUserId(recipientUserId);
        notification.setSenderUserId(senderUserId);
        notification.setKind(kind);
        notification.setNote(trimmedNote);
        notification.setCreatedAt(Instant.now());
        notification.setDismissedAt(null);
        return directedNotificationRepository.save(notification);
    }

    /** Active nudges addressed to a recipient (for the workspace bell). */
    public List<DirectedNotification> activeFor(String recipientUserId) {
        if (recipientUserId == null || recipientUserId.isBlank()) {
            return List.of();
        }
        return directedNotificationRepository.findByRecipientUserIdAndDismissedAtIsNull(recipientUserId);
    }

    private String normalizeNote(String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_NOTE_LENGTH ? trimmed.substring(0, MAX_NOTE_LENGTH) : trimmed;
    }
}
