package ro.uvt.pokedex.core.service.application;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TaskCollectionNamespaceMigrationService {

    public static final String OLD_PUBLICATION_COLLECTION = "schodardex.tasks.scopusPublicationUpdate";
    public static final String NEW_PUBLICATION_COLLECTION = "scholardex.tasks.scopusPublicationUpdate";
    public static final String OLD_CITATION_COLLECTION = "schodardex.tasks.scopusCitationsUpdate";
    public static final String NEW_CITATION_COLLECTION = "scholardex.tasks.scopusCitationsUpdate";

    private final MongoTemplate mongoTemplate;

    public TaskCollectionNamespaceMigrationService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public NamespaceReport scanNamespace(int sampleSize) {
        int safeSampleSize = Math.max(sampleSize, 0);
        CollectionReport publicationReport = scanCollection(
                OLD_PUBLICATION_COLLECTION,
                NEW_PUBLICATION_COLLECTION,
                safeSampleSize
        );
        CollectionReport citationReport = scanCollection(
                OLD_CITATION_COLLECTION,
                NEW_CITATION_COLLECTION,
                safeSampleSize
        );
        return new NamespaceReport(publicationReport, citationReport);
    }

    public ApplyResult applyMigration(boolean deleteOldAfterCopy) {
        long copiedPublicationRows = copyCollection(OLD_PUBLICATION_COLLECTION, NEW_PUBLICATION_COLLECTION);
        long copiedCitationRows = copyCollection(OLD_CITATION_COLLECTION, NEW_CITATION_COLLECTION);

        long deletedPublicationRows = 0;
        long deletedCitationRows = 0;
        if (deleteOldAfterCopy) {
            deletedPublicationRows = mongoTemplate.getCollection(OLD_PUBLICATION_COLLECTION)
                    .deleteMany(new Document()).getDeletedCount();
            deletedCitationRows = mongoTemplate.getCollection(OLD_CITATION_COLLECTION)
                    .deleteMany(new Document()).getDeletedCount();
        }

        return new ApplyResult(
                copiedPublicationRows,
                copiedCitationRows,
                deletedPublicationRows,
                deletedCitationRows
        );
    }

    private CollectionReport scanCollection(String oldCollection, String newCollection, int sampleSize) {
        MongoCollection<Document> oldColl = mongoTemplate.getCollection(oldCollection);
        MongoCollection<Document> newColl = mongoTemplate.getCollection(newCollection);
        long oldCount = oldColl.countDocuments();
        long newCount = newColl.countDocuments();
        List<String> oldOnlySampleIds = collectOldOnlySampleIds(oldColl, newColl, sampleSize);
        long oldOnlyCount = countOldOnlyRows(oldColl, newColl);
        return new CollectionReport(oldCollection, newCollection, oldCount, newCount, oldOnlyCount, oldOnlySampleIds);
    }

    private long copyCollection(String oldCollection, String newCollection) {
        MongoCollection<Document> oldColl = mongoTemplate.getCollection(oldCollection);
        MongoCollection<Document> newColl = mongoTemplate.getCollection(newCollection);

        long processed = 0;
        for (Document doc : oldColl.find()) {
            Object id = doc.get("_id");
            if (id == null) {
                continue;
            }
            newColl.replaceOne(Filters.eq("_id", id), doc, new ReplaceOptions().upsert(true));
            processed++;
        }
        return processed;
    }

    private List<String> collectOldOnlySampleIds(MongoCollection<Document> oldColl,
                                                 MongoCollection<Document> newColl,
                                                 int sampleSize) {
        if (sampleSize == 0) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Document doc : oldColl.find().limit(sampleSize * 4)) {
            Object id = doc.get("_id");
            if (id == null) {
                continue;
            }
            boolean existsInNew = newColl.countDocuments(Filters.eq("_id", id)) > 0;
            if (!existsInNew) {
                ids.add(String.valueOf(id));
            }
            if (ids.size() >= sampleSize) {
                break;
            }
        }
        return ids;
    }

    private long countOldOnlyRows(MongoCollection<Document> oldColl, MongoCollection<Document> newColl) {
        long oldOnly = 0;
        for (Document doc : oldColl.find()) {
            Object id = doc.get("_id");
            if (id == null) {
                continue;
            }
            boolean existsInNew = newColl.countDocuments(Filters.eq("_id", id)) > 0;
            if (!existsInNew) {
                oldOnly++;
            }
        }
        return oldOnly;
    }

    public record CollectionReport(
            String oldCollection,
            String newCollection,
            long oldCount,
            long newCount,
            long oldOnlyCount,
            List<String> oldOnlySampleIds
    ) {
        public boolean hasPendingOldOnlyRows() {
            return oldOnlyCount > 0;
        }
    }

    public record NamespaceReport(
            CollectionReport publicationReport,
            CollectionReport citationReport
    ) {
        public long totalOldRows() {
            return publicationReport.oldCount() + citationReport.oldCount();
        }

        public long totalNewRows() {
            return publicationReport.newCount() + citationReport.newCount();
        }

        public long totalOldOnlyRows() {
            return publicationReport.oldOnlyCount() + citationReport.oldOnlyCount();
        }

        public boolean hasPendingOldOnlyRows() {
            return totalOldOnlyRows() > 0;
        }
    }

    public record ApplyResult(
            long copiedPublicationRows,
            long copiedCitationRows,
            long deletedOldPublicationRows,
            long deletedOldCitationRows
    ) {
    }
}
