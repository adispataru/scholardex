package ro.uvt.pokedex.core.model.reporting.wos;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "wos.identity_conflicts")
public class WosIdentityConflict {
    @Id
    private String id;
    private String conflictType;
    private String conflictReason;
    private Instant conflictDetectedAt;
    @Indexed
    private String sourceEventId;
    private String sourceFile;
    private String sourceVersion;
    private String sourceRowItem;
    private String inputIdentityKey;
    private List<String> inputIssnTokens = new ArrayList<>();
    private String inputTitle;
    private String inputNormalizedTitle;
    private List<String> candidateJournalIds = new ArrayList<>();
    private List<String> candidateIdentityKeys = new ArrayList<>();
}
