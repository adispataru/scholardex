package ro.uvt.pokedex.core.service.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * The set of Mongo collections this application owns, and a guard against wiping anything else
 * (H54.6 / H54.1 safety rule).
 *
 * <p>The local/dev Mongo is shared with other applications (a curriculum app {@code planuri.*}, a
 * skills/occupation taxonomy, an exam system) plus orphaned legacy collections. Any destructive
 * rebuild must operate ONLY on collections this app owns — never {@code dropDatabase}, never a
 * foreign collection. See {@code docs/data-ownership-inventory.md}.
 *
 * <p>The owned set is derived from the {@code @Document} entities known to the mapping context (the
 * same source of truth the index reconciler uses), so it cannot drift from the model layer.
 */
@Component
public class OwnedCollectionRegistry {

    private final Set<String> ownedCollections;

    @Autowired
    public OwnedCollectionRegistry(MongoMappingContext mappingContext) {
        this.ownedCollections = mappingContext.getPersistentEntities().stream()
                .filter(entity -> entity.findAnnotation(Document.class) != null)
                .map(MongoPersistentEntity::getCollection)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Test constructor: supply the owned set directly (production derives it from the mapping context). */
    OwnedCollectionRegistry(Set<String> ownedCollections) {
        this.ownedCollections = Set.copyOf(ownedCollections);
    }

    public boolean isOwned(String collection) {
        return ownedCollections.contains(collection);
    }

    public Set<String> ownedCollections() {
        return ownedCollections;
    }

    /**
     * Fail fast if {@code collection} is not owned by this app — the executable form of the
     * "never touch a foreign collection" rule. Any destructive (wipe/rebuild) path should gate on
     * this before deleting.
     */
    public void assertWipeable(String collection) {
        if (!isOwned(collection)) {
            throw new IllegalArgumentException(
                    "Refusing to wipe collection not owned by this application: '" + collection
                            + "'. Owned collections are derived from @Document models; foreign/orphaned "
                            + "collections (e.g. planuri.*, skills, exam system) must never be wiped.");
        }
    }

    public void assertAllWipeable(Iterable<String> collections) {
        for (String collection : collections) {
            assertWipeable(collection);
        }
    }
}
