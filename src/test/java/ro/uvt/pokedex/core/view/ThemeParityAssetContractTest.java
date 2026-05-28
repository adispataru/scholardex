package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeParityAssetContractTest {

    @Test
    void sharedThemeAssetsExposeChartTokensAndThemeChangeHooks() throws Exception {
        String foundation = Files.readString(Path.of("frontend/src/styles/foundation.css"));
        String themeShell = Files.readString(Path.of("frontend/src/modules/shared/themeShell.js"));
        String chartTheme = Files.readString(Path.of("frontend/src/modules/shared/chartTheme.js"));
        String universityCharts = Files.readString(Path.of("frontend/src/modules/public/universityDetailCharts.js"));
        String forumCharts = Files.readString(Path.of("frontend/src/modules/public/forumDetailCharts.js"));

        assertTrue(foundation.contains("--app-chart-series-1"));
        assertTrue(foundation.contains("--app-chart-grid"));
        assertTrue(foundation.contains("--app-ranking-q3"));
        assertTrue(foundation.contains("--app-ranking-q3-fill"));

        assertTrue(themeShell.contains("app:themechange"));
        assertTrue(chartTheme.contains("venueBuckets"));
        assertTrue(universityCharts.contains("window.addEventListener('app:themechange'"));
        assertTrue(forumCharts.contains("window.addEventListener('app:themechange'"));
    }

    @Test
    void representativeChartTemplatesAndDashboardsUseSharedThemeApi() throws Exception {
        String appBundle = Files.readString(Path.of("frontend/src/app.js"));
        String coreDetail = Files.readString(Path.of("src/main/resources/templates/core/ranking-detail.html"))
                + Files.readString(Path.of("src/main/resources/templates/fragments.html"));
        String groupWorkspace = Files.readString(Path.of("src/main/resources/templates/admin/group-workspace.html"));
        String publicationsDashboard = Files.readString(Path.of("src/main/resources/static/js/indicator-publications-dashboard.js"));
        String citationsDashboard = Files.readString(Path.of("src/main/resources/static/js/indicator-citations-dashboard.js"));
        String activitiesDashboard = Files.readString(Path.of("src/main/resources/static/js/indicator-activities-dashboard.js"));
        String forumStyles = Files.readString(Path.of("frontend/src/styles/public-forums.css"));

        assertTrue(appBundle.contains("window.appChartTheme"));
        assertTrue(coreDetail.contains("window.appChartTheme"));
        assertTrue(groupWorkspace.contains("window.appChartTheme"));
        assertTrue(publicationsDashboard.contains("window.appChartTheme"));
        assertTrue(citationsDashboard.contains("window.appChartTheme"));
        assertTrue(activitiesDashboard.contains("window.appChartTheme"));
        assertTrue(forumStyles.contains("var(--app-ranking-q3)"));
        assertTrue(forumStyles.contains("var(--app-ranking-empty)"));
    }
}
