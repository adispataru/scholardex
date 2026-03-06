package ro.uvt.pokedex.core.model.reporting.wos;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "wos.fact_conflicts")
public class WosFactConflict {
    @Id
    private String id;
    private String factType;
    private String conflictReason;
    private String factKey;
    private String winnerSourceEventId;
    private String winnerSourceFile;
    private String winnerSourceVersion;
    private String winnerSourceRowItem;
    private WosSourceType winnerSourceType;
    private String winnerValueSnapshot;
    private String loserSourceEventId;
    private String loserSourceFile;
    private String loserSourceVersion;
    private String loserSourceRowItem;
    private WosSourceType loserSourceType;
    private String loserValueSnapshot;
    private Instant detectedAt;
}
