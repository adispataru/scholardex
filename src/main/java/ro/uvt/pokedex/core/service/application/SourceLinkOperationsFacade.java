package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SourceLinkOperationsFacade {

    private final MongoTemplate mongoTemplate;
    private final ScholardexSourceLinkService sourceLinkService;

    public Page<ScholardexSourceLink> findSourceLinks(
            Integer page,
            Integer size,
            String entityType,
            String source,
            String linkState,
            String sourceBatchId,
            String sourceCorrelationId,
            String sourceEventId,
            Instant updatedFrom,
            Instant updatedTo
    ) {
        Pageable pageable = PageRequest.of(normalizePage(page), normalizeSize(size), Sort.by(Sort.Direction.DESC, "updatedAt"));
        Query query = new Query().with(pageable);
        String sourceFilter = normalize(source);
        String stateFilter = normalize(linkState);
        String batchFilter = normalize(sourceBatchId);
        String correlationFilter = normalize(sourceCorrelationId);
        String eventFilter = normalize(sourceEventId);
        ScholardexEntityType parsedEntityType = parseEntityType(entityType);

        if (parsedEntityType != null) {
            query.addCriteria(Criteria.where("entityType").is(parsedEntityType));
        }
        if (sourceFilter != null) {
            query.addCriteria(Criteria.where("source").regex(sourceFilter, "i"));
        }
        if (stateFilter != null) {
            query.addCriteria(Criteria.where("linkState").regex(stateFilter, "i"));
        }
        if (batchFilter != null) {
            query.addCriteria(Criteria.where("sourceBatchId").is(batchFilter));
        }
        if (correlationFilter != null) {
            query.addCriteria(Criteria.where("sourceCorrelationId").is(correlationFilter));
        }
        if (eventFilter != null) {
            query.addCriteria(Criteria.where("sourceEventId").is(eventFilter));
        }
        Instant from = updatedFrom == null ? Instant.EPOCH : updatedFrom;
        Instant to = updatedTo == null ? Instant.parse("9999-12-31T23:59:59Z") : updatedTo;
        query.addCriteria(Criteria.where("updatedAt").gte(from).lte(to));

        List<ScholardexSourceLink> content = mongoTemplate.find(query, ScholardexSourceLink.class);
        Query countQuery = Query.of(query).limit(-1).skip(-1);
        long total = mongoTemplate.count(countQuery, ScholardexSourceLink.class);
        return new PageImpl<>(content, pageable, total);
    }

    public Optional<ScholardexSourceLink> findByKey(String entityType, String source, String sourceRecordId) {
        ScholardexEntityType parsedEntityType = parseEntityType(entityType);
        if (parsedEntityType == null) {
            return Optional.empty();
        }
        return sourceLinkService.findByKey(parsedEntityType, source, sourceRecordId);
    }

    public List<ScholardexSourceLink> findByCanonical(String entityType, String canonicalEntityId) {
        ScholardexEntityType parsedEntityType = parseEntityType(entityType);
        if (parsedEntityType == null) {
            return List.of();
        }
        return sourceLinkService.findByCanonical(parsedEntityType, canonicalEntityId);
    }

    public ScholardexSourceLinkService.ImportRepairSummary reconcileSourceLinks() {
        return sourceLinkService.reconcileLinks();
    }

    public ScholardexSourceLinkService.ReplayEligibilitySummary replayEligibilitySummary() {
        return sourceLinkService.replayEligibilitySummary();
    }

    private ScholardexEntityType parseEntityType(String value) {
        String token = normalize(value);
        if (token == null) {
            return null;
        }
        try {
            return ScholardexEntityType.valueOf(token.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private int normalizePage(Integer page) {
        if (page == null || page < 0) {
            return 0;
        }
        return page;
    }

    private int normalizeSize(Integer size) {
        if (size == null || size <= 0) {
            return 20;
        }
        return Math.min(size, 200);
    }
}

