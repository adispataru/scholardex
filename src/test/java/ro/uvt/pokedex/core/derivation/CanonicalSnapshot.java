package ro.uvt.pokedex.core.derivation;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * H75 Stage 0 — the differential validation harness. Snapshots the canonical (stage-3) Mongo collections into a
 * comparable, run-invariant form so V2's output can be diffed against V1's: each collection becomes a
 * natural-key → canonical-JSON map, with volatile lineage/timestamp fields stripped and generated ObjectId {@code _id}
 * dropped for edge/link/conflict rows (entity facts keep their string {@code _id}, which IS the canonical identity).
 *
 * <p>Calibrated by {@code CanonicalSnapshotHarnessTest}, which asserts two V1 runs on the same fixture snapshot
 * identically (so if a volatile field is missed here, that determinism test fails and names the field).
 */
public final class CanonicalSnapshot {

    private CanonicalSnapshot() {
    }

    /** A canonical collection + how to form its run-invariant natural key. {@code idIsCanonical} = keep the string {@code _id}. */
    public record CollectionSpec(String collection, List<String> naturalKey, boolean idIsCanonical) {
    }

    /** The 10 derived collections V2 owns (the output contract). Order is stable for readable diffs. */
    public static final List<CollectionSpec> CANONICAL_COLLECTIONS = List.of(
            new CollectionSpec("scholardex.publication_facts", List.of("_id"), true),
            new CollectionSpec("scholardex.author_facts", List.of("_id"), true),
            new CollectionSpec("scholardex.affiliation_facts", List.of("_id"), true),
            new CollectionSpec("scholardex.forum_facts", List.of("_id"), true),
            new CollectionSpec("scholardex.authorship_facts", List.of("publicationId", "authorId", "source"), false),
            new CollectionSpec("scholardex.author_affiliation_facts", List.of("authorId", "affiliationId", "source"), false),
            new CollectionSpec("scholardex.publication_author_affiliation_facts",
                    List.of("publicationId", "authorId", "affiliationId", "source"), false),
            new CollectionSpec("scholardex.citation_facts", List.of("citedPublicationId", "citingPublicationId"), false),
            new CollectionSpec("scholardex.source_links", List.of("entityType", "source", "sourceRecordId"), false),
            new CollectionSpec("scholardex.identity_conflicts",
                    List.of("entityType", "incomingSource", "incomingSourceRecordId", "reasonCode", "status"), false));

    /** Fields that legitimately vary run-to-run (timestamps + per-run batch/event lineage) — excluded from the diff. */
    private static final Set<String> VOLATILE = Set.of(
            "createdAt", "updatedAt", "linkedAt", "detectedAt", "resolvedAt", "resolvedBy",
            "lastMaterializedAt", "lastPayloadHash", "builderVersion",
            "sourceEventId", "sourceBatchId", "sourceCorrelationId");

    /** collection → (naturalKey → canonical-JSON of the row, volatile fields stripped). */
    public static Map<String, Map<String, String>> snapshot(MongoTemplate mongoTemplate) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (CollectionSpec spec : CANONICAL_COLLECTIONS) {
            Map<String, String> rows = new TreeMap<>();
            for (Document doc : mongoTemplate.getCollection(spec.collection()).find()) {
                String key = spec.naturalKey().stream()
                        .map(f -> String.valueOf(doc.get(f)))
                        .collect(Collectors.joining("|"));
                Document clean = new Document(doc);
                VOLATILE.forEach(clean::remove);
                if (!spec.idIsCanonical()) {
                    clean.remove("_id");
                }
                rows.put(key, canonicalJson(clean));
            }
            out.put(spec.collection(), rows);
        }
        return out;
    }

    /** Per-collection human-readable differences (only-in-a / only-in-b / changed). Empty list ⇒ identical. */
    public static List<String> diff(Map<String, Map<String, String>> a, Map<String, Map<String, String>> b) {
        List<String> report = new ArrayList<>();
        for (CollectionSpec spec : CANONICAL_COLLECTIONS) {
            Map<String, String> ra = a.getOrDefault(spec.collection(), Map.of());
            Map<String, String> rb = b.getOrDefault(spec.collection(), Map.of());
            for (String key : ra.keySet()) {
                if (!rb.containsKey(key)) {
                    report.add(spec.collection() + " ONLY_IN_A " + key);
                } else if (!ra.get(key).equals(rb.get(key))) {
                    report.add(spec.collection() + " CHANGED " + key + "\n  A=" + ra.get(key) + "\n  B=" + rb.get(key));
                }
            }
            for (String key : rb.keySet()) {
                if (!ra.containsKey(key)) {
                    report.add(spec.collection() + " ONLY_IN_B " + key);
                }
            }
        }
        return report;
    }

    /** collection → row count, for quick golden-count assertions. */
    public static Map<String, Integer> counts(Map<String, Map<String, String>> snapshot) {
        Map<String, Integer> out = new LinkedHashMap<>();
        snapshot.forEach((coll, rows) -> out.put(coll, rows.size()));
        return out;
    }

    /** Deterministic serialization: sort object keys; preserve array order (it is semantic, e.g. authorIds). */
    @SuppressWarnings("unchecked")
    private static String canonicalJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Document doc) {
            return doc.keySet().stream().sorted()
                    .map(k -> "\"" + k + "\":" + canonicalJson(doc.get(k)))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(CanonicalSnapshot::canonicalJson).collect(Collectors.joining(",", "[", "]"));
        }
        if (value instanceof Map<?, ?> map) {
            return ((Map<String, Object>) map).entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> "\"" + e.getKey() + "\":" + canonicalJson(e.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        return "\"" + value + "\"";
    }
}
