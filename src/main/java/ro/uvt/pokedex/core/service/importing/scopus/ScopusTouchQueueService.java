package ro.uvt.pokedex.core.service.importing.scopus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationTouch;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorTouch;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationTouch;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationTouch;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationTouchRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorTouchRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationTouchRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumTouchRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationTouchRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class ScopusTouchQueueService {

    private static final int CONSUME_LIMIT = 1_000;
    private static final int RECENTLY_TOUCHED_MAX = 500_000;

    private final ScopusAffiliationTouchRepository affiliationTouchRepository;
    private final ScopusAuthorTouchRepository authorTouchRepository;
    private final ScopusForumTouchRepository forumTouchRepository;
    private final ScopusPublicationTouchRepository publicationTouchRepository;
    private final ScopusCitationTouchRepository citationTouchRepository;
    private final ScopusImportEventRepository importEventRepository;
    private final ObjectMapper objectMapper;
    private final MongoTemplate mongoTemplate;
    private final Set<String> recentlyTouched = ConcurrentHashMap.newKeySet();

    private static final String TOUCH_COLLECTION_AFFILIATION = "scopus.affiliation_touch_queue";
    private static final String TOUCH_COLLECTION_AUTHOR = "scopus.author_touch_queue";
    private static final String TOUCH_COLLECTION_FORUM = "scopus.forum_touch_queue";
    private static final String TOUCH_COLLECTION_PUBLICATION = "scopus.publication_touch_queue";
    private static final String TOUCH_COLLECTION_CITATION = "scopus.citation_touch_queue";

    public void touchFromIngestPayload(ScopusImportEntityType entityType, String source, String payload) {
        if (entityType == null || isBlank(source) || isBlank(payload)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (entityType == ScopusImportEntityType.PUBLICATION) {
                touchPublicationPayload(source, root);
            } else if (entityType == ScopusImportEntityType.CITATION) {
                touchCitationPayload(source, root);
            }
        } catch (Exception ignored) {
        }
    }

    public void touchFromIngestPayloadObject(ScopusImportEntityType entityType, String source, Object payloadObject) {
        if (entityType == null || isBlank(source) || payloadObject == null) {
            return;
        }
        if (payloadObject instanceof Map<?, ?> payloadMap) {
            if (entityType == ScopusImportEntityType.PUBLICATION) {
                touchPublicationPayloadMap(source, payloadMap);
            } else if (entityType == ScopusImportEntityType.CITATION) {
                touchCitationPayloadMap(source, payloadMap);
            }
            return;
        }
        touchFromIngestPayload(entityType, source, String.valueOf(payloadObject));
    }

    public void touchCitationEdgesBatch(String source, List<CitationEdge> edges) {
        if (isBlank(source) || edges == null || edges.isEmpty()) {
            return;
        }
        maybeResetRecentlyTouched();
        List<UpdateOneModel<org.bson.Document>> ops = new ArrayList<>(edges.size());
        Set<String> localDedupe = new HashSet<>(edges.size() * 2);
        Instant now = Instant.now();
        for (CitationEdge edge : edges) {
            if (edge == null || isBlank(edge.citedEid()) || isBlank(edge.citingEid())) {
                continue;
            }
            String dedupeKey = cacheKey("citation", source, edge.citedEid(), edge.citingEid());
            if (!localDedupe.add(dedupeKey)) {
                continue;
            }
            if (!recentlyTouched.add(dedupeKey)) {
                continue;
            }
            org.bson.Document filter = new org.bson.Document()
                    .append("source", source)
                    .append("citedEid", edge.citedEid())
                    .append("citingEid", edge.citingEid());
            org.bson.Document setOnInsert = new org.bson.Document()
                    .append("source", source)
                    .append("citedEid", edge.citedEid())
                    .append("citingEid", edge.citingEid())
                    .append("touchedAt", now);
            ops.add(new UpdateOneModel<>(
                    filter,
                    new org.bson.Document("$setOnInsert", setOnInsert),
                    new UpdateOptions().upsert(true)
            ));
        }
        if (ops.isEmpty()) {
            return;
        }
        mongoTemplate.getCollection(TOUCH_COLLECTION_CITATION)
                .bulkWrite(ops, new BulkWriteOptions().ordered(false));
    }

    public void rebuildFromImportEvents() {
        clearAll();
        for (ScopusImportEvent event : importEventRepository.findAll()) {
            if (event == null || event.getEntityType() == null || isBlank(event.getSource()) || isBlank(event.getPayload())) {
                continue;
            }
            touchFromIngestPayload(event.getEntityType(), event.getSource(), event.getPayload());
        }
    }

    public List<String> consumeAffiliationIds(boolean drain) {
        List<ScopusAffiliationTouch> touches = affiliationTouchRepository.findTop1000ByOrderByTouchedAtAsc();
        if (touches.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        List<String> touchIds = new ArrayList<>(touches.size());
        for (ScopusAffiliationTouch touch : touches) {
            touchIds.add(touch.getId());
            if (!isBlank(touch.getAfid())) {
                ids.add(touch.getAfid());
            }
        }
        if (drain) {
            affiliationTouchRepository.deleteAllById(touchIds);
        }
        return new ArrayList<>(ids);
    }

    public List<String> consumeAuthorIds(boolean drain) {
        List<ScopusAuthorTouch> touches = authorTouchRepository.findTop1000ByOrderByTouchedAtAsc();
        if (touches.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        List<String> touchIds = new ArrayList<>(touches.size());
        for (ScopusAuthorTouch touch : touches) {
            touchIds.add(touch.getId());
            if (!isBlank(touch.getAuthorId())) {
                ids.add(touch.getAuthorId());
            }
        }
        if (drain) {
            authorTouchRepository.deleteAllById(touchIds);
        }
        return new ArrayList<>(ids);
    }

    public List<String> consumePublicationIds(boolean drain) {
        List<ScopusPublicationTouch> touches = publicationTouchRepository.findTop1000ByOrderByTouchedAtAsc();
        if (touches.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        List<String> touchIds = new ArrayList<>(touches.size());
        for (ScopusPublicationTouch touch : touches) {
            touchIds.add(touch.getId());
            if (!isBlank(touch.getEid())) {
                ids.add(touch.getEid());
            }
        }
        if (drain) {
            publicationTouchRepository.deleteAllById(touchIds);
        }
        return new ArrayList<>(ids);
    }

    public List<CitationEdge> consumeCitationEdges(boolean drain) {
        List<ScopusCitationTouch> touches = citationTouchRepository.findTop1000ByOrderByTouchedAtAsc();
        if (touches.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<CitationEdge> edges = new LinkedHashSet<>();
        List<String> touchIds = new ArrayList<>(touches.size());
        for (ScopusCitationTouch touch : touches) {
            touchIds.add(touch.getId());
            if (!isBlank(touch.getCitedEid()) && !isBlank(touch.getCitingEid())) {
                edges.add(new CitationEdge(touch.getCitedEid(), touch.getCitingEid()));
            }
        }
        if (drain) {
            citationTouchRepository.deleteAllById(touchIds);
        }
        return new ArrayList<>(edges);
    }

    public TouchBacklog backlog() {
        return new TouchBacklog(
                publicationTouchRepository.count(),
                authorTouchRepository.count(),
                affiliationTouchRepository.count(),
                forumTouchRepository.count(),
                citationTouchRepository.count()
        );
    }

    public void clearAll() {
        publicationTouchRepository.deleteAll();
        authorTouchRepository.deleteAll();
        affiliationTouchRepository.deleteAll();
        forumTouchRepository.deleteAll();
        citationTouchRepository.deleteAll();
        recentlyTouched.clear();
    }

    private void touchPublicationPayload(String source, JsonNode payload) {
        String eid = text(payload, "eid");
        if (!isBlank(eid)) {
            touchPublication(source, eid);
        }
        String sourceId = text(payload, "source_id");
        if (!isBlank(sourceId)) {
            touchForum(source, sourceId);
        }
        for (String authorId : splitSemicolon(text(payload, "author_ids"))) {
            if (!isBlank(authorId)) {
                touchAuthor(source, authorId);
            }
        }
        for (String affiliationId : splitSemicolon(text(payload, "afid"))) {
            if (!isBlank(affiliationId)) {
                touchAffiliation(source, affiliationId);
            }
        }
    }

    private void touchCitationPayload(String source, JsonNode payload) {
        String citedEid = text(payload, "citedEid");
        String citingEid = text(payload, "citingEid");
        if (isBlank(citedEid) || isBlank(citingEid)) {
            return;
        }
        upsertIfFirstSeen(TOUCH_COLLECTION_CITATION,
                cacheKey("citation", source, citedEid, citingEid),
                Criteria.where("source").is(source).and("citedEid").is(citedEid).and("citingEid").is(citingEid),
                Map.of("source", source, "citedEid", citedEid, "citingEid", citingEid));
    }

    private void touchPublication(String source, String eid) {
        upsertIfFirstSeen(TOUCH_COLLECTION_PUBLICATION,
                cacheKey("publication", source, eid),
                Criteria.where("source").is(source).and("eid").is(eid),
                Map.of("source", source, "eid", eid));
    }

    private void touchAuthor(String source, String authorId) {
        upsertIfFirstSeen(TOUCH_COLLECTION_AUTHOR,
                cacheKey("author", source, authorId),
                Criteria.where("source").is(source).and("authorId").is(authorId),
                Map.of("source", source, "authorId", authorId));
    }

    private void touchAffiliation(String source, String afid) {
        upsertIfFirstSeen(TOUCH_COLLECTION_AFFILIATION,
                cacheKey("affiliation", source, afid),
                Criteria.where("source").is(source).and("afid").is(afid),
                Map.of("source", source, "afid", afid));
    }

    private void touchForum(String source, String sourceId) {
        upsertIfFirstSeen(TOUCH_COLLECTION_FORUM,
                cacheKey("forum", source, sourceId),
                Criteria.where("source").is(source).and("sourceId").is(sourceId),
                Map.of("source", source, "sourceId", sourceId));
    }

    private void touchPublicationPayloadMap(String source, Map<?, ?> payload) {
        String eid = textValue(payload.get("eid"));
        if (!isBlank(eid)) {
            touchPublication(source, eid);
        }
        String sourceId = textValue(payload.get("source_id"));
        if (!isBlank(sourceId)) {
            touchForum(source, sourceId);
        }
        for (String authorId : splitSemicolon(textValue(payload.get("author_ids")))) {
            if (!isBlank(authorId)) {
                touchAuthor(source, authorId);
            }
        }
        for (String affiliationId : splitSemicolon(textValue(payload.get("afid")))) {
            if (!isBlank(affiliationId)) {
                touchAffiliation(source, affiliationId);
            }
        }
    }

    private void touchCitationPayloadMap(String source, Map<?, ?> payload) {
        String citedEid = textValue(payload.get("citedEid"));
        String citingEid = textValue(payload.get("citingEid"));
        if (isBlank(citedEid) || isBlank(citingEid)) {
            return;
        }
        upsertIfFirstSeen(TOUCH_COLLECTION_CITATION,
                cacheKey("citation", source, citedEid, citingEid),
                Criteria.where("source").is(source).and("citedEid").is(citedEid).and("citingEid").is(citingEid),
                Map.of("source", source, "citedEid", citedEid, "citingEid", citingEid));
    }

    private String textValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof JsonNode node) {
            if (node.isMissingNode() || node.isNull()) {
                return "";
            }
            return node.asText("").trim();
        }
        return String.valueOf(value).trim();
    }

    private void upsertIfFirstSeen(String collection, String dedupeKey, Criteria criteria, Map<String, Object> onInsertValues) {
        maybeResetRecentlyTouched();
        if (!recentlyTouched.add(dedupeKey)) {
            return;
        }
        Query query = Query.query(criteria);
        Update update = new Update().setOnInsert("touchedAt", Instant.now());
        for (Map.Entry<String, Object> entry : onInsertValues.entrySet()) {
            update.setOnInsert(entry.getKey(), entry.getValue());
        }
        mongoTemplate.upsert(query, update, collection);
    }

    private void maybeResetRecentlyTouched() {
        if (recentlyTouched.size() > RECENTLY_TOUCHED_MAX) {
            recentlyTouched.clear();
        }
    }

    private String cacheKey(String type, String source, String part1) {
        return type + "|" + source + "|" + part1;
    }

    private String cacheKey(String type, String source, String part1, String part2) {
        return type + "|" + source + "|" + part1 + "|" + part2;
    }

    private String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return "";
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        return value.asText("").trim();
    }

    private List<String> splitSemicolon(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        String[] parts = value.split(";");
        List<String> out = new ArrayList<>(parts.length);
        for (String part : parts) {
            String token = normalize(part);
            if (token != null) {
                out.add(token);
            }
        }
        return out;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public record CitationEdge(String citedEid, String citingEid) {
    }

    public record TouchBacklog(
            long publications,
            long authors,
            long affiliations,
            long forums,
            long citations
    ) {
        public boolean isEmpty() {
            return publications == 0 && authors == 0 && affiliations == 0 && forums == 0 && citations == 0;
        }
    }
}
