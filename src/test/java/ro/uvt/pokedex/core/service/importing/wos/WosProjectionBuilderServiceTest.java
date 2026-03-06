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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private WosCategoryFactRepository categoryFactRepository;
    @Mock
    private WosRankingViewRepository rankingViewRepository;
    @Mock
    private WosScoringViewRepository scoringViewRepository;

    @Test
    void rebuildCreatesDeterministicProjectionRowsWithSharedBuildVersion() {
        WosProjectionBuilderService service = service();
        when(identityRepository.findAll()).thenReturn(List.of(identity("jid-1")));
        when(metricFactRepository.findAll()).thenReturn(List.of(
                metric("jid-1", 2022, MetricType.AIS, EditionNormalized.SCIE, 1.2),
                metric("jid-1", 2023, MetricType.RIS, EditionNormalized.SSCI, 0.8)
        ));
        when(categoryFactRepository.findAll()).thenReturn(List.of(
                category("jid-1", 2022, MetricType.AIS, EditionNormalized.SCIE, "ACOUSTICS", "Q1", 1)
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
        assertNotNull(rankingView.getBuildVersion());
        assertNotNull(rankingView.getBuildAt());
        assertEquals(rankingView.getBuildVersion(), scoringView.getBuildVersion());
        assertEquals(rankingView.getBuildAt(), scoringView.getBuildAt());
        assertEquals(1.2, scoringView.getValue());
        assertEquals(2, result.getProcessedCount());
        assertEquals(2, result.getImportedCount());
    }

    @Test
    void rebuildLeavesScoringValueNullWhenMetricFactMissing() {
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
        assertNull(scoringView.getValue());
        assertEquals(EditionNormalized.ESCI, scoringView.getEditionNormalized());
    }

    @Test
    void rebuildUsesUnknownEditionWhenNoAisOrRisMetricValue() {
        WosProjectionBuilderService service = service();
        when(identityRepository.findAll()).thenReturn(List.of(identity("jid-3")));
        when(metricFactRepository.findAll()).thenReturn(List.of(
                metric("jid-3", 2020, MetricType.IF, EditionNormalized.SCIE, 4.1)
        ));
        when(categoryFactRepository.findAll()).thenReturn(List.of());
        when(rankingViewRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(scoringViewRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.rebuildWosProjections();

        ArgumentCaptor<List<WosRankingView>> rankingCaptor = ArgumentCaptor.forClass(List.class);
        verify(rankingViewRepository).saveAll(rankingCaptor.capture());
        WosRankingView view = rankingCaptor.getValue().getFirst();
        assertEquals(EditionNormalized.UNKNOWN, view.getLatestEditionNormalized());
        assertTrue(view.getLatestAisYear() == null && view.getLatestRisYear() == null);
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

    private WosMetricFact metric(String journalId, int year, MetricType metricType, EditionNormalized edition, Double value) {
        WosMetricFact fact = new WosMetricFact();
        fact.setJournalId(journalId);
        fact.setYear(year);
        fact.setMetricType(metricType);
        fact.setEditionNormalized(edition);
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
        WosCategoryFact fact = new WosCategoryFact();
        fact.setJournalId(journalId);
        fact.setYear(year);
        fact.setMetricType(metricType);
        fact.setEditionNormalized(edition);
        fact.setCategoryNameCanonical(categoryName);
        fact.setQuarter(quarter);
        fact.setRank(rank);
        return fact;
    }
}
