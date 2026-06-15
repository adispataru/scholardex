package ro.uvt.pokedex.core.model.reporting.transfer;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Per-role / per-block totals taken from the run's dedicated indicator scores, keyed by role
     * key (e.g. publications/citations roles) or activity block name (e.g. "C1_UVT"). Used by
     * no-formula (DOCX) templates that must write final totals themselves; XLSX templates ignore
     * it (their own formulas compute totals).
     */
    private Map<String, Double> totals = new LinkedHashMap<>();
}
