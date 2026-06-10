package ro.uvt.pokedex.core.service.application;

/**
 * Provenance carried into a canonical (Scholardex) fact write (H54.5).
 *
 * <p>The writer stamps {@code source}, {@code sourceRecordId}, {@code sourceBatchId} and
 * {@code sourceCorrelationId} uniformly onto the fact and records them on the source link.
 * {@code sourceEventId} is recorded on the source link but is NOT stamped onto the fact by the
 * writer — callers that set the fact's own {@code sourceEventId} as content keep doing so, and
 * callers that intentionally leave it untouched are unaffected.
 */
public record CanonicalWriteProvenance(
        String source,
        String sourceRecordId,
        String sourceBatchId,
        String sourceCorrelationId,
        String sourceEventId
) {
    public static CanonicalWriteProvenance of(
            String source, String sourceRecordId, String sourceBatchId, String sourceCorrelationId) {
        return new CanonicalWriteProvenance(source, sourceRecordId, sourceBatchId, sourceCorrelationId, null);
    }
}
