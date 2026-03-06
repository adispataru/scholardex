package ro.uvt.pokedex.core.service.importing.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ImportProcessingResult {
    private final int maxErrorsSample;
    private int processedCount;
    private int importedCount;
    private int updatedCount;
    private int skippedCount;
    private int errorCount;
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

    public int getProcessedCount() {
        return processedCount;
    }

    public int getImportedCount() {
        return importedCount;
    }

    public int getUpdatedCount() {
        return updatedCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public List<String> getErrorsSample() {
        return Collections.unmodifiableList(errorsSample);
    }
}
