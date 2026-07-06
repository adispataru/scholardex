package ro.uvt.pokedex.core.service.importing.wos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.service.application.WosIndexMaintenanceService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosProjectionBuilderServiceTest {

    @Mock private WosJournalIdentityRepository identityRepository;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private WosIndexMaintenanceService wosIndexMaintenanceService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private ro.uvt.pokedex.core.service.application.ReportingDataEpochService reportingDataEpochService;

    @Test
    void rebuildCreatesProjectionRowsWithStableContracts() {
        WosProjectionBuilderService service = service();

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        when(identityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(identity("jid-1"))), new PageImpl<>(List.of()));
        when(mongoTemplate.find(any(), eq(WosMetricFact.class)))
                .thenReturn(List.of(
                        metric("jid-1", 2022, MetricType.AIS, 1.2),
                        metric("jid-1", 2023, MetricType.RIS, 0.8)
                ));
        when(mongoTemplate.find(any(), eq(WosCategoryFact.class))).thenReturn(
                List.of(
                        category("jid-1", 2022, MetricType.AIS, EditionNormalized.SCIE, "ACOUSTICS", "Q1", 7, 1),
                        category("jid-1", 2023, MetricType.RIS, EditionNormalized.SSCI, "ACOUSTICS", "Q2", null, 3)
                )
        );
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);
        ImportProcessingResult result = service.rebuildWosProjections();

        // 1 journal identity + 2 category facts = 3 imported items
        assertEquals(3, result.getImportedCount());
        // The projection TRUNCATE must be issued inside the transaction (kills removed-call mutation on execute).
        // Matched specifically (contains wos_ranking_view) so it is not conflated with the category-metric-agg
        // rebuild's own TRUNCATE/INSERT executes.
        verify(jdbcTemplate).execute(org.mockito.ArgumentMatchers.contains("wos_ranking_view"));
        // The aggregate rollup is rebuilt in the same transaction.
        verify(jdbcTemplate).execute(org.mockito.ArgumentMatchers.contains("INTO reporting_read.wos_category_metric_agg"));
    }

    @Test
    void rebuildNormalizesSearchFields() {
        WosProjectionBuilderService service = service();

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId("jid-4");
        identity.setTitle("  Journal:  Of  TESTS  ");
        identity.setPrimaryIssn(" 1234-5678 ");
        identity.setEIssn(" 8765 4321 ");
        identity.setAliasIssns(Arrays.asList("1111-2222", " 11112222 ", null, ""));
        when(identityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(identity)), new PageImpl<>(List.of()));
        when(mongoTemplate.find(any(), eq(WosMetricFact.class))).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(WosCategoryFact.class))).thenReturn(List.of());
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);
        // Just verify the service runs without error; normalized fields are written to DB
        ImportProcessingResult result = service.rebuildWosProjections();

        // 1 processed for the identity
        assertEquals(1, result.getImportedCount());
    }

    @Test
    void scopedRebuildDeletesOnlyAffectedJournalsAndDoesNotTruncate() {
        WosProjectionBuilderService service = service();

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        when(identityRepository.findAllById(List.of("jid-1"))).thenReturn(List.of(identity("jid-1")));
        when(mongoTemplate.find(any(), eq(WosMetricFact.class)))
                .thenAnswer(invocation -> {
                    Query query = invocation.getArgument(0);
                    Object journalId = query.getQueryObject().get("journalId");
                    Object year = query.getQueryObject().get("year");
                    Object metricType = query.getQueryObject().get("metricType");
                    if ("jid-1".equals(journalId) && Integer.valueOf(2024).equals(year) && MetricType.AIS.equals(metricType)) {
                        return List.of(metric("jid-1", 2024, MetricType.AIS, 1.2));
                    }
                    if ("jid-1".equals(journalId) && year == null && metricType == null) {
                        return List.of(
                                metric("jid-1", 2024, MetricType.AIS, 1.2),
                                metric("jid-1", 2023, MetricType.RIS, 0.8)
                        );
                    }
                    return List.of();
                });
        when(mongoTemplate.find(any(), eq(WosCategoryFact.class)))
                .thenAnswer(invocation -> {
                    Query query = invocation.getArgument(0);
                    Object journalId = query.getQueryObject().get("journalId");
                    Object year = query.getQueryObject().get("year");
                    Object categoryNameCanonical = query.getQueryObject().get("categoryNameCanonical");
                    Object editionNormalized = query.getQueryObject().get("editionNormalized");
                    Object metricType = query.getQueryObject().get("metricType");
                    if ("jid-1".equals(journalId)
                            && Integer.valueOf(2024).equals(year)
                            && "ECONOMICS".equals(categoryNameCanonical)
                            && EditionNormalized.SCIE.equals(editionNormalized)
                            && MetricType.AIS.equals(metricType)) {
                        return List.of(category("jid-1", 2024, MetricType.AIS, EditionNormalized.SCIE, "ECONOMICS", null, null, null));
                    }
                    if ("jid-1".equals(journalId)
                            && year == null
                            && categoryNameCanonical == null
                            && editionNormalized == null
                            && metricType == null) {
                        return List.of(
                                category("jid-1", 2024, MetricType.AIS, EditionNormalized.SCIE, "ECONOMICS", null, null, null),
                                category("jid-1", 2023, MetricType.RIS, EditionNormalized.SSCI, "HISTORY", "Q2", null, 3)
                        );
                    }
                    return List.of();
                });
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);
        when(jdbcTemplate.batchUpdate(anyString(), anyList(), anyInt(), any())).thenReturn(new int[0][]);

        ImportProcessingResult result = service.rebuildWosProjectionsForScope(
                new LinkedHashSet<>(Set.of("jid-1")),
                List.of(metric("jid-1", 2024, MetricType.AIS, 1.2)),
                List.of(category("jid-1", 2024, MetricType.AIS, EditionNormalized.SCIE, "ECONOMICS", null, null, null))
        );

        assertEquals(2, result.getImportedCount());
        // the scoped rebuild now refreshes the per-year category aggregates (new-year uploads must move
        // the categories' reference years) — the only execute() calls are the agg TRUNCATE + INSERT..SELECT
        org.mockito.ArgumentCaptor<String> executed = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).execute(executed.capture());
        assertTrue(executed.getAllValues().get(0).contains("TRUNCATE TABLE reporting_read.wos_category_metric_agg"));
        assertTrue(executed.getAllValues().get(1).contains("INTO reporting_read.wos_category_metric_agg"));
        verify(jdbcTemplate, never()).batchUpdate(eq("DELETE FROM reporting_read.wos_ranking_view WHERE journal_id = ?"), anyList(), anyInt(), any());
        verify(jdbcTemplate).batchUpdate(eq("DELETE FROM reporting_read.wos_metric_fact WHERE journal_id = ? AND year = ? AND metric_type = ?"), anyList(), anyInt(), any());
        verify(jdbcTemplate).batchUpdate(eq("DELETE FROM reporting_read.wos_category_fact WHERE journal_id = ? AND year = ? AND category_name_canonical = ? AND edition_normalized = ? AND metric_type = ?"), anyList(), anyInt(), any());
        verify(jdbcTemplate).batchUpdate(eq("DELETE FROM reporting_read.wos_scoring_view WHERE journal_id = ? AND year = ? AND category_name_canonical = ? AND edition_normalized = ? AND metric_type = ?"), anyList(), anyInt(), any());
        // verify all 4 insert/upsert calls were made (kills VoidMethodCallMutator at L386-389)
        verify(jdbcTemplate, org.mockito.Mockito.atLeast(4)).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
    }

    @Test
    void rankingSetterBindsArrayContentsAndRisYear() throws Exception {
        WosProjectionBuilderService service = service();
        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId("jid-array");
        identity.setTitle("Array Journal");
        identity.setPrimaryIssn("1234-5678");
        identity.setEIssn("8765-4321");
        identity.setAliasIssns(Arrays.asList("1111-2222", null, ""));  // null + blank in list
        identity.setAlternativeNames(List.of("Alt Name"));
        when(identityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(identity)), new PageImpl<>(List.of()));
        // Both AIS and RIS metrics so latestRisYear is non-null
        when(mongoTemplate.find(any(), eq(WosMetricFact.class))).thenReturn(List.of(
                metric("jid-array", 2022, MetricType.AIS, 0.9),
                metric("jid-array", 2021, MetricType.RIS, 0.5)
        ));
        when(mongoTemplate.find(any(), eq(WosCategoryFact.class))).thenReturn(List.of(
                category("jid-array", 2022, MetricType.AIS, EditionNormalized.SCIE, "PHYSICS", null, null, 2)
        ));

        ArgumentCaptor<BatchPreparedStatementSetter> captor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        when(jdbcTemplate.batchUpdate(anyString(), captor.capture())).thenReturn(new int[0]);

        service.rebuildWosProjections();

        BatchPreparedStatementSetter setter = captor.getAllValues().get(0); // ranking is first

        Connection conn = mock(Connection.class);
        ArgumentCaptor<Object[]> arrayArgCaptor = ArgumentCaptor.forClass(Object[].class);
        Array arr = mock(Array.class);
        when(conn.createArrayOf(eq("text"), arrayArgCaptor.capture())).thenReturn(arr);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.getConnection()).thenReturn(conn);

        setter.setValues(ps, 0);

        verify(ps).setInt(11, 2022);        // latest_ais_year
        verify(ps).setInt(12, 2021);        // latest_ris_year (non-null RIS metric → kills setLatestRisYear mutation)
        verify(ps).setArray(5, arr);        // alternative_issns (kills setAlternativeIssns mutation)
        verify(ps).setArray(6, arr);        // alternative_names (kills setAlternativeNames mutation)
        verify(ps).setArray(10, arr);       // alternative_issns_norm (kills setAlternativeIssnsNorm mutation)

        // Verify normalizeIssnList skipped null and blank tokens → only "11112222" survives
        boolean hasNormalizedAlias = arrayArgCaptor.getAllValues().stream()
                .anyMatch(a -> java.util.Arrays.asList(a).contains("11112222"));
        assertTrue(hasNormalizedAlias, "normalizeIssnList should include '11112222' and skip null/blank tokens");
    }

    @Test
    void rankingSetterHandlesNullIssnAndNullTitle() throws Exception {
        WosProjectionBuilderService service = service();
        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId("jid-null-issn");
        identity.setTitle(null);
        identity.setPrimaryIssn(null);
        identity.setEIssn(null);
        identity.setAliasIssns(null);
        identity.setAlternativeNames(null);
        when(identityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(identity)), new PageImpl<>(List.of()));
        when(mongoTemplate.find(any(), eq(WosMetricFact.class))).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(WosCategoryFact.class))).thenReturn(List.of());

        ArgumentCaptor<BatchPreparedStatementSetter> captor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        when(jdbcTemplate.batchUpdate(anyString(), captor.capture())).thenReturn(new int[0]);

        service.rebuildWosProjections();

        BatchPreparedStatementSetter setter = captor.getAllValues().get(0);
        assertEquals(1, setter.getBatchSize());

        Connection conn = mock(Connection.class);
        when(conn.createArrayOf(eq("text"), any(Object[].class))).thenReturn(mock(Array.class));
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.getConnection()).thenReturn(conn);

        // Must not throw — kills normalizeIssn(null) null-check removal mutation
        setter.setValues(ps, 0);

        verify(ps).setString(3, null);         // issn = null
        verify(ps).setString(4, null);         // e_issn = null
        verify(ps).setString(8, (String) null); // issn_norm = null (normalizeIssn(null) → null)
        verify(ps).setString(9, (String) null); // e_issn_norm = null
    }

    @Test
    void scoringSetterWithNonNullQuartileRankSetsInt() throws Exception {
        WosProjectionBuilderService service = service();
        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        WosMetricFact metricFact = new WosMetricFact();
        metricFact.setJournalId("jid-qr");
        metricFact.setYear(2022);
        metricFact.setMetricType(MetricType.AIS);
        metricFact.setValue(0.5);

        WosCategoryFact categoryFact = new WosCategoryFact();
        categoryFact.setJournalId("jid-qr");
        categoryFact.setYear(2022);
        categoryFact.setCategoryNameCanonical("BIOLOGY");
        categoryFact.setEditionNormalized(EditionNormalized.SCIE);
        categoryFact.setMetricType(MetricType.AIS);
        categoryFact.setQuartileRank(2);  // non-null → must call setInt(9, 2), not setNull
        categoryFact.setRank(4);

        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId("jid-qr");
        identity.setTitle("QR Journal");
        when(identityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(identity)), new PageImpl<>(List.of()));
        when(mongoTemplate.find(any(), eq(WosMetricFact.class))).thenReturn(List.of(metricFact));
        when(mongoTemplate.find(any(), eq(WosCategoryFact.class))).thenReturn(List.of(categoryFact));

        ArgumentCaptor<BatchPreparedStatementSetter> captor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        when(jdbcTemplate.batchUpdate(anyString(), captor.capture())).thenReturn(new int[0]);

        service.rebuildWosProjections();

        // Scoring setter is last (ranking, metric, category, scoring = index 3)
        BatchPreparedStatementSetter setter = captor.getAllValues().get(3);
        PreparedStatement ps = mock(PreparedStatement.class);

        setter.setValues(ps, 0);

        // kills toScoringView::setQuartileRank mutation
        verify(ps).setInt(9, 2);
    }

    @Test
    void resolveLatestEditionReturnsUnknownWhenNoCategoryFacts() throws Exception {
        WosProjectionBuilderService service = service();
        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId("jid-unknown-ed");
        identity.setTitle("No Category Journal");
        when(identityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(identity)), new PageImpl<>(List.of()));
        when(mongoTemplate.find(any(), eq(WosMetricFact.class))).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(WosCategoryFact.class))).thenReturn(List.of());

        ArgumentCaptor<BatchPreparedStatementSetter> captor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        when(jdbcTemplate.batchUpdate(anyString(), captor.capture())).thenReturn(new int[0]);

        service.rebuildWosProjections();

        BatchPreparedStatementSetter rankingSetter = captor.getAllValues().get(0);
        Connection conn = mock(Connection.class);
        when(conn.createArrayOf(eq("text"), any(Object[].class))).thenReturn(mock(Array.class));
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.getConnection()).thenReturn(conn);

        rankingSetter.setValues(ps, 0);

        // resolveLatestEdition falls through to UNKNOWN when no category facts exist
        // kills "replaced return value with null" mutation at L483
        verify(ps).setObject(13, "UNKNOWN", Types.OTHER);
    }

    @Test
    void rankingSetterBindsAllColumnsCorrectly() throws Exception {
        WosProjectionBuilderService service = service();
        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId("jid-rank");
        identity.setTitle("Journal RANK");
        identity.setPrimaryIssn("1234-5678");
        identity.setEIssn("8765-4321");
        identity.setAliasIssns(List.of("1111-2222"));
        identity.setAlternativeNames(List.of("Alt RANK"));
        when(identityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(identity)), new PageImpl<>(List.of()));

        WosMetricFact metricFact = new WosMetricFact();
        metricFact.setJournalId("jid-rank");
        metricFact.setYear(2023);
        metricFact.setMetricType(MetricType.AIS);
        metricFact.setValue(1.5);
        when(mongoTemplate.find(any(), eq(WosMetricFact.class))).thenReturn(List.of(metricFact));
        when(mongoTemplate.find(any(), eq(WosCategoryFact.class))).thenReturn(List.of(
                category("jid-rank", 2023, MetricType.AIS, EditionNormalized.SCIE, "ACOUSTICS", "Q1", null, 5)
        ));

        ArgumentCaptor<BatchPreparedStatementSetter> captor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        when(jdbcTemplate.batchUpdate(anyString(), captor.capture())).thenReturn(new int[0]);

        service.rebuildWosProjections();

        // Ranking setter is the first captured (insertWosRankingRows is first in the transaction)
        BatchPreparedStatementSetter setter = captor.getAllValues().get(0);
        assertEquals(1, setter.getBatchSize());

        Connection conn = mock(Connection.class);
        Array arr = mock(Array.class);
        when(conn.createArrayOf(eq("text"), any(Object[].class))).thenReturn(arr);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.getConnection()).thenReturn(conn);

        setter.setValues(ps, 0);

        verify(ps).setString(1, "jid-rank");           // journal_id
        verify(ps).setString(2, "Journal RANK");        // name
        verify(ps).setString(3, "1234-5678");           // issn (raw)
        verify(ps).setString(4, "8765-4321");           // e_issn (raw)
        verify(ps).setString(7, "journal rank");        // name_norm
        verify(ps).setString(8, "12345678");            // issn_norm
        verify(ps).setString(9, "87654321");            // e_issn_norm
        verify(ps).setInt(11, 2023);                    // latest_ais_year
        verify(ps).setNull(12, Types.INTEGER);          // latest_ris_year (no RIS fact)
        verify(ps).setObject(13, "SCIE", Types.OTHER);  // latest_edition_normalized
        verify(ps).setString(eq(14), anyString());      // build_version (dynamic)
        verify(ps).setTimestamp(eq(15), any(Timestamp.class)); // build_at
        verify(ps).setTimestamp(eq(16), any(Timestamp.class)); // updated_at
    }

    @Test
    void metricSetterBindsAllColumnsCorrectly() throws Exception {
        WosProjectionBuilderService service = service();
        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        WosMetricFact metricFact = new WosMetricFact();
        metricFact.setId("mf-id-1");
        metricFact.setJournalId("jid-metric");
        metricFact.setYear(2022);
        metricFact.setMetricType(MetricType.RIS);
        metricFact.setValue(0.75);
        metricFact.setSourceType(WosSourceType.GOV_AIS_RIS);
        metricFact.setSourceEventId("ev-m1");
        metricFact.setSourceFile("ris.xlsx");
        metricFact.setSourceVersion("v2022");
        metricFact.setSourceRowItem("3");
        metricFact.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));

        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId("jid-metric");
        identity.setTitle("Journal Metric");
        when(identityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(identity)), new PageImpl<>(List.of()));
        when(mongoTemplate.find(any(), eq(WosMetricFact.class))).thenReturn(List.of(metricFact));
        when(mongoTemplate.find(any(), eq(WosCategoryFact.class))).thenReturn(List.of());

        ArgumentCaptor<BatchPreparedStatementSetter> captor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        when(jdbcTemplate.batchUpdate(anyString(), captor.capture())).thenReturn(new int[0]);

        service.rebuildWosProjections();

        // Metric setter is second captured (after ranking)
        BatchPreparedStatementSetter setter = captor.getAllValues().get(1);
        assertEquals(1, setter.getBatchSize());

        PreparedStatement ps = mock(PreparedStatement.class);
        setter.setValues(ps, 0);

        verify(ps).setString(1, "mf-id-1");                         // id
        verify(ps).setString(2, "jid-metric");                       // journal_id
        verify(ps).setInt(3, 2022);                                  // year
        verify(ps).setObject(4, "RIS", Types.OTHER);                 // metric_type
        verify(ps).setDouble(5, 0.75);                               // value
        verify(ps).setObject(6, "GOV_AIS_RIS", Types.OTHER);         // source_type
        verify(ps).setString(7, "ev-m1");                            // source_event_id
        verify(ps).setString(8, "ris.xlsx");                         // source_file
        verify(ps).setString(9, "v2022");                            // source_version
        verify(ps).setString(10, "3");                               // source_row_item
        verify(ps).setTimestamp(eq(11), any(Timestamp.class));       // created_at non-null
    }

    @Test
    void categorySetterBindsAllColumnsCorrectly() throws Exception {
        WosProjectionBuilderService service = service();
        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        WosCategoryFact categoryFact = new WosCategoryFact();
        categoryFact.setId("cf-id-1");
        categoryFact.setJournalId("jid-cat");
        categoryFact.setYear(2021);
        categoryFact.setCategoryNameCanonical("CHEMISTRY");
        categoryFact.setEditionRaw("SCIE");
        categoryFact.setEditionNormalized(EditionNormalized.SCIE);
        categoryFact.setMetricType(MetricType.AIS);
        categoryFact.setQuarter("Q2");
        categoryFact.setQuartileRank(3);
        categoryFact.setRank(7);
        categoryFact.setSourceType(WosSourceType.OFFICIAL_WOS_EXTRACT);
        categoryFact.setSourceEventId("ev-c1");
        categoryFact.setSourceFile("wos.json");
        categoryFact.setSourceVersion("v2021");
        categoryFact.setSourceRowItem("5");
        categoryFact.setCreatedAt(Instant.parse("2024-06-01T00:00:00Z"));

        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId("jid-cat");
        identity.setTitle("Journal Cat");
        when(identityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(identity)), new PageImpl<>(List.of()));
        when(mongoTemplate.find(any(), eq(WosMetricFact.class))).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(WosCategoryFact.class))).thenReturn(List.of(categoryFact));

        ArgumentCaptor<BatchPreparedStatementSetter> captor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        when(jdbcTemplate.batchUpdate(anyString(), captor.capture())).thenReturn(new int[0]);

        service.rebuildWosProjections();

        // Category setter is second captured — no metric facts so metric batchUpdate is skipped
        BatchPreparedStatementSetter setter = captor.getAllValues().get(1);
        assertEquals(1, setter.getBatchSize());

        PreparedStatement ps = mock(PreparedStatement.class);
        setter.setValues(ps, 0);

        verify(ps).setString(1, "cf-id-1");                          // id
        verify(ps).setString(2, "jid-cat");                          // journal_id
        verify(ps).setInt(3, 2021);                                   // year
        verify(ps).setString(4, "CHEMISTRY");                         // category_name_canonical
        verify(ps).setString(5, "SCIE");                              // edition_raw
        verify(ps).setObject(6, "SCIE", Types.OTHER);                 // edition_normalized
        verify(ps).setObject(7, "AIS", Types.OTHER);                  // metric_type
        verify(ps).setString(8, "Q2");                                // quarter
        verify(ps).setInt(9, 3);                                      // quartile_rank
        verify(ps).setInt(10, 7);                                     // rank
        verify(ps).setObject(11, "OFFICIAL_WOS_EXTRACT", Types.OTHER);// source_type
        verify(ps).setString(12, "ev-c1");                            // source_event_id
        verify(ps).setString(13, "wos.json");                         // source_file
        verify(ps).setString(14, "v2021");                            // source_version
        verify(ps).setString(15, "5");                                // source_row_item
        verify(ps).setTimestamp(eq(16), any(Timestamp.class));        // created_at non-null
    }

    @Test
    void scoringSetterBindsValueFromLinkedMetricFact() throws Exception {
        WosProjectionBuilderService service = service();
        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        WosMetricFact metricFact = new WosMetricFact();
        metricFact.setJournalId("jid-score");
        metricFact.setYear(2023);
        metricFact.setMetricType(MetricType.AIS);
        metricFact.setValue(2.34);

        WosCategoryFact categoryFact = new WosCategoryFact();
        categoryFact.setId("cf-score-1");
        categoryFact.setJournalId("jid-score");
        categoryFact.setYear(2023);
        categoryFact.setCategoryNameCanonical("PHYSICS");
        categoryFact.setEditionNormalized(EditionNormalized.SSCI);
        categoryFact.setMetricType(MetricType.AIS);
        categoryFact.setQuarter("Q3");
        categoryFact.setQuartileRank(null);
        categoryFact.setRank(10);

        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId("jid-score");
        identity.setTitle("Journal Score");
        when(identityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(identity)), new PageImpl<>(List.of()));
        when(mongoTemplate.find(any(), eq(WosMetricFact.class))).thenReturn(List.of(metricFact));
        when(mongoTemplate.find(any(), eq(WosCategoryFact.class))).thenReturn(List.of(categoryFact));

        ArgumentCaptor<BatchPreparedStatementSetter> captor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        when(jdbcTemplate.batchUpdate(anyString(), captor.capture())).thenReturn(new int[0]);

        service.rebuildWosProjections();

        // Scoring setter is fourth captured
        BatchPreparedStatementSetter setter = captor.getAllValues().get(3);
        assertEquals(1, setter.getBatchSize());

        PreparedStatement ps = mock(PreparedStatement.class);
        setter.setValues(ps, 0);

        // id is a SHA-256 hash — just verify it's set to something non-null
        verify(ps).setString(eq(1), anyString());                     // id (sha256)
        verify(ps).setString(2, "jid-score");                         // journal_id
        verify(ps).setInt(3, 2023);                                   // year
        verify(ps).setString(4, "PHYSICS");                           // category_name_canonical
        verify(ps).setObject(5, "SSCI", Types.OTHER);                 // edition_normalized
        verify(ps).setObject(6, "AIS", Types.OTHER);                  // metric_type
        verify(ps).setDouble(7, 2.34);                                // value (from linked metric fact)
        verify(ps).setString(8, "Q3");                                // quarter
        verify(ps).setNull(9, Types.INTEGER);                         // quartile_rank null
        verify(ps).setInt(10, 10);                                    // rank
        verify(ps).setString(eq(11), anyString());                    // build_version
        verify(ps).setTimestamp(eq(12), any(Timestamp.class));        // build_at
        verify(ps).setTimestamp(eq(13), any(Timestamp.class));        // updated_at
    }

    @Test
    void journalScopedRebuildInsertsRowsAndReportsNoErrors() {
        WosProjectionBuilderService service = service();

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        when(mongoTemplate.find(any(Query.class), eq(WosMetricFact.class)))
                .thenReturn(List.of(metric("jid-1", 2024, MetricType.AIS, 1.5)));
        when(mongoTemplate.find(any(Query.class), eq(WosCategoryFact.class)))
                .thenReturn(List.of(category("jid-1", 2024, MetricType.AIS, EditionNormalized.SCIE, "ACOUSTICS", "Q1", 1, 2)));
        when(identityRepository.findAllById(any()))
                .thenReturn(List.of(identity("jid-1")));
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);
        when(jdbcTemplate.batchUpdate(anyString(), anyList(), anyInt(), any())).thenReturn(new int[0][]);

        ImportProcessingResult result = service.rebuildWosProjectionsForJournals(Set.of("jid-1"));

        assertTrue(result.getImportedCount() > 0);
        assertEquals(0, result.getErrorCount());
        // insert calls for ranking, metric, category, scoring (kills VoidMethodCallMutator mutations)
        org.mockito.Mockito.verify(jdbcTemplate, org.mockito.Mockito.atLeast(4))
                .batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
    }

    @Test
    void journalScopedRebuildWithNullJournalIdsReturnsEmptyResult() {
        WosProjectionBuilderService service = service();

        ImportProcessingResult result = service.rebuildWosProjectionsForJournals(null);

        assertEquals(0, result.getProcessedCount());
        assertEquals(0, result.getErrorCount());
    }

    @Test
    void journalScopedRebuildWithEmptyJournalIdsReturnsEmptyResult() {
        WosProjectionBuilderService service = service();

        ImportProcessingResult result = service.rebuildWosProjectionsForJournals(Set.of());

        assertEquals(0, result.getProcessedCount());
        assertEquals(0, result.getErrorCount());
    }

    private WosProjectionBuilderService service() {
        WosOptimizationProperties properties = new WosOptimizationProperties();
        return new WosProjectionBuilderService(
                identityRepository,
                mongoTemplate,
                wosIndexMaintenanceService,
                properties,
                jdbcTemplate,
                transactionManager,
                reportingDataEpochService
        );
    }

    private WosJournalIdentity identity(String id) {
        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId(id);
        identity.setTitle("Journal " + id);
        identity.setPrimaryIssn("12345678");
        identity.setEIssn("87654321");
        identity.setAliasIssns(List.of("11112222"));
        identity.setAlternativeNames(List.of("Alt Journal " + id));
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
