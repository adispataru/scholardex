package ro.uvt.pokedex.core.model.reporting.transfer;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
public class ReportInstanceSnapshot {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private int schemaVersion = CURRENT_SCHEMA_VERSION;
    private String reportTypeKey;
    private String reportDefinitionId;
    private String sourceRunId;
    private Instant exportedAt;
    private String exportedBy;
    private List<SnapshotItem> items = new ArrayList<>();
}
