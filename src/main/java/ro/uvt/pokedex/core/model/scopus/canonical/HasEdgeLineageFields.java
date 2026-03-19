package ro.uvt.pokedex.core.model.scopus.canonical;

import java.time.Instant;

public interface HasEdgeLineageFields extends HasLineageFields {
    void setLinkState(String linkState);
    void setLinkReason(String linkReason);
    void setUpdatedAt(Instant updatedAt);

    String getSourceRecordId();
    String getSourceEventId();
    String getSourceBatchId();
    String getSourceCorrelationId();
    String getLinkState();
    String getLinkReason();
}
