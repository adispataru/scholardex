package ro.uvt.pokedex.core.service.application.model;

import java.util.List;

public record ResearcherWorkspaceViewModel(
        String researcherName,
        boolean hasResearcherProfile,
        int profileCompletenessPercent,

        int publicationCount,
        int totalCitations,
        int hIndex,
        int activityInstanceCount,
        int availableReportCount,

        int unreadNotificationCount,

        List<RecentActivityItem> recentActivities,

        int pendingScopusTaskCount,

        List<TabDef> tabs,

        List<String> overviewCardOrder,

        WorkspaceState workspaceState,

        OverviewCharts overviewCharts
) {
    public record OverviewCharts(
            List<String> years,
            List<Integer> pubsPerYear,
            // Citations bucketed by the CITING paper's year (Google-Scholar semantics), on their own
            // continuous axis. Two series: including and excluding the researcher's self-citations.
            List<String> citeYears,
            List<Integer> citesInclSelf,
            List<Integer> citesExclSelf,
            List<String> activityLabels,
            List<Integer> activityCounts
    ) {}

    public record RecentActivityItem(
            String instanceId,
            String activityName,
            String date
    ) {}

    public enum WorkspaceState {
        NEW_USER,
        INCOMPLETE_PROFILE,
        REPORTING_SEASON,
        ACTIVE
    }
}
