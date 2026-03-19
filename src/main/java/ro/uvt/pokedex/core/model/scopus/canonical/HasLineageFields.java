package ro.uvt.pokedex.core.model.scopus.canonical;

public interface HasLineageFields {
    void setSourceEventId(String sourceEventId);
    void setSource(String source);
    void setSourceRecordId(String sourceRecordId);
    void setSourceBatchId(String sourceBatchId);
    void setSourceCorrelationId(String sourceCorrelationId);
}
