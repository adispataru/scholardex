package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "scholardex.publication_link_conflicts")
public class PublicationLinkConflict {
    @Id
    private String id;
    private String conflictType;
    private String conflictReason;
    private String enrichmentSource;
    private String keyType;
    private String keyValue;
    private String requestedPublicationId;
    private String requestedEid;
    private String requestedDoiNormalized;
    private String targetPublicationId;
    private List<String> candidatePublicationIds = new ArrayList<>();
    private String linkerVersion;
    private String linkerRunId;
    private Instant detectedAt;
}
