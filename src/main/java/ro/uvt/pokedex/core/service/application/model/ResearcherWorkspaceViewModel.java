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
            List<Integer> citesPerYear,
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
