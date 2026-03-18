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
import ro.uvt.pokedex.core.model.reporting.wos.WosFactBuildCheckpoint;
import ro.uvt.pokedex.core.model.reporting.wos.WosFactConflict;
import ro.uvt.pokedex.core.model.reporting.wos.WosIdentityConflict;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosImportEvent;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.reporting.wos.WosScoringView;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private IndexOperations categoryOps;
    @Mock
    private IndexOperations rankingOps;
    @Mock
    private IndexOperations scoringOps;
    @Mock
    private IndexOperations importEventOps;
    @Mock
    private IndexOperations journalIdentityOps;
    @Mock
    private IndexOperations checkpointOps;
    @Mock
    private IndexOperations identityConflictOps;
    @Mock
    private IndexOperations factConflictOps;

    private WosIndexMaintenanceService service;

    @BeforeEach
    void setUp() {
        service = new WosIndexMaintenanceService(mongoTemplate);
        when(mongoTemplate.indexOps(WosMetricFact.class)).thenReturn(metricOps);
        when(mongoTemplate.indexOps(WosCategoryFact.class)).thenReturn(categoryOps);
        when(mongoTemplate.indexOps(WosRankingView.class)).thenReturn(rankingOps);
        when(mongoTemplate.indexOps(WosScoringView.class)).thenReturn(scoringOps);
        when(mongoTemplate.indexOps(WosImportEvent.class)).thenReturn(importEventOps);
        when(mongoTemplate.indexOps(WosJournalIdentity.class)).thenReturn(journalIdentityOps);
        when(mongoTemplate.indexOps(WosFactBuildCheckpoint.class)).thenReturn(checkpointOps);
        when(mongoTemplate.indexOps(WosIdentityConflict.class)).thenReturn(identityConflictOps);
        when(mongoTemplate.indexOps(WosFactConflict.class)).thenReturn(factConflictOps);
    }

    @Test
    void ensureWosIndexesCreatesAllMissingIndexes() {
        when(metricOps.getIndexInfo()).thenReturn(List.of());
        when(categoryOps.getIndexInfo()).thenReturn(List.of());
        when(rankingOps.getIndexInfo()).thenReturn(List.of());
        when(scoringOps.getIndexInfo()).thenReturn(List.of());
        when(importEventOps.getIndexInfo()).thenReturn(List.of());
        when(journalIdentityOps.getIndexInfo()).thenReturn(List.of());
        when(checkpointOps.getIndexInfo()).thenReturn(List.of());
        when(identityConflictOps.getIndexInfo()).thenReturn(List.of());
        when(factConflictOps.getIndexInfo()).thenReturn(List.of());

        WosIndexMaintenanceService.WosIndexEnsureResult result = service.ensureWosIndexes();

        assertEquals(20, result.created().size());
        assertTrue(result.present().isEmpty());
        assertTrue(result.invalid().isEmpty());
        assertTrue(result.errors().isEmpty());
        verify(metricOps, org.mockito.Mockito.times(2)).createIndex(any());
        verify(categoryOps, org.mockito.Mockito.times(2)).createIndex(any());
        verify(rankingOps, org.mockito.Mockito.times(7)).createIndex(any());
        verify(scoringOps, org.mockito.Mockito.times(2)).createIndex(any());
        verify(importEventOps, org.mockito.Mockito.times(1)).createIndex(any());
        verify(journalIdentityOps, org.mockito.Mockito.times(4)).createIndex(any());
        verify(identityConflictOps, org.mockito.Mockito.times(1)).createIndex(any());
        verify(factConflictOps, org.mockito.Mockito.times(1)).createIndex(any());
    }

    @Test
    void ensureWosIndexesMarksExistingIndexesAsPresent() {
        when(metricOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_METRIC_UNIQ, true, "journalId", "year", "metricType"),
                info(WosIndexMaintenanceService.IDX_METRIC_LOOKUP, false, "year", "metricType", "journalId")
        ));
        when(categoryOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_CATEGORY_UNIQ, true, "journalId", "year", "categoryNameCanonical", "editionNormalized", "metricType"),
                info(WosIndexMaintenanceService.IDX_CATEGORY_LOOKUP, false, "categoryNameCanonical", "year", "metricType", "editionNormalized", "journalId")
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
        when(importEventOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_IMPORT_EVENT_SOURCE_SORT, false, "sourceType", "sourceFile", "sourceVersion", "sourceRowItem")
        ));
        when(journalIdentityOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_JOURNAL_IDENTITY_KEY, true, "identityKey"),
                info(WosIndexMaintenanceService.IDX_JOURNAL_PRIMARY_ISSN, false, "primaryIssn"),
                info(WosIndexMaintenanceService.IDX_JOURNAL_EISSN, false, "eIssn"),
                info(WosIndexMaintenanceService.IDX_JOURNAL_ALIAS_ISSN, false, "aliasIssns")
        ));
        when(checkpointOps.getIndexInfo()).thenReturn(List.of());
        when(identityConflictOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_IDENTITY_CONFLICT_KEY, false, "inputIdentityKey")
        ));
        when(factConflictOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_FACT_CONFLICT_KEY, false, "factKey", "factType")
        ));

        WosIndexMaintenanceService.WosIndexEnsureResult result = service.ensureWosIndexes();

        assertEquals(20, result.present().size());
        assertTrue(result.created().isEmpty());
        assertTrue(result.invalid().isEmpty());
        assertTrue(result.errors().isEmpty());
        verify(metricOps, never()).createIndex(any());
        verify(categoryOps, never()).createIndex(any());
        verify(rankingOps, never()).createIndex(any());
        verify(scoringOps, never()).createIndex(any());
    }

    @Test
    void ensureWosIndexesReportsInvalidWhenShapeExistsUnderDifferentName() {
        when(metricOps.getIndexInfo()).thenReturn(List.of(
                info("other_metric_uniq", true, "journalId", "year", "metricType"),
                info(WosIndexMaintenanceService.IDX_METRIC_LOOKUP, false, "year", "metricType", "journalId")
        ));
        when(categoryOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_CATEGORY_UNIQ, true, "journalId", "year", "categoryNameCanonical", "editionNormalized", "metricType"),
                info(WosIndexMaintenanceService.IDX_CATEGORY_LOOKUP, false, "categoryNameCanonical", "year", "metricType", "editionNormalized", "journalId")
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
        when(importEventOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_IMPORT_EVENT_SOURCE_SORT, false, "sourceType", "sourceFile", "sourceVersion", "sourceRowItem")
        ));
        when(journalIdentityOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_JOURNAL_IDENTITY_KEY, true, "identityKey"),
                info(WosIndexMaintenanceService.IDX_JOURNAL_PRIMARY_ISSN, false, "primaryIssn"),
                info(WosIndexMaintenanceService.IDX_JOURNAL_EISSN, false, "eIssn"),
                info(WosIndexMaintenanceService.IDX_JOURNAL_ALIAS_ISSN, false, "aliasIssns")
        ));
        when(checkpointOps.getIndexInfo()).thenReturn(List.of());
        when(identityConflictOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_IDENTITY_CONFLICT_KEY, false, "inputIdentityKey")
        ));
        when(factConflictOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_FACT_CONFLICT_KEY, false, "factKey", "factType")
        ));

        WosIndexMaintenanceService.WosIndexEnsureResult result = service.ensureWosIndexes();

        assertEquals(1, result.invalid().size());
        assertTrue(result.invalid().getFirst().contains(WosIndexMaintenanceService.IDX_METRIC_UNIQ));
        assertTrue(result.created().isEmpty());
        assertEquals(19, result.present().size());
        verify(metricOps, never()).createIndex(any());
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

    @Test
    void ensureWosIndexesForStageFailsFastOnInvalidIndexes() {
        when(metricOps.getIndexInfo()).thenReturn(List.of(
                info("other_metric_uniq", true, "journalId", "year", "metricType"),
                info(WosIndexMaintenanceService.IDX_METRIC_LOOKUP, false, "year", "metricType", "journalId")
        ));
        when(categoryOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_CATEGORY_UNIQ, true, "journalId", "year", "categoryNameCanonical", "editionNormalized", "metricType"),
                info(WosIndexMaintenanceService.IDX_CATEGORY_LOOKUP, false, "categoryNameCanonical", "year", "metricType", "editionNormalized", "journalId")
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
        when(importEventOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_IMPORT_EVENT_SOURCE_SORT, false, "sourceType", "sourceFile", "sourceVersion", "sourceRowItem")
        ));
        when(journalIdentityOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_JOURNAL_IDENTITY_KEY, true, "identityKey"),
                info(WosIndexMaintenanceService.IDX_JOURNAL_PRIMARY_ISSN, false, "primaryIssn"),
                info(WosIndexMaintenanceService.IDX_JOURNAL_EISSN, false, "eIssn"),
                info(WosIndexMaintenanceService.IDX_JOURNAL_ALIAS_ISSN, false, "aliasIssns")
        ));
        when(checkpointOps.getIndexInfo()).thenReturn(List.of());
        when(identityConflictOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_IDENTITY_CONFLICT_KEY, false, "inputIdentityKey")
        ));
        when(factConflictOps.getIndexInfo()).thenReturn(List.of(
                info(WosIndexMaintenanceService.IDX_FACT_CONFLICT_KEY, false, "factKey", "factType")
        ));

        assertThrows(IllegalStateException.class, () -> service.ensureWosIndexesForStage("wos-facts"));
    }
}
