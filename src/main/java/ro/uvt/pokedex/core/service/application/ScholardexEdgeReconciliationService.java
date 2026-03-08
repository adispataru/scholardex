package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.observability.H19CanonicalMetrics;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ScholardexEdgeReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ScholardexEdgeReconciliationService.class);

    private static final String STATUS_OPEN = "OPEN";
    public static final String REASON_EDGE_ARRAY_DIVERGENCE_AMBIGUOUS = "EDGE_ARRAY_DIVERGENCE_AMBIGUOUS";
    private static final String REASON_RECONCILE = "edge-reconcile";

    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final ScholardexAuthorFactRepository authorFactRepository;
    private final ScholardexAuthorshipFactRepository authorshipFactRepository;
    private final ScholardexAuthorAffiliationFactRepository authorAffiliationFactRepository;
    private final ScholardexIdentityConflictRepository identityConflictRepository;
    private final ScholardexEdgeWriterService edgeWriterService;

    public ImportProcessingResult reconcileEdges() {
        long startedAtNanos = System.nanoTime();
        String runId = java.util.UUID.randomUUID().toString();
        ImportProcessingResult result = new ImportProcessingResult(20);
        reconcilePublicationAuthorEdges(result);
        reconcileAuthorAffiliationEdges(result);
        String outcome = result.getErrorCount() > 0 ? "failure" : "success";
        H19CanonicalMetrics.recordReconcileRun("edges", outcome, System.nanoTime() - startedAtNanos);
        log.info("H19_TRIAGE edge_reconcile runId={} batchId={} correlationId={} entity=EDGE source=CANONICAL outcome={} updated={} skipped={} errors={}",
                runId,
                "N/A",
                "N/A",
                outcome,
                result.getUpdatedCount(),
                result.getSkippedCount(),
                result.getErrorCount());
        return result;
    }

    private void reconcilePublicationAuthorEdges(ImportProcessingResult result) {
        for (ScholardexPublicationFact publication : publicationFactRepository.findAll()) {
            if (publication.getId() == null) {
                continue;
            }
            Set<String> expectedAuthorIds = distinctNonBlank(publication.getAuthorIds());
            String source = fallbackSource(publication.getSource());
            String publicationSourceRecordId = fallbackSourceRecordId(publication.getSourceRecordId(), publication.getId());
            for (String authorId : expectedAuthorIds) {
                String sourceRecordId = publicationSourceRecordId + "::author::" + normalize(authorId);
                Optional<ScholardexAuthorshipFact> existing = authorshipFactRepository
                        .findByPublicationIdAndAuthorIdAndSource(publication.getId(), authorId, source);
                if (existing.isPresent()) {
                    if (needsAuthorshipRepair(
                            existing.get(),
                            sourceRecordId,
                            publication.getSourceEventId(),
                            publication.getSourceBatchId(),
                            publication.getSourceCorrelationId()
                    )) {
                        ScholardexEdgeWriterService.EdgeWriteResult repaired = edgeWriterService.upsertAuthorshipEdge(
                                new ScholardexEdgeWriterService.EdgeWriteCommand(
                                        publication.getId(),
                                        authorId,
                                        source,
                                        sourceRecordId,
                                        publication.getSourceEventId(),
                                        publication.getSourceBatchId(),
                                        publication.getSourceCorrelationId(),
                                        fallbackLinkState(existing.get().getLinkState(), ScholardexSourceLinkService.STATE_LINKED),
                                        fallbackLinkReason(existing.get().getLinkReason(), REASON_RECONCILE),
                                        true
                                )
                        );
                        if (repaired.accepted()) {
                            result.markUpdated();
                        } else {
                            result.markError("authorship-reconcile-repair-failed publicationId=" + publication.getId() + " authorId=" + authorId);
                        }
                    }
                    continue;
                }
                ScholardexEdgeWriterService.EdgeWriteResult writeResult = edgeWriterService.upsertAuthorshipEdge(
                        new ScholardexEdgeWriterService.EdgeWriteCommand(
                                publication.getId(),
                                authorId,
                                source,
                                sourceRecordId,
                                publication.getSourceEventId(),
                                publication.getSourceBatchId(),
                                publication.getSourceCorrelationId(),
                                ScholardexSourceLinkService.STATE_LINKED,
                                REASON_RECONCILE,
                                true
                        )
                );
                if (writeResult.accepted()) {
                    result.markUpdated();
                } else {
                    result.markError("authorship-reconcile-create-failed publicationId=" + publication.getId() + " authorId=" + authorId);
                }
            }

            List<ScholardexAuthorshipFact> byPublication = authorshipFactRepository.findByPublicationId(publication.getId());
            for (ScholardexAuthorshipFact edge : byPublication) {
                if (!source.equalsIgnoreCase(fallbackSource(edge.getSource()))) {
                    openAmbiguousConflict(ScholardexEntityType.AUTHORSHIP, source, publicationSourceRecordId, publication.getId(), edge.getId());
                    result.markSkipped("authorship-ambiguous-source publicationId=" + publication.getId());
                    continue;
                }
                if (!expectedAuthorIds.contains(normalize(edge.getAuthorId()))) {
                    authorshipFactRepository.delete(edge);
                    result.markUpdated();
                }
            }
        }
    }

    private void reconcileAuthorAffiliationEdges(ImportProcessingResult result) {
        for (ScholardexAuthorFact author : authorFactRepository.findAll()) {
            if (author.getId() == null) {
                continue;
            }
            Set<String> expectedAffiliationIds = distinctNonBlank(author.getAffiliationIds());
            String source = fallbackSource(author.getSource());
            String authorSourceRecordId = fallbackSourceRecordId(author.getSourceRecordId(), author.getId());
            for (String affiliationId : expectedAffiliationIds) {
                String sourceRecordId = authorSourceRecordId + "::affiliation::" + normalize(affiliationId);
                Optional<ScholardexAuthorAffiliationFact> existing = authorAffiliationFactRepository
                        .findByAuthorIdAndAffiliationIdAndSource(author.getId(), affiliationId, source);
                if (existing.isPresent()) {
                    if (needsAuthorAffiliationRepair(
                            existing.get(),
                            sourceRecordId,
                            author.getSourceEventId(),
                            author.getSourceBatchId(),
                            author.getSourceCorrelationId()
                    )) {
                        ScholardexEdgeWriterService.EdgeWriteResult repaired = edgeWriterService.upsertAuthorAffiliationEdge(
                                new ScholardexEdgeWriterService.EdgeWriteCommand(
                                        author.getId(),
                                        affiliationId,
                                        source,
                                        sourceRecordId,
                                        author.getSourceEventId(),
                                        author.getSourceBatchId(),
                                        author.getSourceCorrelationId(),
                                        fallbackLinkState(existing.get().getLinkState(), ScholardexSourceLinkService.STATE_LINKED),
                                        fallbackLinkReason(existing.get().getLinkReason(), REASON_RECONCILE),
                                        true
                                )
                        );
                        if (repaired.accepted()) {
                            result.markUpdated();
                        } else {
                            result.markError("author-affiliation-reconcile-repair-failed authorId=" + author.getId() + " affiliationId=" + affiliationId);
                        }
                    }
                    continue;
                }
                ScholardexEdgeWriterService.EdgeWriteResult writeResult = edgeWriterService.upsertAuthorAffiliationEdge(
                        new ScholardexEdgeWriterService.EdgeWriteCommand(
                                author.getId(),
                                affiliationId,
                                source,
                                sourceRecordId,
                                author.getSourceEventId(),
                                author.getSourceBatchId(),
                                author.getSourceCorrelationId(),
                                ScholardexSourceLinkService.STATE_LINKED,
                                REASON_RECONCILE,
                                true
                        )
                );
                if (writeResult.accepted()) {
                    result.markUpdated();
                } else {
                    result.markError("author-affiliation-reconcile-create-failed authorId=" + author.getId() + " affiliationId=" + affiliationId);
                }
            }

            List<ScholardexAuthorAffiliationFact> byAuthor = authorAffiliationFactRepository.findByAuthorId(author.getId());
            for (ScholardexAuthorAffiliationFact edge : byAuthor) {
                if (!source.equalsIgnoreCase(fallbackSource(edge.getSource()))) {
                    openAmbiguousConflict(ScholardexEntityType.AUTHOR_AFFILIATION, source, authorSourceRecordId, author.getId(), edge.getId());
                    result.markSkipped("author-affiliation-ambiguous-source authorId=" + author.getId());
                    continue;
                }
                if (!expectedAffiliationIds.contains(normalize(edge.getAffiliationId()))) {
                    authorAffiliationFactRepository.delete(edge);
                    result.markUpdated();
                }
            }
        }
    }

    private void openAmbiguousConflict(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String canonicalRootId,
            String edgeId
    ) {
        if (normalize(source) == null || normalize(sourceRecordId) == null) {
            return;
        }
        ScholardexIdentityConflict conflict = identityConflictRepository
                .findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                        entityType,
                        source,
                        sourceRecordId,
                        REASON_EDGE_ARRAY_DIVERGENCE_AMBIGUOUS,
                        STATUS_OPEN
                )
                .orElseGet(ScholardexIdentityConflict::new);
        conflict.setEntityType(entityType);
        conflict.setIncomingSource(source);
        conflict.setIncomingSourceRecordId(sourceRecordId);
        conflict.setReasonCode(REASON_EDGE_ARRAY_DIVERGENCE_AMBIGUOUS);
        conflict.setStatus(STATUS_OPEN);
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (normalize(canonicalRootId) != null) {
            candidates.add(canonicalRootId);
        }
        if (normalize(edgeId) != null) {
            candidates.add(edgeId);
        }
        conflict.setCandidateCanonicalIds(new ArrayList<>(candidates));
        if (conflict.getDetectedAt() == null) {
            conflict.setDetectedAt(Instant.now());
        }
        identityConflictRepository.save(conflict);
        H19CanonicalMetrics.recordConflictCreated(entityType.name(), source, REASON_EDGE_ARRAY_DIVERGENCE_AMBIGUOUS);
    }

    private boolean needsAuthorshipRepair(
            ScholardexAuthorshipFact edge,
            String expectedSourceRecordId,
            String expectedSourceEventId,
            String expectedSourceBatchId,
            String expectedSourceCorrelationId
    ) {
        if (edge == null) {
            return false;
        }
        return normalize(edge.getId()) == null
                || normalize(edge.getSourceRecordId()) == null
                || lineageMismatch(edge.getSourceEventId(), expectedSourceEventId)
                || lineageMismatch(edge.getSourceBatchId(), expectedSourceBatchId)
                || lineageMismatch(edge.getSourceCorrelationId(), expectedSourceCorrelationId)
                || normalize(edge.getLinkState()) == null
                || normalize(edge.getLinkReason()) == null
                || edge.getCreatedAt() == null
                || edge.getUpdatedAt() == null
                || !expectedSourceRecordId.equals(normalize(edge.getSourceRecordId()));
    }

    private boolean needsAuthorAffiliationRepair(
            ScholardexAuthorAffiliationFact edge,
            String expectedSourceRecordId,
            String expectedSourceEventId,
            String expectedSourceBatchId,
            String expectedSourceCorrelationId
    ) {
        if (edge == null) {
            return false;
        }
        return normalize(edge.getId()) == null
                || normalize(edge.getSourceRecordId()) == null
                || lineageMismatch(edge.getSourceEventId(), expectedSourceEventId)
                || lineageMismatch(edge.getSourceBatchId(), expectedSourceBatchId)
                || lineageMismatch(edge.getSourceCorrelationId(), expectedSourceCorrelationId)
                || normalize(edge.getLinkState()) == null
                || normalize(edge.getLinkReason()) == null
                || edge.getCreatedAt() == null
                || edge.getUpdatedAt() == null
                || !expectedSourceRecordId.equals(normalize(edge.getSourceRecordId()));
    }

    private boolean lineageMismatch(String actual, String expected) {
        String expectedNorm = normalize(expected);
        if (expectedNorm == null) {
            return false;
        }
        return !expectedNorm.equals(normalize(actual));
    }

    private String fallbackLinkState(String linkState, String fallback) {
        String normalized = normalize(linkState);
        return normalized == null ? fallback : normalized.toUpperCase(Locale.ROOT);
    }

    private String fallbackLinkReason(String linkReason, String fallback) {
        String normalized = normalize(linkReason);
        return normalized == null ? fallback : normalized;
    }

    private Set<String> distinctNonBlank(List<String> values) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized != null) {
                out.add(normalized);
            }
        }
        return out;
    }

    private String fallbackSource(String source) {
        String normalized = normalize(source);
        return normalized == null ? "UNKNOWN" : normalized.toUpperCase(Locale.ROOT);
    }

    private String fallbackSourceRecordId(String sourceRecordId, String fallback) {
        String normalized = normalize(sourceRecordId);
        if (normalized != null) {
            return normalized;
        }
        String fallbackNormalized = normalize(fallback);
        return fallbackNormalized == null ? "unknown" : fallbackNormalized;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
