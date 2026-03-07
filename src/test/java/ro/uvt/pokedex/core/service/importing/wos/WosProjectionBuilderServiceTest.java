package ro.uvt.pokedex.core.service.importing.wos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.reporting.wos.WosScoringView;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosRankingViewRepository;
import ro.uvt.pokedex.core.repository.reporting.WosScoringViewRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosProjectionBuilderServiceTest {

    @Mock private WosJournalIdentityRepository identityRepository;
    @Mock private WosMetricFactRepository metricFactRepository;
    @Mock private WosCategoryFactRepository categoryFactRepository;
    @Mock private WosRankingViewRepository rankingViewRepository;
    @Mock private WosScoringViewRepository scoringViewRepository;

    @Test
    void rebuildCreatesDeterministicProjectionRowsWithSharedBuildVersion() {
        WosProjectionBuilderService service = service();
        when(identityRepository.findAll()).thenReturn(List.of(identity("jid-1")));
        when(metricFactRepository.findAll()).thenReturn(List.of(
                metric("jid-1", 2022, MetricType.AIS, 1.2),
                metric("jid-1", 2023, MetricType.RIS, 0.8)
        ));
        when(categoryFactRepository.findAll()).thenReturn(List.of(
                category("jid-1", 2022, MetricType.AIS, EditionNormalized.SCIE, "ACOUSTICS", "Q1", 7, 1),
                category("jid-1", 2023, MetricType.RIS, EditionNormalized.SSCI, "ACOUSTICS", "Q2", 3)
        ));

        ImportProcessingResult result = service.rebuildWosProjections();

        ArgumentCaptor<List<WosRankingView>> rankingCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<WosScoringView>> scoringCaptor = ArgumentCaptor.forClass(List.class);
        verify(rankingViewRepository).saveAll(rankingCaptor.capture());
        verify(scoringViewRepository).saveAll(scoringCaptor.capture());

        WosRankingView rankingView = rankingCaptor.getValue().getFirst();
        WosScoringView scoringView = scoringCaptor.getValue().getFirst();
        assertEquals("jid-1", rankingView.getId());
        assertEquals(2022, rankingView.getLatestAisYear());
        assertEquals(2023, rankingView.getLatestRisYear());
        assertEquals(EditionNormalized.SCIE, rankingView.getLatestEditionNormalized());
        assertNotNull(rankingView.getBuildVersion());
        assertEquals(rankingView.getBuildVersion(), scoringView.getBuildVersion());
        assertEquals("ACOUSTICS", scoringView.getCategoryNameCanonical());
        assertEquals("Q1", scoringView.getQuarter());
        assertEquals(7, scoringView.getQuartileRank());
        assertEquals(1, scoringView.getRank());
        assertEquals(1.2, scoringView.getValue());
        assertEquals(3, result.getImportedCount());
    }

    @Test
    void rebuildLeavesScoreNullWhenCategoryHasNoScore() {
        WosProjectionBuilderService service = service();
        when(identityRepository.findAll()).thenReturn(List.of(identity("jid-2")));
        when(metricFactRepository.findAll()).thenReturn(List.of());
        when(categoryFactRepository.findAll()).thenReturn(List.of(
                category("jid-2", 2024, MetricType.RIS, EditionNormalized.ESCI, "EDUCATION", "Q2", 3)
        ));

        service.rebuildWosProjections();

        ArgumentCaptor<List<WosScoringView>> scoringCaptor = ArgumentCaptor.forClass(List.class);
        verify(scoringViewRepository).saveAll(scoringCaptor.capture());
        WosScoringView scoringView = scoringCaptor.getValue().getFirst();
        assertEquals(null, scoringView.getValue());
        assertEquals(EditionNormalized.ESCI, scoringView.getEditionNormalized());
    }

    @Test
    void rebuildUsesUnknownEditionWhenNoCategoryFacts() {
        WosProjectionBuilderService service = service();
        when(identityRepository.findAll()).thenReturn(List.of(identity("jid-3")));
        when(metricFactRepository.findAll()).thenReturn(List.of(metric("jid-3", 2020, MetricType.IF, 4.1)));
        when(categoryFactRepository.findAll()).thenReturn(List.of());
        when(rankingViewRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.rebuildWosProjections();

        ArgumentCaptor<List<WosRankingView>> rankingCaptor = ArgumentCaptor.forClass(List.class);
        verify(rankingViewRepository).saveAll(rankingCaptor.capture());
        WosRankingView view = rankingCaptor.getValue().getFirst();
        assertEquals(EditionNormalized.UNKNOWN, view.getLatestEditionNormalized());
    }

    @Test
    void rebuildNormalizesRankingSearchFieldsDeterministically() {
        WosProjectionBuilderService service = service();
        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId("jid-4");
        identity.setTitle("  Journal:  Of  TESTS  ");
        identity.setPrimaryIssn(" 1234-5678 ");
        identity.setEIssn(" 8765 4321 ");
        identity.setAliasIssns(Arrays.asList("1111-2222", " 11112222 ", null, ""));
        when(identityRepository.findAll()).thenReturn(List.of(identity));
        when(metricFactRepository.findAll()).thenReturn(List.of());
        when(categoryFactRepository.findAll()).thenReturn(List.of());

        service.rebuildWosProjections();

        ArgumentCaptor<List<WosRankingView>> rankingCaptor = ArgumentCaptor.forClass(List.class);
        verify(rankingViewRepository).saveAll(rankingCaptor.capture());
        WosRankingView view = rankingCaptor.getValue().getFirst();
        assertEquals("journal: of tests", view.getNameNorm());
        assertEquals("12345678", view.getIssnNorm());
    }

    private WosProjectionBuilderService service() {
        return new WosProjectionBuilderService(
                identityRepository,
                metricFactRepository,
                categoryFactRepository,
                rankingViewRepository,
                scoringViewRepository
        );
    }

    private WosJournalIdentity identity(String id) {
        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId(id);
        identity.setTitle("Journal " + id);
        identity.setPrimaryIssn("12345678");
        identity.setEIssn("87654321");
        identity.setAliasIssns(List.of("11112222"));
        return identity;
    }

    private WosMetricFact metric(String journalId, int year, MetricType metricType, Double value) {
        WosMetricFact fact = new WosMetricFact();
        fact.setJournalId(journalId);
        fact.setYear(year);
        fact.setMetricType(metricType);
        fact.setValue(value);
        fact.setSourceVersion("v" + year);
        fact.setSourceRowItem("1");
        return fact;
    }

    private WosCategoryFact category(
            String journalId,
            int year,
            MetricType metricType,
            EditionNormalized edition,
            String categoryName,
            String quarter,
            Integer rank
    ) {
        return category(journalId, year, metricType, edition, categoryName, quarter, null, rank);
    }

    private WosCategoryFact category(
            String journalId,
            int year,
            MetricType metricType,
            EditionNormalized edition,
            String categoryName,
            String quarter,
            Integer quartileRank,
            Integer rank
    ) {
        WosCategoryFact fact = new WosCategoryFact();
        fact.setJournalId(journalId);
        fact.setYear(year);
        fact.setMetricType(metricType);
        fact.setEditionNormalized(edition);
        fact.setCategoryNameCanonical(categoryName);
        fact.setQuarter(quarter);
        fact.setQuartileRank(quartileRank);
        fact.setRank(rank);
        return fact;
    }
}
