package ro.uvt.pokedex.core.service.application;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import ro.uvt.pokedex.core.repository.support.MongoIntegrationTestBase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataMongoTest
@Import(TaskCollectionNamespaceMigrationService.class)
class TaskCollectionNamespaceMigrationServiceIntegrationTest extends MongoIntegrationTestBase {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private TaskCollectionNamespaceMigrationService migrationService;

    @BeforeEach
    void cleanCollections() {
        mongoTemplate.getCollection(TaskCollectionNamespaceMigrationService.OLD_PUBLICATION_COLLECTION).deleteMany(new Document());
        mongoTemplate.getCollection(TaskCollectionNamespaceMigrationService.NEW_PUBLICATION_COLLECTION).deleteMany(new Document());
        mongoTemplate.getCollection(TaskCollectionNamespaceMigrationService.OLD_CITATION_COLLECTION).deleteMany(new Document());
        mongoTemplate.getCollection(TaskCollectionNamespaceMigrationService.NEW_CITATION_COLLECTION).deleteMany(new Document());
    }

    @Test
    void scanNamespaceDetectsPendingOldCollectionRows() {
        mongoTemplate.getCollection(TaskCollectionNamespaceMigrationService.OLD_PUBLICATION_COLLECTION)
                .insertOne(taskDoc("pub-1", "u1"));
        mongoTemplate.getCollection(TaskCollectionNamespaceMigrationService.OLD_CITATION_COLLECTION)
                .insertOne(taskDoc("cit-1", "u2"));

        TaskCollectionNamespaceMigrationService.NamespaceReport report = migrationService.scanNamespace(10);

        assertTrue(report.hasPendingOldOnlyRows());
        assertEquals(2, report.totalOldOnlyRows());
        assertFalse(report.publicationReport().oldOnlySampleIds().isEmpty());
    }

    @Test
    void applyMigrationCopiesRowsAndIsIdempotent() {
        mongoTemplate.getCollection(TaskCollectionNamespaceMigrationService.OLD_PUBLICATION_COLLECTION)
                .insertOne(taskDoc("pub-1", "u1"));
        mongoTemplate.getCollection(TaskCollectionNamespaceMigrationService.OLD_CITATION_COLLECTION)
                .insertOne(taskDoc("cit-1", "u2"));

        TaskCollectionNamespaceMigrationService.ApplyResult firstApply = migrationService.applyMigration(false);
        TaskCollectionNamespaceMigrationService.NamespaceReport afterFirst = migrationService.scanNamespace(10);
        TaskCollectionNamespaceMigrationService.ApplyResult secondApply = migrationService.applyMigration(false);
        TaskCollectionNamespaceMigrationService.NamespaceReport afterSecond = migrationService.scanNamespace(10);

        assertEquals(1, firstApply.copiedPublicationRows());
        assertEquals(1, firstApply.copiedCitationRows());
        assertEquals(1, secondApply.copiedPublicationRows());
        assertEquals(1, secondApply.copiedCitationRows());
        assertFalse(afterFirst.hasPendingOldOnlyRows());
        assertFalse(afterSecond.hasPendingOldOnlyRows());
        assertEquals(1, mongoTemplate.getCollection(TaskCollectionNamespaceMigrationService.NEW_PUBLICATION_COLLECTION).countDocuments());
        assertEquals(1, mongoTemplate.getCollection(TaskCollectionNamespaceMigrationService.NEW_CITATION_COLLECTION).countDocuments());
    }

    @Test
    void applyMigrationCanDeleteOldCollectionsWhenEnabled() {
        mongoTemplate.getCollection(TaskCollectionNamespaceMigrationService.OLD_PUBLICATION_COLLECTION)
                .insertOne(taskDoc("pub-1", "u1"));
        mongoTemplate.getCollection(TaskCollectionNamespaceMigrationService.OLD_CITATION_COLLECTION)
                .insertOne(taskDoc("cit-1", "u2"));

        TaskCollectionNamespaceMigrationService.ApplyResult applyResult = migrationService.applyMigration(true);
        TaskCollectionNamespaceMigrationService.NamespaceReport report = migrationService.scanNamespace(10);

        assertEquals(1, applyResult.deletedOldPublicationRows());
        assertEquals(1, applyResult.deletedOldCitationRows());
        assertEquals(0, report.publicationReport().oldCount());
        assertEquals(0, report.citationReport().oldCount());
        assertFalse(report.hasPendingOldOnlyRows());
    }

    private static Document taskDoc(String id, String initiator) {
        Document document = new Document();
        document.put("_id", id);
        document.put("initiator", initiator);
        document.put("status", "IN_PROGRESS");
        document.put("scopusId", "s-" + id);
        return document;
    }
}
