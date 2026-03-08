package ro.uvt.pokedex.core.service.importing.scopus;

public record CanonicalBuildOptions(
        Integer chunkSizeOverride,
        Integer startBatchOverride,
        boolean useCheckpoint,
        String sourceVersionOverride,
        boolean reconcileSourceLinks
) {

    public static CanonicalBuildOptions defaults() {
        return new CanonicalBuildOptions(null, null, true, null, false);
    }

    public static CanonicalBuildOptions noCheckpoint() {
        return new CanonicalBuildOptions(null, null, false, null, false);
    }
}
