package ro.uvt.pokedex.core.service.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.reporting.wos.WosScoringView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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

        WosIndexEnsureResult result = new WosIndexEnsureResult(created, present, invalid, errors);
        log.info("WoS index ensure summary: created={}, present={}, invalid={}, errors={}",
                created.size(), present.size(), invalid.size(), errors.size());
        return result;
    }

    private void ensureMetricFactIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(WosMetricFact.class);
        ensureNamedIndex(ops, new IndexDefinition(
                IDX_METRIC_UNIQ,
                true,
                List.of(field("journalId"), field("year"), field("metricType"), field("editionNormalized"))
        ), created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(
                IDX_METRIC_LOOKUP,
                false,
                List.of(field("metricType"), field("year"), field("editionNormalized"), field("journalId"))
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

    private void ensureNamedIndex(
            IndexOperations ops,
            IndexDefinition definition,
            List<String> created,
            List<String> present,
            List<String> invalid,
            List<String> errors
    ) {
        try {
            List<IndexInfo> indexInfo = ops.getIndexInfo();
            IndexInfo exact = indexInfo.stream().filter(info -> definition.matchesByNameAndShape(info)).findFirst().orElse(null);
            if (exact != null) {
                present.add(definition.name());
                return;
            }

            IndexInfo sameShapeDifferentName = indexInfo.stream().filter(definition::matchesByShape).findFirst().orElse(null);
            if (sameShapeDifferentName != null) {
                invalid.add(definition.name() + " (existing=" + sameShapeDifferentName.getName() + ")");
                return;
            }

            Index index = new Index().named(definition.name());
            for (IndexField field : definition.fields()) {
                index.on(field.getKey(), Sort.Direction.ASC);
            }
            if (definition.unique()) {
                index.unique();
            }
            ops.ensureIndex(index);
            created.add(definition.name());
        } catch (Exception e) {
            errors.add(definition.name() + ": " + e.getMessage());
        }
    }

    private IndexField field(String key) {
        return IndexField.create(key, Sort.Direction.ASC);
    }

    private record IndexDefinition(String name, boolean unique, List<IndexField> fields) {
        boolean matchesByNameAndShape(IndexInfo info) {
            if (info == null) {
                return false;
            }
            return Objects.equals(name, info.getName()) && matchesByShape(info);
        }

        boolean matchesByShape(IndexInfo info) {
            if (info == null) {
                return false;
            }
            if (unique != info.isUnique()) {
                return false;
            }
            if (info.getIndexFields().size() != fields.size()) {
                return false;
            }
            String expected = fields.stream().map(IndexField::getKey).collect(Collectors.joining("|"));
            String actual = info.getIndexFields().stream().map(IndexField::getKey).collect(Collectors.joining("|"));
            return Objects.equals(expected, actual);
        }
    }

    public record WosIndexEnsureResult(
            List<String> created,
            List<String> present,
            List<String> invalid,
            List<String> errors
    ) {
    }
}
