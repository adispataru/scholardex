package ro.uvt.pokedex.core.service.importing.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ImportProcessingResult {
    private final int maxErrorsSample;
    @Getter private int processedCount;
    @Getter private int importedCount;
    @Getter private int updatedCount;
    @Getter private int skippedCount;
    @Getter private int errorCount;
    @Getter @Setter private Integer startBatch;
    @Getter @Setter private Integer endBatch;
    @Getter @Setter private Integer batchesProcessed;
    @Getter @Setter private Integer totalBatches;
    @Getter @Setter private Boolean resumedFromCheckpoint;
    @Getter @Setter private Integer checkpointLastCompletedBatch;
    private final List<String> errorsSample = new ArrayList<>();

    public ImportProcessingResult(int maxErrorsSample) {
        this.maxErrorsSample = Math.max(0, maxErrorsSample);
    }

    public void markProcessed() {
        processedCount++;
    }

    public void markImported() {
        importedCount++;
    }

    public void markUpdated() {
        updatedCount++;
    }

    public void markSkipped(String reason) {
        skippedCount++;
    }

    public void markError(String errorMessage) {
        errorCount++;
        if (errorsSample.size() < maxErrorsSample) {
            errorsSample.add(errorMessage);
        }
    }

    public List<String> getErrorsSample() {
        return Collections.unmodifiableList(errorsSample);
    }
}
