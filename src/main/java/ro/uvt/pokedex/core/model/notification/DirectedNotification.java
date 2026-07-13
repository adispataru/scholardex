package ro.uvt.pokedex.core.model.notification;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A notification one user directs at another — currently a supervisor's nudge to a researcher in
 * their scope. Unlike the researcher's own workspace notifications (derived on the fly from their
 * state), a directed notification is persisted because it originates from someone else.
 *
 * <p>At most one <em>active</em> nudge exists per (recipient, sender, kind): re-nudging bumps
 * {@link #createdAt} instead of stacking duplicates. Dismissal is recorded two ways — the recipient's
 * workspace dismissed-id set filters it from the bell, and {@link #dismissedAt} lets the store retire
 * it server-side.</p>
 */
@Data
@Document(collection = "scholardex.directed_notifications")
@CompoundIndex(name = "recipient_sender_kind_active",
        def = "{'recipientUserId': 1, 'senderUserId': 1, 'kind': 1, 'dismissedAt': 1}")
public class DirectedNotification {

    @Id
    private String id;

    /** Email of the researcher who sees the nudge. */
    @Indexed
    private String recipientUserId;

    /** Email of the supervisor who sent it. */
    private String senderUserId;

    private NudgeKind kind;

    /** Optional free-text note from the sender (capped and escaped at the edge). */
    private String note;

    private Instant createdAt;

    /** Null while the nudge is active. */
    private Instant dismissedAt;

    /** The preset reasons a supervisor can nudge about. */
    public enum NudgeKind {
        ONBOARD,
        CONFIRM_PUBLICATIONS,
        REFRESH_REPORT
    }
}
