package ro.uvt.pokedex.core.service.reporting.transfer;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportInstanceSnapshot;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingBlock;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingKind;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingRole;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding;
import ro.uvt.pokedex.core.service.application.UserIndicatorResultService;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.transfer.projection.ActivityBlockProjector;
import ro.uvt.pokedex.core.service.reporting.transfer.projection.CitationRowProjector;
import ro.uvt.pokedex.core.service.reporting.transfer.projection.PublicationRowProjector;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportInstanceSnapshotBuilderTest {

    @Test
    void buildUsesPersistedRunIndicatorResultsInsteadOfLivePublicationProjection() {
        PublicationRowProjector publicationProjector = mock(PublicationRowProjector.class);
        CitationRowProjector citationProjector = mock(CitationRowProjector.class);
        ActivityBlockProjector activityBlockProjector = mock(ActivityBlockProjector.class);
        ReportImportRegistry registry = mock(ReportImportRegistry.class);
        UserIndicatorResultService userIndicatorResultService = mock(UserIndicatorResultService.class);
        ReportInstanceSnapshotBuilder builder = new ReportInstanceSnapshotBuilder(
                publicationProjector,
                citationProjector,
                activityBlockProjector,
                registry,
                userIndicatorResultService,
                new RunIndicatorSnapshotProjector(mock(ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService.class)));

        IndividualReport report = report(publicationIndicator("ind-pub"));
        UserIndividualReportRun run = run("run-1", "result-pub");
        Score score = new Score();
        score.setCoreRankingEquivalent("A");
        score.setAuthorScore(7.5);
        when(userIndicatorResultService.getById("result-pub"))
                .thenReturn(Optional.of(indicatorResult("ind-pub", Map.of(
                        "outputMode", "publications",
                        "scores", Map.of("Paper A", score),
                        "publications", List.of(Map.of(
                                "id", "pub-1",
                                "title", "Paper A",
                                "authors", List.of("Ada", "Bob"),
                                "forum", "Journal A",
                                "volume", "12(3)",
                                "coverDate", "2024-05-01",
                                "authorCount", 2
                        ))
                ))));
        when(registry.find("informatica-2016")).thenReturn(Optional.empty());

        ReportInstanceSnapshot snapshot = builder.build(run, report, "u@uvt.ro");

        assertEquals("run-1", snapshot.getSourceRunId());
        assertEquals(1, snapshot.getItems().size());
        PublicationSnapshotItem item = assertInstanceOf(PublicationSnapshotItem.class, snapshot.getItems().getFirst());
        assertEquals("journal-publications", item.getRoleKey());
        assertEquals("Paper A", item.getTitle());
        assertEquals("A", item.getForumCategoryLetter());
        assertEquals(7.5, item.getScore());
        verify(publicationProjector, never()).project(any(), any(), any());
    }

    @Test
    void buildReconstructsCitationTileRowsFromPersistedRunResult() {
        PublicationRowProjector publicationProjector = mock(PublicationRowProjector.class);
        CitationRowProjector citationProjector = mock(CitationRowProjector.class);
        ActivityBlockProjector activityBlockProjector = mock(ActivityBlockProjector.class);
        ReportImportRegistry registry = mock(ReportImportRegistry.class);
        UserIndicatorResultService userIndicatorResultService = mock(UserIndicatorResultService.class);
        ReportInstanceSnapshotBuilder builder = new ReportInstanceSnapshotBuilder(
                publicationProjector,
                citationProjector,
                activityBlockProjector,
                registry,
                userIndicatorResultService,
                new RunIndicatorSnapshotProjector(mock(ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService.class)));

        IndividualReport report = report(citationIndicator("ind-cit"), CitationRowProjector.ROLE_KEY);
        UserIndividualReportRun run = run("run-1", "result-cit");
        Score citingScore = new Score();
        citingScore.setCoreRankingEquivalent("B");
        citingScore.setAuthorScore(3.5);
        citingScore.setScore(7.0);
        citingScore.setScoringInfo(Map.of("workshopAdjusted", true));
        when(userIndicatorResultService.getById("result-cit"))
                .thenReturn(Optional.of(indicatorResult("ind-cit", Map.of(
                        "outputMode", "citations",
                        "scores", Map.of("Cited Paper", Map.of("Citing Paper", citingScore)),
                        "publications", List.of(Map.of(
                                "id", "cited-1",
                                "title", "Cited Paper",
                                "forum", "Cited Journal",
                                "coverDate", "2022-01-01",
                                "authorCount", 4
                        )),
                        "citationMap", Map.of("Citing Paper", Map.of(
                                "id", "citing-1",
                                "title", "Citing Paper",
                                "authors", List.of("Carol", "Dan"),
                                "forum", "Citing Conf",
                                "volume", "9",
                                "coverDate", "2024-02-03"
                        ))
                ))));
        when(registry.find("informatica-2016")).thenReturn(Optional.empty());

        ReportInstanceSnapshot snapshot = builder.build(run, report, "u@uvt.ro");

        assertEquals(1, snapshot.getItems().size());
        CitationSnapshotItem tile = assertInstanceOf(CitationSnapshotItem.class, snapshot.getItems().getFirst());
        assertEquals("citations-per-publication", tile.getRoleKey());
        assertEquals("Cited Paper", tile.getPublicationTitle());
        assertEquals("Cited Journal", tile.getPublicationForumName());
        assertEquals(2022, tile.getPublicationYear());
        assertEquals(4, tile.getPublicationAuthorCount());
        assertEquals(3.5, tile.getScore());
        assertEquals(1, tile.getCitingPublications().size());
        CitationSnapshotItem.CitingPublication citing = tile.getCitingPublications().getFirst();
        assertEquals("Citing Paper", citing.getTitle());
        assertEquals("Carol, Dan", citing.getAuthors());
        assertEquals("Citing Conf", citing.getForumName());
        assertEquals("9", citing.getVolumeInfo());
        assertEquals(2024, citing.getYear());
        assertEquals("DA", citing.getIsWorkshopDaNu());
        assertEquals("B", citing.getForumCategoryLetter());
        assertEquals(7.0, citing.getScore());
        verify(citationProjector, never()).project(any(), any(), any());
    }

    @Test
    void buildSkipsExplicitlyExcludedIndicatorsEvenWhenBlockMapped() {
        PublicationRowProjector publicationProjector = mock(PublicationRowProjector.class);
        CitationRowProjector citationProjector = mock(CitationRowProjector.class);
        ActivityBlockProjector activityBlockProjector = mock(ActivityBlockProjector.class);
        ReportImportRegistry registry = mock(ReportImportRegistry.class);
        ReportTypeImportSupport support = mock(ReportTypeImportSupport.class);
        UserIndicatorResultService userIndicatorResultService = mock(UserIndicatorResultService.class);
        ReportInstanceSnapshotBuilder builder = new ReportInstanceSnapshotBuilder(
                publicationProjector,
                citationProjector,
                activityBlockProjector,
                registry,
                userIndicatorResultService,
                new RunIndicatorSnapshotProjector(mock(ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService.class)));

        Indicator indicator = publicationIndicator("ind-excluded");
        IndividualReport report = report(indicator, "__not_exported__");
        report.setBlockByIndicatorId(Map.of("ind-excluded", "Granturi"));
        UserIndividualReportRun run = run("run-1", "result-excluded");
        Score score = new Score();
        score.setAuthorScore(9.0);
        when(userIndicatorResultService.getById("result-excluded"))
                .thenReturn(Optional.of(indicatorResult("ind-excluded", Map.of(
                        "outputMode", "publications",
                        "scores", Map.of("Paper B", score),
                        "publications", List.of(Map.of("title", "Paper B"))
                ))));
        when(registry.find("informatica-2016")).thenReturn(Optional.of(support));
        when(support.binding()).thenReturn(bindingWithBlock("activities-perspectiva-d", "Granturi"));

        ReportInstanceSnapshot snapshot = builder.build(run, report, "u@uvt.ro");

        assertEquals(0, snapshot.getItems().size());
        verify(publicationProjector, never()).project(any(), any(), any());
        verify(activityBlockProjector, never()).projectAllBlocks(any(), any(), any(), any());
    }

    private static IndividualReport report(Indicator indicator) {
        return report(indicator, "journal-publications");
    }

    private static IndividualReport report(Indicator indicator, String roleKey) {
        IndividualReport report = new IndividualReport();
        report.setId("report-1");
        report.setReportTypeKey("informatica-2016");
        report.setIndicators(List.of(indicator));
        report.setIndicatorRolesByIndicatorId(Map.of(indicator.getId(), roleKey));
        return report;
    }

    private static Indicator publicationIndicator(String id) {
        Indicator indicator = new Indicator();
        indicator.setId(id);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "PUBLICATIONS");
        return indicator;
    }

    private static Indicator citationIndicator(String id) {
        Indicator indicator = new Indicator();
        indicator.setId(id);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "CITATIONS");
        return indicator;
    }

    private static UserIndividualReportRun run(String id, String resultId) {
        UserIndividualReportRun run = new UserIndividualReportRun();
        run.setId(id);
        run.setUserEmail("u@uvt.ro");
        run.setReportDefinitionId("report-1");
        run.setIndicatorResultIds(List.of(resultId));
        run.setCreatedAt(Instant.parse("2026-04-01T10:00:00Z"));
        run.setStatus(UserIndividualReportRun.Status.READY);
        return run;
    }

    private static IndicatorApplyResultDto indicatorResult(String indicatorId, Map<String, Object> rawGraph) {
        return new IndicatorApplyResultDto(
                "result-pub",
                indicatorId,
                "view",
                rawGraph,
                new IndicatorApplyResultDto.Summary(7.5, 1, List.of(), List.of()),
                IndicatorApplyResultDto.Source.PERSISTED,
                Instant.parse("2026-04-01T10:00:00Z"),
                Instant.parse("2026-04-01T10:00:00Z"),
                0
        );
    }

    private static TemplateBinding bindingWithBlock(String roleKey, String blockName) {
        BindingBlock block = new BindingBlock();
        block.setActivityName(blockName);
        BindingRole role = new BindingRole();
        role.setRoleKey(roleKey);
        role.setKind(BindingKind.STACKED_BLOCKS);
        role.setBlocks(List.of(block));
        TemplateBinding binding = new TemplateBinding();
        binding.setRoles(List.of(role));
        return binding;
    }
}
