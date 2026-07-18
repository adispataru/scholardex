package ro.uvt.pokedex.core.config;

import com.mongodb.MongoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns unique/declared index lifecycle at startup, replacing Spring's create-only
 * {@code auto-index-creation}. For every {@code @Document} entity, creates each declared index and
 * — crucially — reconciles <em>evolution</em>: if a declared index changed shape since it was last
 * created, the stale same-named index is dropped and rebuilt instead of crashing startup.
 *
 * <p>Why this exists: {@code auto-index-creation} is create-only and runs eagerly during context
 * init, so any index spec change (e.g. {@code sparse} → {@code partialFilter}, or a key change)
 * aborts startup with {@code IndexKeySpecsConflict}/{@code IndexOptionsConflict} and there is no
 * hook to reconcile first. This component made startup resilient to declared-index evolution
 * (H54.2).
 *
 * <p>Conflict policy:
 * <ul>
 *   <li><b>86 IndexKeySpecsConflict</b> (same name, different spec): the declared index evolved —
 *       drop the stale same-named index and recreate. Safe: only ever drops an index we declare,
 *       by name.</li>
 *   <li><b>85 IndexOptionsConflict</b> (same key, different name): the declared index collides with
 *       a differently-named, externally-owned index (per H54.2 convention, non-unique performance
 *       indexes are owned by the maintenance services). Do <b>not</b> drop — log and skip; resolve
 *       at the declaration level.</li>
 *   <li><b>11000 DuplicateKey</b> (real duplicate data): a unique index cannot be enforced. Recorded
 *       and rethrown at the end so startup fails loudly with the full list of integrity problems.</li>
 * </ul>
 *
 * <p>Note: this only creates/repairs indexes that are currently declared. It never drops an index
 * that is not declared (so maintenance-owned and orphaned-but-removed indexes are left untouched).
 */
@Component
public class MongoIndexReconciler implements SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger(MongoIndexReconciler.class);

    private static final int CODE_INDEX_OPTIONS_CONFLICT = 85;     // same key, different name
    private static final int CODE_INDEX_KEY_SPECS_CONFLICT = 86;   // same name, different spec
    private static final int CODE_DUPLICATE_KEY = 11000;           // unique violation in data

    private final MongoTemplate mongoTemplate;
    private final MongoMappingContext mappingContext;

    public MongoIndexReconciler(MongoTemplate mongoTemplate, MongoMappingContext mappingContext) {
        this.mongoTemplate = mongoTemplate;
        this.mappingContext = mappingContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        IndexResolver resolver = new MongoPersistentEntityIndexResolver(mappingContext);
        List<String> integrityFailures = new ArrayList<>();
        int created = 0;
        int evolved = 0;

        for (MongoPersistentEntity<?> entity : mappingContext.getPersistentEntities()) {
            if (entity.findAnnotation(Document.class) == null) {
                continue;
            }
            Class<?> type = entity.getType();
            IndexOperations ops;
            try {
                ops = mongoTemplate.indexOps(type);
            } catch (RuntimeException ex) {
                LOG.warn("Skipping index reconcile for {} (cannot resolve index ops): {}",
                        type.getSimpleName(), ex.getMessage());
                continue;
            }

            for (IndexDefinition def : resolver.resolveIndexFor(type)) {
                String name = String.valueOf(def.getIndexOptions().get("name"));
                String coll = entity.getCollection();
                try {
                    ops.createIndex(def);
                    created++;
                } catch (RuntimeException ex) {
                    if (isConnectivityFailure(ex)) {
                        // No database, no reconciliation: context-load smoke tests run without
                        // Mongo, and on the cluster the app pod can start before Mongo is ready.
                        // Failing the whole context here helps nobody — indexes are re-ensured on
                        // the next boot with a reachable database.
                        LOG.warn("Mongo unreachable during index reconciliation ({}); skipping — "
                                + "indexes will be ensured on the next start.", ex.getMessage());
                        return;
                    }
                    int code = mongoErrorCode(ex);
                    if (code == CODE_INDEX_KEY_SPECS_CONFLICT) {
                        // Declared index evolved: drop the stale same-named index and rebuild.
                        try {
                            LOG.warn("Index '{}' on {} drifted from its declaration; dropping and recreating.",
                                    name, coll);
                            ops.dropIndex(name);
                            ops.createIndex(def);
                            evolved++;
                        } catch (RuntimeException retryEx) {
                            int retryCode = mongoErrorCode(retryEx);
                            if (retryCode == CODE_DUPLICATE_KEY) {
                                integrityFailures.add(coll + " :: " + name
                                        + " — duplicate data prevents unique index (after drop/recreate)");
                            } else {
                                integrityFailures.add(coll + " :: " + name
                                        + " — recreate failed: " + retryEx.getMessage());
                            }
                        }
                    } else if (code == CODE_INDEX_OPTIONS_CONFLICT) {
                        LOG.warn("Declared index '{}' on {} collides by key with a differently-named "
                                + "existing index (code 85). Leaving the existing index in place; resolve "
                                + "the duplicate declaration at the model level.", name, coll);
                    } else if (code == CODE_DUPLICATE_KEY) {
                        integrityFailures.add(coll + " :: " + name + " — duplicate data prevents unique index");
                    } else {
                        integrityFailures.add(coll + " :: " + name + " — " + ex.getMessage());
                    }
                }
            }
        }

        if (!integrityFailures.isEmpty()) {
            throw new IllegalStateException("Index reconciliation failed for "
                    + integrityFailures.size() + " index(es):\n  - "
                    + String.join("\n  - ", integrityFailures));
        }
        LOG.info("Mongo index reconciliation complete: {} index(es) ensured, {} evolved (dropped & rebuilt).",
                created, evolved);
    }

    /** Connection-level failure (server unreachable), as opposed to a server-reported index error. */
    private static boolean isConnectivityFailure(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof com.mongodb.MongoTimeoutException
                    || cur instanceof com.mongodb.MongoSocketException
                    || cur instanceof java.net.ConnectException) {
                return true;
            }
        }
        return false;
    }

    /** Walk the cause chain for a MongoDB server error code, or -1 if none. */
    private static int mongoErrorCode(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof MongoException mongo) {
                return mongo.getCode();
            }
        }
        return -1;
    }
}
