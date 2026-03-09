package ro.uvt.pokedex.core.service.importing.scopus;

public record CanonicalBuildOptions(
        Integer chunkSizeOverride,
        Integer startBatchOverride,
        boolean useCheckpoint,
        String sourceVersionOverride,
        boolean reconcileSourceLinks,
        boolean reconcileEdges,
        boolean incremental,
        boolean drainQueues,
        boolean fullRescan
) {

    public CanonicalBuildOptions(
            Integer chunkSizeOverride,
            Integer startBatchOverride,
            boolean useCheckpoint,
            String sourceVersionOverride,
            boolean reconcileSourceLinks,
            boolean reconcileEdges
    ) {
        this(chunkSizeOverride, startBatchOverride, useCheckpoint, sourceVersionOverride, reconcileSourceLinks, reconcileEdges, true, true, false);
    }

    public static CanonicalBuildOptions defaults() {
        return new CanonicalBuildOptions(null, null, true, null, false, false, true, true, false);
    }

    public static CanonicalBuildOptions noCheckpoint() {
        return new CanonicalBuildOptions(null, null, false, null, false, false, true, true, false);
    }
}
