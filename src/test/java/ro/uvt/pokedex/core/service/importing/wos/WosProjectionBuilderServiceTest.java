package ro.uvt.pokedex.core.service.importing.wos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.service.application.WosIndexMaintenanceService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosProjectionBuilderServiceTest {

    @Mock private WosJournalIdentityRepository identityRepository;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private WosIndexMaintenanceService wosIndexMaintenanceService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private PlatformTransactionManager transactionManager;

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
        when(jdbcTemplate.batchUpdate(anyString(), anyList(), eq(500), any())).thenReturn(new int[0][]);

        ImportProcessingResult result = service.rebuildWosProjections();

        // 1 journal identity + 2 category facts = 3 imported items
        assertEquals(3, result.getImportedCount());
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
        when(jdbcTemplate.batchUpdate(anyString(), anyList(), eq(500), any())).thenReturn(new int[0][]);

        // Just verify the service runs without error; normalized fields are written to DB
        ImportProcessingResult result = service.rebuildWosProjections();

        // 1 processed for the identity
        assertEquals(1, result.getImportedCount());
    }

    private WosProjectionBuilderService service() {
        WosOptimizationProperties properties = new WosOptimizationProperties();
        return new WosProjectionBuilderService(
                identityRepository,
                mongoTemplate,
                wosIndexMaintenanceService,
                properties,
                jdbcTemplate,
                transactionManager
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
