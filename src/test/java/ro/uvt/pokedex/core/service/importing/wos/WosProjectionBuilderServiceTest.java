package ro.uvt.pokedex.core.service.importing.wos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.reporting.wos.WosScoringView;
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

    @Mock
    private WosJournalIdentityRepository identityRepository;
    @Mock
    private WosMetricFactRepository metricFactRepository;
    @Mock
    private WosRankingViewRepository rankingViewRepository;
    @Mock
    private WosScoringViewRepository scoringViewRepository;

    @Test
    void rebuildCreatesDeterministicProjectionRowsWithSharedBuildVersion() {
        WosProjectionBuilderService service = service();
        when(identityRepository.findAll()).thenReturn(List.of(identity("jid-1")));
        when(metricFactRepository.findAll()).thenReturn(List.of(
                metric("jid-1", 2022, MetricType.AIS, EditionNormalized.SCIE, "ACOUSTICS", "Q1", 1, 1.2),
                metric("jid-1", 2023, MetricType.RIS, EditionNormalized.SSCI, "ACOUSTICS", "Q2", 3, 0.8)
        ));

        ImportProcessingResult result = service.rebuildWosProjections();

        ArgumentCaptor<List<WosRankingView>> rankingCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<WosScoringView>> scoringCaptor = ArgumentCaptor.forClass(List.class);
        verify(rankingViewRepository).deleteAll();
        verify(scoringViewRepository).deleteAll();
        verify(rankingViewRepository).saveAll(rankingCaptor.capture());
        verify(scoringViewRepository).saveAll(scoringCaptor.capture());

        WosRankingView rankingView = rankingCaptor.getValue().getFirst();
        WosScoringView scoringView = scoringCaptor.getValue().getFirst();
        assertEquals("jid-1", rankingView.getId());
        assertEquals(2022, rankingView.getLatestAisYear());
        assertEquals(2023, rankingView.getLatestRisYear());
        assertEquals(EditionNormalized.SCIE, rankingView.getLatestEditionNormalized());
        assertEquals("journal jid-1", rankingView.getNameNorm());
        assertEquals("12345678", rankingView.getIssnNorm());
        assertEquals("87654321", rankingView.getEIssnNorm());
        assertEquals(List.of("11112222"), rankingView.getAlternativeIssnsNorm());
        assertNotNull(rankingView.getBuildVersion());
        assertNotNull(rankingView.getBuildAt());
        assertEquals(rankingView.getBuildVersion(), scoringView.getBuildVersion());
        assertEquals(rankingView.getBuildAt(), scoringView.getBuildAt());
        assertEquals("ACOUSTICS", scoringView.getCategoryNameCanonical());
        assertEquals("Q1", scoringView.getQuarter());
        assertEquals(1, scoringView.getRank());
        assertEquals(1.2, scoringView.getValue());
        assertEquals(3, result.getProcessedCount());
        assertEquals(3, result.getImportedCount());
    }

    @Test
    void rebuildCreatesNoScoringRowsWhenMetricFactMissing() {
        WosProjectionBuilderService service = service();
        when(identityRepository.findAll()).thenReturn(List.of(identity("jid-2")));
        when(metricFactRepository.findAll()).thenReturn(List.of());

        service.rebuildWosProjections();

        ArgumentCaptor<List<WosScoringView>> scoringCaptor = ArgumentCaptor.forClass(List.class);
        verify(scoringViewRepository).saveAll(scoringCaptor.capture());
        assertTrue(scoringCaptor.getValue().isEmpty());
    }

    @Test
    void rebuildUsesUnknownEditionWhenNoAisOrRisMetricValue() {
        WosProjectionBuilderService service = service();
        when(identityRepository.findAll()).thenReturn(List.of(identity("jid-3")));
        when(metricFactRepository.findAll()).thenReturn(List.of(
                metric("jid-3", 2020, MetricType.IF, EditionNormalized.SCIE, "MEDICINE", "Q1", 1, 4.1)
        ));
        when(rankingViewRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(scoringViewRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.rebuildWosProjections();

        ArgumentCaptor<List<WosRankingView>> rankingCaptor = ArgumentCaptor.forClass(List.class);
        verify(rankingViewRepository).saveAll(rankingCaptor.capture());
        WosRankingView view = rankingCaptor.getValue().getFirst();
        assertEquals(EditionNormalized.UNKNOWN, view.getLatestEditionNormalized());
        assertTrue(view.getLatestAisYear() == null && view.getLatestRisYear() == null);
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

        service.rebuildWosProjections();

        ArgumentCaptor<List<WosRankingView>> rankingCaptor = ArgumentCaptor.forClass(List.class);
        verify(rankingViewRepository).saveAll(rankingCaptor.capture());
        WosRankingView view = rankingCaptor.getValue().getFirst();
        assertEquals("journal: of tests", view.getNameNorm());
        assertEquals("12345678", view.getIssnNorm());
        assertEquals("87654321", view.getEIssnNorm());
        assertEquals(List.of("11112222"), view.getAlternativeIssnsNorm());
    }

    private WosProjectionBuilderService service() {
        return new WosProjectionBuilderService(
                identityRepository,
                metricFactRepository,
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

    private WosMetricFact metric(
            String journalId,
            int year,
            MetricType metricType,
            EditionNormalized edition,
            String categoryNameCanonical,
            String quarter,
            Integer rank,
            Double value
    ) {
        WosMetricFact fact = new WosMetricFact();
        fact.setJournalId(journalId);
        fact.setYear(year);
        fact.setMetricType(metricType);
        fact.setEditionNormalized(edition);
        fact.setCategoryNameCanonical(categoryNameCanonical);
        fact.setQuarter(quarter);
        fact.setRank(rank);
        fact.setValue(value);
        fact.setSourceVersion("v" + year);
        fact.setSourceRowItem("1");
        return fact;
    }
}
