package ro.uvt.pokedex.core.service.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Service;
import static ro.uvt.pokedex.core.service.application.IndexMaintenanceSupport.IndexDefinition;
import ro.uvt.pokedex.core.model.reporting.wos.WosFactBuildCheckpoint;
import ro.uvt.pokedex.core.model.reporting.wos.WosFactConflict;
import ro.uvt.pokedex.core.model.reporting.wos.WosIdentityConflict;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosImportEvent;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.reporting.wos.WosScoringView;

import java.util.ArrayList;
import java.util.List;

@Service
public class WosIndexMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(WosIndexMaintenanceService.class);

    static final String IDX_METRIC_UNIQ = "uniq_metric_fact";
    static final String IDX_METRIC_LOOKUP = "idx_wos_metric_lookup";
    static final String IDX_CATEGORY_UNIQ = "uniq_category_fact";
    static final String IDX_CATEGORY_LOOKUP = "idx_wos_category_lookup";
    static final String IDX_RANKING_SORT_NAME = "idx_wos_ranking_sort_name";
    static final String IDX_RANKING_SORT_ISSN = "idx_wos_ranking_sort_issn";
    static final String IDX_RANKING_SORT_EISSN = "idx_wos_ranking_sort_eissn";
    static final String IDX_RANKING_SEARCH_NAME_NORM = "idx_wos_ranking_name_norm";
    static final String IDX_RANKING_SEARCH_ISSN_NORM = "idx_wos_ranking_issn_norm";
    static final String IDX_RANKING_SEARCH_EISSN_NORM = "idx_wos_ranking_eissn_norm";
    static final String IDX_RANKING_SEARCH_ALT_ISSNS_NORM = "idx_wos_ranking_alt_issns_norm";
    static final String IDX_SCORING_LOOKUP = "idx_wos_scoring_lookup";
    static final String IDX_SCORING_JOURNAL_TIMELINE = "idx_wos_scoring_journal_timeline";
    static final String IDX_IMPORT_EVENT_SOURCE_SORT = "idx_wos_import_event_source_sort";
    static final String IDX_JOURNAL_IDENTITY_KEY = "uniq_identity_key";
    static final String IDX_JOURNAL_PRIMARY_ISSN = "idx_wos_journal_identity_primary_issn";
    static final String IDX_JOURNAL_EISSN = "idx_wos_journal_identity_eissn";
    static final String IDX_JOURNAL_ALIAS_ISSN = "idx_wos_journal_identity_alias_issn";
    static final String IDX_IDENTITY_CONFLICT_KEY = "idx_wos_identity_conflict_key";
    static final String IDX_FACT_CONFLICT_KEY = "idx_wos_fact_conflict_key";

    private final MongoTemplate mongoTemplate;

    public WosIndexMaintenanceService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public WosIndexEnsureResult ensureWosIndexes() {
        List<String> created = new ArrayList<>();
        List<String> present = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        ensureMetricFactIndexes(created, present, invalid, errors);
        ensureCategoryFactIndexes(created, present, invalid, errors);
        ensureRankingViewIndexes(created, present, invalid, errors);
        ensureScoringViewIndexes(created, present, invalid, errors);
        ensureImportEventIndexes(created, present, invalid, errors);
        ensureJournalIdentityIndexes(created, present, invalid, errors);
        ensureSupportingIndexes(created, present, invalid, errors);

        WosIndexEnsureResult result = new WosIndexEnsureResult(created, present, invalid, errors);
        log.info("WoS index ensure summary: created={}, present={}, invalid={}, errors={}",
                created.size(), present.size(), invalid.size(), errors.size());
        return result;
    }

    public WosIndexEnsureResult ensureWosIndexesForStage(String stageName) {
        WosIndexEnsureResult result = ensureWosIndexes();
        if (!result.invalid().isEmpty() || !result.errors().isEmpty()) {
            throw new IllegalStateException(
                    "WoS index preflight failed for stage " + stageName
                            + ": invalid=" + result.invalid()
                            + ", errors=" + result.errors()
            );
        }
        return result;
    }

    private void ensureMetricFactIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(WosMetricFact.class);
        ensureNamedIndex(ops, new IndexDefinition(
                IDX_METRIC_UNIQ,
                true,
                List.of(field("journalId"), field("year"), field("metricType"))
        ), created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(
                IDX_METRIC_LOOKUP,
                false,
                List.of(field("year"), field("metricType"), field("journalId"))
        ), created, present, invalid, errors);
    }

    private void ensureCategoryFactIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(WosCategoryFact.class);
        ensureNamedIndex(ops, new IndexDefinition(
                IDX_CATEGORY_UNIQ,
                true,
                List.of(field("journalId"), field("year"), field("categoryNameCanonical"), field("editionNormalized"), field("metricType"))
        ), created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(
                IDX_CATEGORY_LOOKUP,
                false,
                List.of(field("categoryNameCanonical"), field("year"), field("metricType"), field("editionNormalized"), field("journalId"))
        ), created, present, invalid, errors);
    }

    private void ensureRankingViewIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(WosRankingView.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_RANKING_SORT_NAME, false, List.of(field("name"))), created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_RANKING_SORT_ISSN, false, List.of(field("issn"))), created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_RANKING_SORT_EISSN, false, List.of(field("eIssn"))), created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_RANKING_SEARCH_NAME_NORM, false, List.of(field("nameNorm"))), created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_RANKING_SEARCH_ISSN_NORM, false, List.of(field("issnNorm"))), created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_RANKING_SEARCH_EISSN_NORM, false, List.of(field("eIssnNorm"))), created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_RANKING_SEARCH_ALT_ISSNS_NORM, false, List.of(field("alternativeIssnsNorm"))), created, present, invalid, errors);
    }

    private void ensureScoringViewIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(WosScoringView.class);
        ensureNamedIndex(ops, new IndexDefinition(
                IDX_SCORING_LOOKUP,
                false,
                List.of(field("categoryNameCanonical"), field("year"), field("metricType"), field("editionNormalized"))
        ), created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(
                IDX_SCORING_JOURNAL_TIMELINE,
                false,
                List.of(field("journalId"), field("metricType"), field("year"), field("editionNormalized"))
        ), created, present, invalid, errors);
    }

    private void ensureImportEventIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(WosImportEvent.class);
        ensureNamedIndex(ops, new IndexDefinition(
                IDX_IMPORT_EVENT_SOURCE_SORT,
                false,
                List.of(field("sourceType"), field("sourceFile"), field("sourceVersion"), field("sourceRowItem"))
        ), created, present, invalid, errors);
    }

    private void ensureJournalIdentityIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(WosJournalIdentity.class);
        ensureNamedIndex(ops, new IndexDefinition(
                IDX_JOURNAL_IDENTITY_KEY,
                true,
                List.of(field("identityKey"))
        ), created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(
                IDX_JOURNAL_PRIMARY_ISSN,
                false,
                List.of(field("primaryIssn"))
        ), created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(
                IDX_JOURNAL_EISSN,
                false,
                List.of(field("eIssn"))
        ), created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(
                IDX_JOURNAL_ALIAS_ISSN,
                false,
                List.of(field("aliasIssns"))
        ), created, present, invalid, errors);
    }

    private void ensureSupportingIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        mongoTemplate.indexOps(WosFactBuildCheckpoint.class).getIndexInfo();
        ensureNamedIndex(mongoTemplate.indexOps(WosIdentityConflict.class),
                new IndexDefinition(IDX_IDENTITY_CONFLICT_KEY, false, List.of(field("inputIdentityKey"))),
                created, present, invalid, errors);
        ensureNamedIndex(mongoTemplate.indexOps(WosFactConflict.class),
                new IndexDefinition(IDX_FACT_CONFLICT_KEY, false, List.of(field("factKey"), field("factType"))),
                created, present, invalid, errors);
    }

    private void ensureNamedIndex(
            IndexOperations ops,
            IndexDefinition definition,
            List<String> created,
            List<String> present,
            List<String> invalid,
            List<String> errors
    ) {
        IndexMaintenanceSupport.ensureNamedIndex(ops, definition, created, present, invalid, errors);
    }

    private IndexField field(String key) {
        return IndexMaintenanceSupport.field(key);
    }

    public record WosIndexEnsureResult(
            List<String> created,
            List<String> present,
            List<String> invalid,
            List<String> errors
    ) {
    }
}
