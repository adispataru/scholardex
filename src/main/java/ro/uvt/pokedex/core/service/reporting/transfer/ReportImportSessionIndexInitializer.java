package ro.uvt.pokedex.core.service.reporting.transfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportImportSession;

@Component
public class ReportImportSessionIndexInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(ReportImportSessionIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public ReportImportSessionIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        IndexOperations ops = mongoTemplate.indexOps(ReportImportSession.class);
        IndexResolver resolver = new MongoPersistentEntityIndexResolver(mongoTemplate.getConverter().getMappingContext());
        resolver.resolveIndexFor(ReportImportSession.class).forEach(def -> {
            ops.createIndex(def);
            LOG.debug("Ensured ReportImportSession index: {}", def.getIndexKeys());
        });
    }
}
