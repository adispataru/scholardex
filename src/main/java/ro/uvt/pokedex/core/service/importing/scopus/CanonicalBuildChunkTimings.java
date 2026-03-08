package ro.uvt.pokedex.core.service.importing.scopus;

public record CanonicalBuildChunkTimings(
        long preloadMs,
        long resolveMs,
        long upsertMs,
        long saveMs,
        long totalMs
) {
}
