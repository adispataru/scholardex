package ro.uvt.pokedex.core.model.reporting.transfer;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "reportImportSessions")
public class ReportImportSession {
    @Id
    private String id;

    @Indexed
    private String userEmail;
    private String reportTypeKey;
    private String reportDefinitionId;
    private String sourceRunId;
    private String uploadedFilename;
    private ReportFormat format;
    private Instant createdAt;

    // Mongo TTL index: documents are auto-deleted once expiresAt is reached.
    @Indexed(expireAfter = "0s")
    private Instant expiresAt;

    private ReportImportStatus status = ReportImportStatus.PENDING_REVIEW;
    private List<ReportImportItem> items = new ArrayList<>();
}
