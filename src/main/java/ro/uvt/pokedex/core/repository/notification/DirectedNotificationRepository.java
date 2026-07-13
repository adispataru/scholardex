package ro.uvt.pokedex.core.repository.notification;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import ro.uvt.pokedex.core.model.notification.DirectedNotification;
import ro.uvt.pokedex.core.model.notification.DirectedNotification.NudgeKind;

import java.util.List;
import java.util.Optional;

@Repository
public interface DirectedNotificationRepository extends MongoRepository<DirectedNotification, String> {

    List<DirectedNotification> findByRecipientUserIdAndDismissedAtIsNull(String recipientUserId);

    Optional<DirectedNotification> findByRecipientUserIdAndSenderUserIdAndKindAndDismissedAtIsNull(
            String recipientUserId, String senderUserId, NudgeKind kind);
}
