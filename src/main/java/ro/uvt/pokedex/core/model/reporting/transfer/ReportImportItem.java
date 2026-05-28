package ro.uvt.pokedex.core.model.reporting.transfer;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ReportImportItem {
    private String itemKey;
    private String entityType;
    private ReportImportBucket bucket;
    private SnapshotItem incomingSnapshot;
    private String matchedEntityId;
    private List<FieldDiff> fieldDiffs = new ArrayList<>();
    private String blockerReason;
    private ReportImportDecision userDecision = ReportImportDecision.PENDING;
    private String committedEntityId;
    private String commitError;
}
