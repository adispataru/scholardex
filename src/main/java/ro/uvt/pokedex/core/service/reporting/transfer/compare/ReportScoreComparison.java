package ro.uvt.pokedex.core.service.reporting.transfer.compare;

import java.util.List;

/**
 * Result of comparing an uploaded report file's per-publication scores against the platform's
 * latest scores. Read-only. Each row is one publication (matched by title) carrying up to two
 * score dimensions: publication points (Punctaj P) and citation points.
 */
public record ReportScoreComparison(
        List<PublicationRow> rows,
        List<ActivityBlockComparison> activityBlocks,
        double platformTotal,
        double fileTotal,
        double correctPoints,
        int matchingCount,
        int differingCount) {

    public enum Status { MATCH, DIFFERS, ONLY_IN_FILE, ONLY_IN_PLATFORM }

    /** One Perspectiva D block (activity category): block-level totals plus per-item reconciliation. */
    public record ActivityBlockComparison(
            String blockName,
            Double platformTotal,
            Double fileTotal,
            Double delta,
            Status status,
            List<ActivityItem> matched,        // paired by fuzzy text (may be MATCH or DIFFERS)
            List<ActivityItem> importable,      // file-only — candidates to add to the platform
            List<ActivityItem> onlyInPlatform,  // platform-only — counted but absent from the file
            List<ActivityOption> activityOptions) {

        public boolean rowMatches() {
            return status == Status.MATCH;
        }

        public boolean hasImportable() {
            return importable != null && !importable.isEmpty();
        }
    }

    /** A single activity row in a block comparison. For importable items only {@code file} is set. */
    public record ActivityItem(
            String description,
            String category,
            Double platform,
            Double file,
            Status status,
            Double delta) {
    }

    /** A candidate Activity type for the inline "add to platform" form (dropdown when >1 per block). */
    public record ActivityOption(String activityId, String activityName) {
    }

    /** A single score dimension for a publication (platform value, file value, status, delta). */
    public record ScorePair(Double platform, Double file, Status status, Double delta) {
    }

    /** One citing publication's platform vs file score, for the per-citation breakdown on expansion. */
    public record CitationDetail(String citingTitle, Double platform, Double file, Status status, Double delta) {
    }

    /**
     * Citations found in an additional citation sheet that re-used an already-seen cited-paper title
     * (a common data-entry mistake: a sheet duplicated and the cited title not updated). Kept out of
     * the paper's score; surfaced separately with a best-guess of which paper they actually belong to.
     */
    public record DuplicateCitationGroup(
            String suggestedActualTitle,
            int suggestionOverlap,
            double totalFileScore,
            List<CitationDetail> citations) {
    }

    public record PublicationRow(
            String title,
            String roleKey,
            ScorePair publication,
            ScorePair citation,
            String platformCategory,
            Integer platformAuthorCount,
            String platformForumName,
            Integer platformYear,
            String fileCategory,
            Integer fileAuthorCount,
            List<CitationDetail> citationBreakdown,
            List<DuplicateCitationGroup> duplicateCitationGroups) {

        public boolean hasDetail() {
            return platformCategory != null || platformAuthorCount != null
                    || platformForumName != null || platformYear != null
                    || (citationBreakdown != null && !citationBreakdown.isEmpty())
                    || (duplicateCitationGroups != null && !duplicateCitationGroups.isEmpty());
        }

        /** True when the file's category differs from the platform's (a common cause of score diff). */
        public boolean categoryDiffers() {
            return platformCategory != null && fileCategory != null
                    && !platformCategory.equalsIgnoreCase(fileCategory);
        }

        /** True when the file's author count differs from the platform's. */
        public boolean authorCountDiffers() {
            return platformAuthorCount != null && fileAuthorCount != null
                    && !platformAuthorCount.equals(fileAuthorCount);
        }

        public long citationsOnlyInFile() { return countCitations(Status.ONLY_IN_FILE); }
        public long citationsOnlyInPlatform() { return countCitations(Status.ONLY_IN_PLATFORM); }
        public long citationsDiffering() { return countCitations(Status.DIFFERS); }

        private long countCitations(Status status) {
            if (citationBreakdown == null) return 0;
            return citationBreakdown.stream().filter(c -> c.status() == status).count();
        }

        public boolean rowMatches() {
            boolean pubOk = publication == null || publication.status() == Status.MATCH;
            boolean citOk = citation == null || citation.status() == Status.MATCH;
            return pubOk && citOk;
        }
    }
}
