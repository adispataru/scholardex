package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.reporting.wos.WosScoringView;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosIndexMaintenanceServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private IndexOperations metricOps;
    @Mock
    private IndexOperations rankingOps;
    @Mock
    private IndexOperations scoringOps;

    private WosIndexMaintenanceService service;

    @BeforeEach
    void setUp() {
        service = new WosIndexMaintenanceService(mongoTemplate);
        when(mongoTemplate.indexOps(WosMetricFact.class)).thenReturn(metricOps);
        when(mongoTemplate.indexOps(WosRankingView.class)).thenReturn(rankingOps);
        when(mongoTemplate.indexOps(WosScoringView.class)).thenReturn(scoringOps);
    }

    @Test
    void ensureWosIndexesCreatesAllMissingIndexes() {
        when(metricOps.getIndexInfo()).thenReturn(List.of());
        when(rankingOps.getIndexInfo()).thenReturn(List.of());
        when(scoringOps.getIndexInfo()).thenReturn(List.of());

        WosIndexMaintenanceService.WosIndexEnsureResult result = service.ensureWosIndexes();

        assertEquals(11, result.created().size());
        assertTrue(result.present().isEmpty());
        assertTrue(result.invalid().isEmpty());
        assertTrue(result.errors().isEmpty());
        verify(metricOps, org.mockito.Mockito.times(2)).ensureIndex(any());
        verify(rankingOps, org.mockito.Mockito.times(7)).ensureIndex(any());
        verify(scoringOps, org.mockito.Mockito.times(2)).ensureIndex(any());
    }

    @Test
    void ensureWosIndexesMarksExistingIndexesAsPresent() {
        when(metricOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_METRIC_UNIQ, true, "journalId", "year", "metricType", "categoryNameCanonical", "editionNormalized"),
                info(WosIndexMaintenanceService.IDX_METRIC_LOOKUP, false, "categoryNameCanonical", "year", "metricType", "editionNormalized", "journalId")
        ));
        when(rankingOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_RANKING_SORT_NAME, false, "name"),
                info(WosIndexMaintenanceService.IDX_RANKING_SORT_ISSN, false, "issn"),
                info(WosIndexMaintenanceService.IDX_RANKING_SORT_EISSN, false, "eIssn"),
                info(WosIndexMaintenanceService.IDX_RANKING_SEARCH_NAME_NORM, false, "nameNorm"),
                info(WosIndexMaintenanceService.IDX_RANKING_SEARCH_ISSN_NORM, false, "issnNorm"),
                info(WosIndexMaintenanceService.IDX_RANKING_SEARCH_EISSN_NORM, false, "eIssnNorm"),
                info(WosIndexMaintenanceService.IDX_RANKING_SEARCH_ALT_ISSNS_NORM, false, "alternativeIssnsNorm")
        ));
        when(scoringOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_SCORING_LOOKUP, false, "categoryNameCanonical", "year", "metricType", "editionNormalized"),
                info(WosIndexMaintenanceService.IDX_SCORING_JOURNAL_TIMELINE, false, "journalId", "metricType", "year", "editionNormalized")
        ));

        WosIndexMaintenanceService.WosIndexEnsureResult result = service.ensureWosIndexes();

        assertEquals(11, result.present().size());
        assertTrue(result.created().isEmpty());
        assertTrue(result.invalid().isEmpty());
        assertTrue(result.errors().isEmpty());
        verify(metricOps, never()).ensureIndex(any());
        verify(rankingOps, never()).ensureIndex(any());
        verify(scoringOps, never()).ensureIndex(any());
    }

    @Test
    void ensureWosIndexesReportsInvalidWhenShapeExistsUnderDifferentName() {
        when(metricOps.getIndexInfo()).thenReturn(List.of(
                info("other_metric_uniq", true, "journalId", "year", "metricType", "categoryNameCanonical", "editionNormalized"),
                info(WosIndexMaintenanceService.IDX_METRIC_LOOKUP, false, "categoryNameCanonical", "year", "metricType", "editionNormalized", "journalId")
        ));
        when(rankingOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_RANKING_SORT_NAME, false, "name"),
                info(WosIndexMaintenanceService.IDX_RANKING_SORT_ISSN, false, "issn"),
                info(WosIndexMaintenanceService.IDX_RANKING_SORT_EISSN, false, "eIssn"),
                info(WosIndexMaintenanceService.IDX_RANKING_SEARCH_NAME_NORM, false, "nameNorm"),
                info(WosIndexMaintenanceService.IDX_RANKING_SEARCH_ISSN_NORM, false, "issnNorm"),
                info(WosIndexMaintenanceService.IDX_RANKING_SEARCH_EISSN_NORM, false, "eIssnNorm"),
                info(WosIndexMaintenanceService.IDX_RANKING_SEARCH_ALT_ISSNS_NORM, false, "alternativeIssnsNorm")
        ));
        when(scoringOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_SCORING_LOOKUP, false, "categoryNameCanonical", "year", "metricType", "editionNormalized"),
                info(WosIndexMaintenanceService.IDX_SCORING_JOURNAL_TIMELINE, false, "journalId", "metricType", "year", "editionNormalized")
        ));

        WosIndexMaintenanceService.WosIndexEnsureResult result = service.ensureWosIndexes();

        assertEquals(1, result.invalid().size());
        assertTrue(result.invalid().getFirst().contains(WosIndexMaintenanceService.IDX_METRIC_UNIQ));
        assertTrue(result.created().isEmpty());
        assertEquals(10, result.present().size());
        verify(metricOps, never()).ensureIndex(any());
    }

    private IndexInfo info(String name, boolean unique, String... keys) {
        return new IndexInfo(
                java.util.Arrays.stream(keys).map(k -> IndexField.create(k, Sort.Direction.ASC)).toList(),
                name,
                unique,
                false,
                null
        );
    }
}
