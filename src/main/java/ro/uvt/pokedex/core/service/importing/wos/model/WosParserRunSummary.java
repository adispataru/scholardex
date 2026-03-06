package ro.uvt.pokedex.core.service.importing.wos.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WosParserRunSummary {
    private final int maxSamples;
    private int processedCount;
    private int parsedCount;
    private int skippedCount;
    private int errorCount;
    private final List<String> samples = new ArrayList<>();

    public WosParserRunSummary(int maxSamples) {
        this.maxSamples = Math.max(maxSamples, 0);
    }

    public void markProcessed() {
        processedCount++;
    }

    public void markParsed() {
        parsedCount++;
    }

    public void markSkipped(String sample) {
        skippedCount++;
        addSample(sample);
    }

    public void markError(String sample) {
        errorCount++;
        addSample(sample);
    }

    private void addSample(String sample) {
        if (sample == null || sample.isBlank()) {
            return;
        }
        if (samples.size() < maxSamples) {
            samples.add(sample);
        }
    }

    public int getProcessedCount() {
        return processedCount;
    }

    public int getParsedCount() {
        return parsedCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public List<String> getSamples() {
        return Collections.unmodifiableList(samples);
    }
}
