package ro.uvt.pokedex.core.model.org;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Time-bounded link between a user and a research group. Replaces the legacy
 * {@code Group.memberIds} list of emails.
 */
@Data
@Document(collection = "scholardex.memberships")
@CompoundIndexes({
        @CompoundIndex(name = "group_user_current", def = "{'groupId': 1, 'userId': 1, 'validTo': 1}"),
        @CompoundIndex(name = "user_current", def = "{'userId': 1, 'validTo': 1}")
})
public class Membership {
    @Id
    private String id;

    @Indexed
    private String userId;

    @Indexed
    private String groupId;

    private MembershipRole role;

    private LocalDate validFrom;

    /** Null = current. */
    private LocalDate validTo;

    private Instant createdAt;
}
