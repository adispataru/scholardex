package ro.uvt.pokedex.core.service.application;

import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class CitationUniquenessMigrationService {

    private static final String UNIQUE_INDEX_NAME = "uniq_scholardex_citation_edge";

    private final MongoTemplate mongoTemplate;
    private final ScholardexCitationFactRepository scholardexCitationFactRepository;

    public CitationUniquenessMigrationService(MongoTemplate mongoTemplate,
                                              ScholardexCitationFactRepository scholardexCitationFactRepository) {
        this.mongoTemplate = mongoTemplate;
        this.scholardexCitationFactRepository = scholardexCitationFactRepository;
    }

    public DuplicateScanResult scanDuplicates() {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.group("citedPublicationId", "citingPublicationId", "source")
                        .count().as("count")
                        .push("_id").as("ids"),
                Aggregation.match(org.springframework.data.mongodb.core.query.Criteria.where("count").gt(1))
        );

        List<Document> grouped = mongoTemplate.aggregate(
                aggregation,
                ScholardexCitationFact.class,
                Document.class
        ).getMappedResults();

        List<DuplicatePair> duplicates = new ArrayList<>();
        for (Document group : grouped) {
            Document pair = group.get("_id", Document.class);
            if (pair == null) {
                continue;
            }
            String citedId = pair.getString("citedPublicationId");
            String citingId = pair.getString("citingPublicationId");
            String source = pair.getString("source");
            List<String> ids = readIds(group.get("ids"));
            duplicates.add(new DuplicatePair(citedId, citingId, source, ids));
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
            scholardexCitationFactRepository.deleteAllById(candidate.idsToDelete());
            affectedPairs++;
            deletedRows += candidate.idsToDelete().size();
        }
        return new DedupeResult(affectedPairs, deletedRows);
    }

    public void ensureUniqueIndex() {
        mongoTemplate.indexOps(ScholardexCitationFact.class).createIndex(
                new Index()
                        .on("citedPublicationId", Sort.Direction.ASC)
                        .on("citingPublicationId", Sort.Direction.ASC)
                        .on("source", Sort.Direction.ASC)
                        .unique()
                        .named(UNIQUE_INDEX_NAME)
        );
    }

    public VerificationResult verifyPostConditions() {
        DuplicateScanResult afterScan = scanDuplicates();
        List<IndexInfo> indexInfo = mongoTemplate.indexOps(ScholardexCitationFact.class).getIndexInfo();
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
                && info.getIndexFields().size() == 3
                && "citedPublicationId".equals(info.getIndexFields().getFirst().getKey())
                && "citingPublicationId".equals(info.getIndexFields().get(1).getKey())
                && "source".equals(info.getIndexFields().get(2).getKey());
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

    public record DuplicatePair(String citedId, String citingId, String source, List<String> ids) {
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
