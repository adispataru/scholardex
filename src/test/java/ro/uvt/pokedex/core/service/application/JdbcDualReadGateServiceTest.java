package ro.uvt.pokedex.core.service.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.uvt.pokedex.core.controller.dto.ScopusAffiliationPageResponse;
import ro.uvt.pokedex.core.controller.dto.ScopusAuthorPageResponse;
import ro.uvt.pokedex.core.controller.dto.ScopusForumPageResponse;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.reporting.wos.WosScoringView;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.application.model.AdminScopusCitationsViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminScopusPublicationSearchViewModel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcDualReadGateServiceTest {

    @Test
    void runFullGateFailsOnParityMismatch() {
        var fixture = fixture();
        when(fixture.mongoReportingLookup.getTopRankings("COMPUTER SCIENCE - SCIE", 2025)).thenReturn(1);
        when(fixture.postgresReportingLookup.getTopRankings("COMPUTER SCIENCE - SCIE", 2025)).thenReturn(2);

        DualReadGateService.DualReadGateRunSummary run = fixture.service.runFullGate();

        assertEquals("FAILED", run.status());
        var topScenario = run.scenarios().stream()
                .filter(scenario -> scenario.scenarioId().equals("wos.top-rankings.count"))
                .findFirst()
                .orElseThrow();
        assertFalse(topScenario.parityPassed());
        assertTrue(topScenario.performancePassed());
    }

    @Test
    void runFullGateFailsOnPerformanceThreshold() {
        var fixture = fixture();
        fixture.properties.setP95RatioThreshold(1.2d);

        doAnswer(invocation -> {
            Thread.sleep(15L);
            return fixture.postgresRanking;
        }).when(fixture.postgresReportingLookup).getRankingsByIssn("1234-5678");

        DualReadGateService.DualReadGateRunSummary run = fixture.service.runFullGate();

        assertEquals("FAILED", run.status());
        var issnScenario = run.scenarios().stream()
                .filter(scenario -> scenario.scenarioId().equals("wos.issn-ranking.lookup"))
                .findFirst()
                .orElseThrow();
        assertTrue(issnScenario.parityPassed());
        assertFalse(issnScenario.performancePassed());
    }

    @Test
    void percentileAndRatioHelpersAreDeterministic() {
        assertEquals(10.0d, JdbcDualReadGateService.p95(List.of(1d, 2d, 3d, 10d, 9d, 8d, 7d, 6d, 5d, 4d)));
        assertEquals(2.0d, JdbcDualReadGateService.ratio(20d, 10d));
        assertEquals(Double.POSITIVE_INFINITY, JdbcDualReadGateService.ratio(20d, 0d));
        assertEquals(1.0d, JdbcDualReadGateService.ratio(0d, 0d));
    }

    private Fixture fixture() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DualReadGateProperties properties = new DualReadGateProperties();
        properties.setSampleSize(2);
        properties.setP95RatioThreshold(50d);

        MongoTemplate mongoTemplate = mock(MongoTemplate.class);

        ProjectionBackedReportingLookupFacade mongoReportingLookup = mock(ProjectionBackedReportingLookupFacade.class);
        PostgresReportingLookupFacade postgresReportingLookup = mock(PostgresReportingLookupFacade.class);

        MongoScopusAuthorReadPort mongoAuthor = mock(MongoScopusAuthorReadPort.class);
        PostgresScopusAuthorReadPort postgresAuthor = mock(PostgresScopusAuthorReadPort.class);

        MongoScopusForumReadPort mongoForum = mock(MongoScopusForumReadPort.class);
        PostgresScopusForumReadPort postgresForum = mock(PostgresScopusForumReadPort.class);

        MongoScopusAffiliationReadPort mongoAffiliation = mock(MongoScopusAffiliationReadPort.class);
        PostgresScopusAffiliationReadPort postgresAffiliation = mock(PostgresScopusAffiliationReadPort.class);

        MongoAdminScopusReadPort mongoAdmin = mock(MongoAdminScopusReadPort.class);
        PostgresAdminScopusReadPort postgresAdmin = mock(PostgresAdminScopusReadPort.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<PostgresReportingLookupFacade> reportingProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PostgresScopusAuthorReadPort> authorProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PostgresScopusForumReadPort> forumProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PostgresScopusAffiliationReadPort> affiliationProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PostgresAdminScopusReadPort> adminProvider = mock(ObjectProvider.class);

        when(reportingProvider.getIfAvailable()).thenReturn(postgresReportingLookup);
        when(authorProvider.getIfAvailable()).thenReturn(postgresAuthor);
        when(forumProvider.getIfAvailable()).thenReturn(postgresForum);
        when(affiliationProvider.getIfAvailable()).thenReturn(postgresAffiliation);
        when(adminProvider.getIfAvailable()).thenReturn(postgresAdmin);

        WosRankingView rankingView = new WosRankingView();
        rankingView.setIssn("1234-5678");
        when(mongoTemplate.findOne(any(Query.class), eq(WosRankingView.class))).thenReturn(rankingView);

        WosScoringView scoringView = new WosScoringView();
        scoringView.setCategoryNameCanonical("COMPUTER SCIENCE");
        scoringView.setEditionNormalized(EditionNormalized.SCIE);
        scoringView.setMetricType(MetricType.AIS);
        scoringView.setYear(2025);
        scoringView.setQuarter("Q1");
        when(mongoTemplate.findOne(any(Query.class), eq(WosScoringView.class))).thenReturn(scoringView);

        ScholardexAuthorView authorView = new ScholardexAuthorView();
        authorView.setAffiliationIds(List.of("af-1"));
        when(mongoTemplate.findOne(any(Query.class), eq(ScholardexAuthorView.class))).thenReturn(authorView);

        ScholardexForumView forumView = new ScholardexForumView();
        forumView.setPublicationName("Forum One");
        when(mongoTemplate.findOne(any(Query.class), eq(ScholardexForumView.class))).thenReturn(forumView);

        ScholardexAffiliationView affiliationView = new ScholardexAffiliationView();
        affiliationView.setName("Affiliation One");
        when(mongoTemplate.findOne(any(Query.class), eq(ScholardexAffiliationView.class))).thenReturn(affiliationView);

        ScholardexPublicationView publicationView = new ScholardexPublicationView();
        publicationView.setId("pub-1");
        publicationView.setTitle("Parity publication");
        when(mongoTemplate.findOne(any(Query.class), eq(ScholardexPublicationView.class))).thenReturn(publicationView);

        WoSRanking mongoRanking = new WoSRanking();
        mongoRanking.setId("j-1");
        WoSRanking postgresRanking = new WoSRanking();
        postgresRanking.setId("j-1");

        when(mongoReportingLookup.getRankingsByIssn("1234-5678")).thenReturn(List.of(mongoRanking));
        when(postgresReportingLookup.getRankingsByIssn("1234-5678")).thenReturn(List.of(postgresRanking));
        when(mongoReportingLookup.getTopRankings("COMPUTER SCIENCE - SCIE", 2025)).thenReturn(1);
        when(postgresReportingLookup.getTopRankings("COMPUTER SCIENCE - SCIE", 2025)).thenReturn(1);

        ScopusAuthorPageResponse authorResponse = new ScopusAuthorPageResponse(List.of(), 0, 25, 0, 0);
        when(mongoAuthor.search("af-1", 0, 25, "name", "asc", null)).thenReturn(authorResponse);
        when(postgresAuthor.search("af-1", 0, 25, "name", "asc", null)).thenReturn(authorResponse);

        ScopusForumPageResponse forumResponse = new ScopusForumPageResponse(List.of(), 0, 25, 0, 0);
        when(mongoForum.search(0, 25, "publicationName", "asc", "Forum")).thenReturn(forumResponse);
        when(postgresForum.search(0, 25, "publicationName", "asc", "Forum")).thenReturn(forumResponse);

        ScopusAffiliationPageResponse affiliationResponse = new ScopusAffiliationPageResponse(List.of(), 0, 25, 0, 0);
        when(mongoAffiliation.search(0, 25, "name", "asc", "Affiliation")).thenReturn(affiliationResponse);
        when(postgresAffiliation.search(0, 25, "name", "asc", "Affiliation")).thenReturn(affiliationResponse);

        Publication publication = new Publication();
        publication.setId("pub-1");
        AdminScopusPublicationSearchViewModel searchView = new AdminScopusPublicationSearchViewModel(List.of(publication), Map.of());
        when(mongoAdmin.buildPublicationSearchView("Parity")).thenReturn(searchView);
        when(postgresAdmin.buildPublicationSearchView("Parity")).thenReturn(searchView);

        AdminScopusCitationsViewModel citationsView = new AdminScopusCitationsViewModel(publication, null, List.of(), Map.of(), Map.of());
        when(mongoAdmin.buildPublicationCitationsView("pub-1")).thenReturn(Optional.of(citationsView));
        when(postgresAdmin.buildPublicationCitationsView("pub-1")).thenReturn(Optional.of(citationsView));

        JdbcDualReadGateService service = new JdbcDualReadGateService(
                jdbcTemplate,
                new ObjectMapper(),
                properties,
                mongoTemplate,
                mongoReportingLookup,
                reportingProvider,
                mongoAuthor,
                authorProvider,
                mongoForum,
                forumProvider,
                mongoAffiliation,
                affiliationProvider,
                mongoAdmin,
                adminProvider
        );

        return new Fixture(service, properties, mongoReportingLookup, postgresReportingLookup, List.of(postgresRanking));
    }

    private record Fixture(
            JdbcDualReadGateService service,
            DualReadGateProperties properties,
            ProjectionBackedReportingLookupFacade mongoReportingLookup,
            PostgresReportingLookupFacade postgresReportingLookup,
            List<WoSRanking> postgresRanking
    ) {
    }
}
