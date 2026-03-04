package ro.uvt.pokedex.core.service.application;

import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.repository.scopus.ScopusCitationRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class CitationUniquenessMigrationService {

    private static final String UNIQUE_INDEX_NAME = "uniq_cited_citing";

    private final MongoTemplate mongoTemplate;
    private final ScopusCitationRepository scopusCitationRepository;

    public CitationUniquenessMigrationService(MongoTemplate mongoTemplate,
                                              ScopusCitationRepository scopusCitationRepository) {
        this.mongoTemplate = mongoTemplate;
        this.scopusCitationRepository = scopusCitationRepository;
    }

    public DuplicateScanResult scanDuplicates() {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.group("citedId", "citingId")
                        .count().as("count")
                        .push("_id").as("ids"),
                Aggregation.match(org.springframework.data.mongodb.core.query.Criteria.where("count").gt(1))
        );

        List<Document> grouped = mongoTemplate.aggregate(
                aggregation,
                Citation.class,
                Document.class
        ).getMappedResults();

        List<DuplicatePair> duplicates = new ArrayList<>();
        for (Document group : grouped) {
            Document pair = group.get("_id", Document.class);
            if (pair == null) {
                continue;
            }
            String citedId = pair.getString("citedId");
            String citingId = pair.getString("citingId");
            List<String> ids = readIds(group.get("ids"));
            duplicates.add(new DuplicatePair(citedId, citingId, ids));
        }

        return new DuplicateScanResult(duplicates);
    }

    public DedupeResult applyDedupeKeepingLowestId(DuplicateScanResult scanResult) {
        int affectedPairs = 0;
        int deletedRows = 0;
        for (DuplicatePair pair : scanResult.duplicatePairs()) {
            DedupeCandidate candidate = buildCandidate(pair.ids());
            if (candidate.idsToDelete().isEmpty()) {
                continue;
            }
            scopusCitationRepository.deleteAllById(candidate.idsToDelete());
            affectedPairs++;
            deletedRows += candidate.idsToDelete().size();
        }
        return new DedupeResult(affectedPairs, deletedRows);
    }

    public void ensureUniqueIndex() {
        mongoTemplate.indexOps(Citation.class).ensureIndex(
                new Index()
                        .on("citedId", Sort.Direction.ASC)
                        .on("citingId", Sort.Direction.ASC)
                        .unique()
                        .named(UNIQUE_INDEX_NAME)
        );
    }

    public VerificationResult verifyPostConditions() {
        DuplicateScanResult afterScan = scanDuplicates();
        List<IndexInfo> indexInfo = mongoTemplate.indexOps(Citation.class).getIndexInfo();
        boolean uniqueIndexPresent = indexInfo.stream().anyMatch(this::isTargetUniqueIndex);
        return new VerificationResult(afterScan.duplicatePairs().isEmpty(), uniqueIndexPresent);
    }

    public DedupeCandidate buildCandidate(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return new DedupeCandidate(null, List.of());
        }
        List<String> sorted = ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .sorted(Comparator.naturalOrder())
                .toList();
        if (sorted.isEmpty()) {
            return new DedupeCandidate(null, List.of());
        }
        return new DedupeCandidate(
                sorted.getFirst(),
                sorted.stream().skip(1).toList()
        );
    }

    public String uniqueIndexName() {
        return UNIQUE_INDEX_NAME;
    }

    private boolean isTargetUniqueIndex(IndexInfo info) {
        return UNIQUE_INDEX_NAME.equals(info.getName())
                && info.isUnique()
                && info.getIndexFields().size() == 2
                && "citedId".equals(info.getIndexFields().get(0).getKey())
                && "citingId".equals(info.getIndexFields().get(1).getKey());
    }

    @SuppressWarnings("unchecked")
    private List<String> readIds(Object idsObject) {
        if (!(idsObject instanceof List<?> ids)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object idObj : ids) {
            if (idObj != null) {
                values.add(String.valueOf(idObj));
            }
        }
        return values;
    }

    public record DuplicatePair(String citedId, String citingId, List<String> ids) {
    }

    public record DuplicateScanResult(List<DuplicatePair> duplicatePairs) {
        public int duplicateGroupCount() {
            return duplicatePairs.size();
        }

        public int duplicateRowCount() {
            return duplicatePairs.stream().mapToInt(pair -> pair.ids().size()).sum();
        }
    }

    public record DedupeResult(int affectedPairs, int deletedRows) {
    }

    public record VerificationResult(boolean duplicatesRemoved, boolean uniqueIndexPresent) {
    }

    public record DedupeCandidate(String keptId, List<String> idsToDelete) {
    }
}
