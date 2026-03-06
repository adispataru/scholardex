package ro.uvt.pokedex.core.model.reporting.wos;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "wos.journal_identity")
@CompoundIndex(name = "uniq_identity_key", def = "{'identityKey': 1}", unique = true)
public class WosJournalIdentity {
    @Id
    private String id;
    @Indexed
    private String identityKey;
    @Indexed
    private String primaryIssn;
    @Indexed
    private String eIssn;
    @Indexed
    private List<String> aliasIssns = new ArrayList<>();
    private String mergeGroupId;
    private String conflictType;
    private String conflictReason;
    private Instant conflictDetectedAt;
    private String title;
    @Indexed
    private String normalizedTitle;
    private boolean active = true;
    private Instant createdAt;
    private Instant updatedAt;
}
