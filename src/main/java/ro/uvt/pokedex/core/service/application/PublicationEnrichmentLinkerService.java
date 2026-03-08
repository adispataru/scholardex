package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationLinkConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationLinkConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class PublicationEnrichmentLinkerService {

    private static final String KEY_WOS = "wosId";
    private static final String KEY_SCHOLAR = "googleScholarId";

    private static final String CONFLICT_KEY_ASSIGNED = "ENRICHMENT_KEY_ALREADY_ASSIGNED";
    private static final String CONFLICT_AMBIGUOUS_DOI = "AMBIGUOUS_DOI_MATCH";
    private static final String CONFLICT_TARGET_HAS_OTHER_KEY = "TARGET_HAS_DIFFERENT_ENRICHMENT_KEY";
    private static final String STATUS_OPEN = "OPEN";

    private static final Pattern DOI_URL_PREFIX = Pattern.compile("^https?://(dx\\.)?doi\\.org/", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOI_PREFIX = Pattern.compile("^doi:", Pattern.CASE_INSENSITIVE);

    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final ScholardexSourceLinkRepository sourceLinkRepository;
    private final ScholardexIdentityConflictRepository identityConflictRepository;
    private final PublicationLinkConflictRepository conflictRepository;

    public LinkResult linkWosEnrichment(Publication publication, String source, String linkerVersion, String linkerRunId) {
        if (publication == null) {
            return LinkResult.invalid("null-publication");
        }
        String incomingWosId = normalizeBlank(publication.getWosId());
        if (incomingWosId == null || Publication.NON_WOS_ID.equals(incomingWosId)) {
            return LinkResult.skipped("non-wos-or-empty");
        }

        Resolution resolution = resolveTarget(publication);
        if (resolution.state == LinkState.CONFLICT) {
            saveConflict(KEY_WOS, incomingWosId, source, linkerVersion, linkerRunId, publication, null,
                    CONFLICT_AMBIGUOUS_DOI, resolution.candidateIds);
            return new LinkResult(LinkState.CONFLICT, CONFLICT_AMBIGUOUS_DOI, resolution.target == null ? null : resolution.target.getId(), null);
        }
        if (resolution.state == LinkState.UNMATCHED || resolution.target == null) {
            return new LinkResult(LinkState.UNMATCHED, resolution.reason, null, null);
        }

        ScholardexPublicationFact target = resolution.target;
        if (target.getWosId() != null && !target.getWosId().isBlank() && !target.getWosId().equals(incomingWosId)) {
            saveConflict(KEY_WOS, incomingWosId, source, linkerVersion, linkerRunId, publication, target.getId(),
                    CONFLICT_TARGET_HAS_OTHER_KEY, List.of(target.getId()));
            return new LinkResult(LinkState.CONFLICT, CONFLICT_TARGET_HAS_OTHER_KEY, target.getId(), null);
        }

        List<String> conflictingIds = publicationFactRepository.findByWosId(incomingWosId).stream()
                .map(ScholardexPublicationFact::getId)
                .filter(id -> id != null && !id.equals(target.getId()))
                .toList();
        if (!conflictingIds.isEmpty()) {
            saveConflict(KEY_WOS, incomingWosId, source, linkerVersion, linkerRunId, publication, target.getId(),
                    CONFLICT_KEY_ASSIGNED, conflictingIds);
            return new LinkResult(LinkState.CONFLICT, CONFLICT_KEY_ASSIGNED, target.getId(), null);
        }

        target.setWosId(incomingWosId);
        target.setSource(source);
        target.setSourceRecordId(incomingWosId);
        target.setSourceBatchId(linkerRunId);
        target.setSourceCorrelationId(linkerVersion);
        target.setUpdatedAt(Instant.now());
        publicationFactRepository.save(target);
        upsertSourceLink(source, incomingWosId, target.getId(), linkerVersion, linkerRunId, "wos-link");
        return new LinkResult(LinkState.LINKED, "linked", target.getId(), null);
    }

    public LinkResult linkScholarEnrichment(String publicationId,
                                            String eid,
                                            String doi,
                                            String googleScholarId,
                                            String source,
                                            String linkerVersion,
                                            String linkerRunId) {
        String incomingScholarId = normalizeBlank(googleScholarId);
        if (incomingScholarId == null) {
            return LinkResult.skipped("empty-google-scholar-id");
        }

        Publication publication = new Publication();
        publication.setId(publicationId);
        publication.setEid(eid);
        publication.setDoi(doi);
        Resolution resolution = resolveTarget(publication);
        if (resolution.state == LinkState.CONFLICT) {
            saveConflict(KEY_SCHOLAR, incomingScholarId, source, linkerVersion, linkerRunId, publication, null,
                    CONFLICT_AMBIGUOUS_DOI, resolution.candidateIds);
            return new LinkResult(LinkState.CONFLICT, CONFLICT_AMBIGUOUS_DOI, null, null);
        }
        if (resolution.state == LinkState.UNMATCHED || resolution.target == null) {
            return new LinkResult(LinkState.UNMATCHED, resolution.reason, null, null);
        }

        ScholardexPublicationFact target = resolution.target;
        if (target.getGoogleScholarId() != null
                && !target.getGoogleScholarId().isBlank()
                && !target.getGoogleScholarId().equals(incomingScholarId)) {
            saveConflict(KEY_SCHOLAR, incomingScholarId, source, linkerVersion, linkerRunId, publication, target.getId(),
                    CONFLICT_TARGET_HAS_OTHER_KEY, List.of(target.getId()));
            return new LinkResult(LinkState.CONFLICT, CONFLICT_TARGET_HAS_OTHER_KEY, target.getId(), null);
        }

        List<String> conflictingIds = publicationFactRepository.findByGoogleScholarId(incomingScholarId).stream()
                .map(ScholardexPublicationFact::getId)
                .filter(id -> id != null && !id.equals(target.getId()))
                .toList();
        if (!conflictingIds.isEmpty()) {
            saveConflict(KEY_SCHOLAR, incomingScholarId, source, linkerVersion, linkerRunId, publication, target.getId(),
                    CONFLICT_KEY_ASSIGNED, conflictingIds);
            return new LinkResult(LinkState.CONFLICT, CONFLICT_KEY_ASSIGNED, target.getId(), null);
        }

        target.setGoogleScholarId(incomingScholarId);
        target.setSource(source);
        target.setSourceRecordId(incomingScholarId);
        target.setSourceBatchId(linkerRunId);
        target.setSourceCorrelationId(linkerVersion);
        target.setUpdatedAt(Instant.now());
        publicationFactRepository.save(target);
        upsertSourceLink(source, incomingScholarId, target.getId(), linkerVersion, linkerRunId, "scholar-link");
        return new LinkResult(LinkState.LINKED, "linked", target.getId(), null);
    }

    private Resolution resolveTarget(Publication publication) {
        String publicationId = normalizeBlank(publication.getId());
        if (publicationId != null) {
            Optional<ScholardexPublicationFact> byId = publicationFactRepository.findById(publicationId);
            if (byId.isPresent()) {
                return Resolution.linked(byId.get());
            }
        }

        String eid = normalizeBlank(publication.getEid());
        if (eid != null) {
            Optional<ScholardexPublicationFact> byEid = publicationFactRepository.findByEid(eid);
            if (byEid.isPresent()) {
                return Resolution.linked(byEid.get());
            }
        }

        String doiNormalized = normalizeDoi(publication.getDoi());
        if (doiNormalized != null) {
            List<ScholardexPublicationFact> byDoi = publicationFactRepository.findAllByDoiNormalized(doiNormalized);
            if (byDoi.size() == 1) {
                return Resolution.linked(byDoi.getFirst());
            }
            if (byDoi.size() > 1) {
                return Resolution.conflict("ambiguous-doi", byDoi.stream().map(ScholardexPublicationFact::getId).toList());
            }
        }

        return Resolution.unmatched("no-id-eid-doi-match");
    }

    private void saveConflict(String keyType,
                              String keyValue,
                              String source,
                              String linkerVersion,
                              String linkerRunId,
                              Publication publication,
                              String targetPublicationId,
                              String reason,
                              List<String> candidateIds) {
        saveGenericConflict(source, publication, reason, candidateIds);

        PublicationLinkConflict conflict = new PublicationLinkConflict();
        conflict.setConflictType("ENRICHMENT_LINK_CONFLICT");
        conflict.setConflictReason(reason);
        conflict.setEnrichmentSource(source);
        conflict.setKeyType(keyType);
        conflict.setKeyValue(keyValue);
        conflict.setRequestedPublicationId(normalizeBlank(publication.getId()));
        conflict.setRequestedEid(normalizeBlank(publication.getEid()));
        conflict.setRequestedDoiNormalized(normalizeDoi(publication.getDoi()));
        conflict.setTargetPublicationId(targetPublicationId);
        conflict.setCandidatePublicationIds(candidateIds == null ? List.of() : new ArrayList<>(candidateIds));
        conflict.setLinkerVersion(linkerVersion);
        conflict.setLinkerRunId(linkerRunId);
        conflict.setDetectedAt(Instant.now());
        conflictRepository.save(conflict);
    }

    private void saveGenericConflict(String source, Publication publication, String reason, List<String> candidateIds) {
        String recordId = normalizeBlank(publication.getId());
        if (recordId == null) {
            recordId = normalizeBlank(publication.getEid());
        }
        if (recordId == null) {
            recordId = normalizeDoi(publication.getDoi());
        }
        if (recordId == null) {
            recordId = "unknown";
        }
        ScholardexIdentityConflict conflict = identityConflictRepository
                .findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                        ScholardexEntityType.PUBLICATION, source, recordId, reason, STATUS_OPEN)
                .orElseGet(ScholardexIdentityConflict::new);
        conflict.setEntityType(ScholardexEntityType.PUBLICATION);
        conflict.setIncomingSource(source);
        conflict.setIncomingSourceRecordId(recordId);
        conflict.setReasonCode(reason);
        conflict.setStatus(STATUS_OPEN);
        conflict.setCandidateCanonicalIds(candidateIds == null ? List.of() : new ArrayList<>(candidateIds));
        if (conflict.getDetectedAt() == null) {
            conflict.setDetectedAt(Instant.now());
        }
        identityConflictRepository.save(conflict);
    }

    private void upsertSourceLink(
            String source,
            String sourceRecordId,
            String canonicalId,
            String linkerVersion,
            String linkerRunId,
            String reason
    ) {
        if (source == null || source.isBlank() || sourceRecordId == null || sourceRecordId.isBlank()) {
            return;
        }
        ScholardexSourceLink link = sourceLinkRepository
                .findByEntityTypeAndSourceAndSourceRecordId(ScholardexEntityType.PUBLICATION, source, sourceRecordId)
                .orElseGet(ScholardexSourceLink::new);
        Instant now = Instant.now();
        link.setEntityType(ScholardexEntityType.PUBLICATION);
        link.setSource(source);
        link.setSourceRecordId(sourceRecordId);
        link.setCanonicalEntityId(canonicalId);
        link.setLinkState("LINKED");
        link.setLinkReason(reason);
        link.setSourceBatchId(linkerRunId);
        link.setSourceCorrelationId(linkerVersion);
        if (link.getLinkedAt() == null) {
            link.setLinkedAt(now);
        }
        link.setUpdatedAt(now);
        sourceLinkRepository.save(link);
    }

    private String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeDoi(String doi) {
        String value = normalizeBlank(doi);
        if (value == null) {
            return null;
        }
        String normalized = DOI_URL_PREFIX.matcher(value).replaceFirst("");
        normalized = DOI_PREFIX.matcher(normalized).replaceFirst("");
        normalized = normalized.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private record Resolution(LinkState state, String reason, ScholardexPublicationFact target, List<String> candidateIds) {
        static Resolution linked(ScholardexPublicationFact view) {
            return new Resolution(LinkState.LINKED, "resolved", view, List.of(view.getId()));
        }

        static Resolution unmatched(String reason) {
            return new Resolution(LinkState.UNMATCHED, reason, null, List.of());
        }

        static Resolution conflict(String reason, List<String> ids) {
            return new Resolution(LinkState.CONFLICT, reason, null, ids);
        }
    }

    public enum LinkState {
        LINKED,
        UNMATCHED,
        CONFLICT,
        SKIPPED,
        INVALID
    }

    public record LinkResult(
            LinkState state,
            String reason,
            String targetPublicationId,
            String conflictId
    ) {
        static LinkResult skipped(String reason) {
            return new LinkResult(LinkState.SKIPPED, reason, null, null);
        }

        static LinkResult invalid(String reason) {
            return new LinkResult(LinkState.INVALID, reason, null, null);
        }
    }
}
