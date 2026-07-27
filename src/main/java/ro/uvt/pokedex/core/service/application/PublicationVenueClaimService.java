package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationVenueClaim;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationVenueClaimRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationDblpEvidenceRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * H93 — the venue-claim executor, shared by the live admin approval and the rebuild re-apply pass (chained
 * into the full-maintenance materialization AFTER {@code rebuildFromEvidence()} and the merge re-apply:
 * human decisions write last, or the DBLP auto-stamp would overwrite them moments after they applied).
 *
 * <p>Applying an approved claim has two levels. Always: stamp the claimed {@code forumId}, preserving the
 * displaced one as {@code originalForumId} so the H85/H92 fallbacks keep reading the pre-claim venue. When
 * the claimed forum carries a {@code conf/X} DBLP id: write the venue-evidence row in the shape DBLP would
 * have provided — {@code series=conf/X}, and {@code <label>@<ACRONYM>} when the workshop flag is set, which
 * the scorer's existing X@Y path turns into the half-points ladder with NO scorer change.
 *
 * <p><b>Claim beats DBLP</b>, including on rebuild durability: for a claim on a NON-conference forum, a
 * DBLP-known {@code series} must be cleared (into {@link PublicationVenueClaim.Displaced}) or
 * {@code rebuildFromEvidence()} would re-stamp the machine's forum over the human's on the next rebuild.
 * A rejection after approval reverts exactly what was displaced.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicationVenueClaimService {

    private static final String MATCH_METHOD_CLAIM = "researcher-claim";
    private static final String DEFAULT_WORKSHOP_LABEL = "WS";

    private final PublicationVenueClaimRepository claimRepository;
    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final ScholardexForumFactRepository forumFactRepository;
    private final ScholardexPublicationDblpEvidenceRepository evidenceRepository;
    private final ScholardexProjectionDirtyService projectionDirtyService;

    /* ------------------------------------------------------------------ */
    /*  Claim lifecycle                                                   */
    /* ------------------------------------------------------------------ */

    /** Creates a PENDING claim; an existing claim for the publication is returned instead of duplicated. */
    public PublicationVenueClaim requestClaim(String publicationId, String claimedForumId,
                                              boolean workshopOf, String workshopLabel,
                                              String requestedByEmail, String requestedByResearcherId,
                                              String note) {
        ScholardexPublicationFact publication = requirePublication(publicationId);
        ScholardexForumFact forum = requireForum(claimedForumId);
        Optional<PublicationVenueClaim> existing = claimRepository.findByPublicationId(publicationId);
        if (existing.isPresent()) {
            // One standing claim per publication: a REJECTED one suppresses re-claiming the same paper
            // (the researcher can see why in the queue), a PENDING/APPROVED one is simply surfaced.
            return existing.get();
        }
        PublicationVenueClaim claim = new PublicationVenueClaim();
        Instant now = Instant.now();
        claim.setStatus(PublicationVenueClaim.Status.PENDING);
        claim.setPublicationId(publication.getId());
        claim.setDoiNormalized(publication.getDoiNormalized());
        claim.setTitleNormalized(publication.getTitleNormalized());
        claim.setYear(yearOf(publication));
        claim.setPublicationTitle(publication.getTitle());
        claim.setClaimedForumId(forum.getId());
        claim.setClaimedForumName(forum.getName());
        claim.setWorkshopOf(workshopOf);
        claim.setWorkshopLabel(normalizeLabel(workshopLabel));
        claim.setRequestedByEmail(requestedByEmail);
        claim.setRequestedByResearcherId(requestedByResearcherId);
        claim.setRequestNote(note);
        claim.setCreatedAt(now);
        claim.setUpdatedAt(now);
        return claimRepository.save(claim);
    }

    public ClaimApplyResult approve(String claimId, String decidedBy, String note) {
        PublicationVenueClaim claim = requireClaim(claimId);
        claim.setStatus(PublicationVenueClaim.Status.APPROVED);
        claim.setDecidedBy(decidedBy);
        claim.setDecidedAt(Instant.now());
        claim.setDecisionNote(note);
        claim.setUpdatedAt(Instant.now());
        claimRepository.save(claim);
        return apply(claim, true);
    }

    /** Admin one-step (mirrors {@code directMerge}): records an APPROVED claim and applies it now. */
    public ClaimApplyResult directClaim(String publicationId, String claimedForumId,
                                        boolean workshopOf, String workshopLabel,
                                        String decidedBy, String note) {
        PublicationVenueClaim claim = requestClaim(publicationId, claimedForumId, workshopOf, workshopLabel,
                decidedBy, null, note);
        if (claim.getStatus() == PublicationVenueClaim.Status.REJECTED
                || !claimedForumId.equals(claim.getClaimedForumId())
                || claim.isWorkshopOf() != workshopOf) {
            // A standing claim disagrees with the explicit admin call — the admin call wins. Revert whatever
            // the old claim applied first, so displaced values chain correctly instead of nesting.
            revertIfApplied(claim);
            claim.setClaimedForumId(claimedForumId);
            claim.setClaimedForumName(requireForum(claimedForumId).getName());
            claim.setWorkshopOf(workshopOf);
            claim.setWorkshopLabel(normalizeLabel(workshopLabel));
            claim.setDisplaced(null);
        }
        return approve(claim.getId(), decidedBy, note);
    }

    /** Rejecting an APPLIED claim reverts it — the displaced venue and evidence come back exactly. */
    public PublicationVenueClaim reject(String claimId, String decidedBy, String note) {
        PublicationVenueClaim claim = requireClaim(claimId);
        revertIfApplied(claim);
        claim.setStatus(PublicationVenueClaim.Status.REJECTED);
        claim.setDecidedBy(decidedBy);
        claim.setDecidedAt(Instant.now());
        claim.setDecisionNote(note);
        claim.setUpdatedAt(Instant.now());
        return claimRepository.save(claim);
    }

    public Optional<PublicationVenueClaim> findClaim(String publicationId) {
        return claimRepository.findByPublicationId(publicationId);
    }

    public List<PublicationVenueClaim> listClaims(PublicationVenueClaim.Status status) {
        return status == null
                ? claimRepository.findAllByOrderByUpdatedAtDesc()
                : claimRepository.findByStatusOrderByUpdatedAtDesc(status);
    }

    /**
     * Rebuild durability: re-apply every APPROVED claim after the canonical replay. CORE-conference claims
     * would mostly survive through their evidence row ({@code rebuildFromEvidence} re-stamps it), but this
     * pass is still load-bearing for forum-only claims (no evidence row to ride on) and for re-anchoring a
     * claim whose publication was re-minted under a new id.
     */
    public ReapplySummary reapplyApproved() {
        int applied = 0;
        int skipped = 0;
        for (PublicationVenueClaim claim : claimRepository.findByStatus(PublicationVenueClaim.Status.APPROVED)) {
            ClaimApplyResult result = apply(claim, false);
            if (result.outcome() == ClaimOutcome.APPLIED) {
                applied++;
            } else {
                skipped++;
            }
        }
        log.info("Venue-claim re-apply: applied={} unresolved={}", applied, skipped);
        return new ReapplySummary(applied, skipped);
    }

    /* ------------------------------------------------------------------ */
    /*  The executor                                                      */
    /* ------------------------------------------------------------------ */

    public ClaimApplyResult apply(PublicationVenueClaim claim, boolean markProjectionsDirty) {
        ScholardexPublicationFact publication = resolvePublication(claim);
        if (publication == null) {
            log.warn("Venue claim {}: publication not resolvable (doi={} title={}), skipping",
                    claim.getId(), claim.getDoiNormalized(), claim.getTitleNormalized());
            return new ClaimApplyResult(ClaimOutcome.PUBLICATION_NOT_FOUND, null);
        }
        if (!publication.getId().equals(claim.getPublicationId())) {
            // The rebuild re-minted the publication under a new id — re-anchor the claim on it.
            claim.setPublicationId(publication.getId());
        }
        ScholardexForumFact forum = forumFactRepository.findById(claim.getClaimedForumId()).orElse(null);
        if (forum == null) {
            log.warn("Venue claim {}: claimed forum {} no longer exists, skipping",
                    claim.getId(), claim.getClaimedForumId());
            return new ClaimApplyResult(ClaimOutcome.FORUM_NOT_FOUND, publication.getId());
        }

        captureDisplacedOnce(claim, publication);

        // Level 1 — the forum stamp, preserving the displaced venue for the H85/H92 fallbacks.
        String previousForumId = publication.getForumId();
        if (previousForumId != null && !previousForumId.equals(forum.getId())
                && !forum.getId().equals(publication.getOriginalForumId())) {
            publication.setOriginalForumId(previousForumId);
        }
        publication.setForumId(forum.getId());
        publicationFactRepository.save(publication);

        // Level 2 — venue evidence, only meaningful when the claimed forum IS a conference stream.
        String streamKey = conferenceStreamKey(forum);
        if (streamKey != null) {
            String acronym = streamKey.substring("conf/".length()).toUpperCase(Locale.ROOT);
            String conferenceName = claim.isWorkshopOf()
                    ? labelOf(claim) + "@" + acronym
                    : acronym;
            upsertEvidence(claim, publication, streamKey, conferenceName);
        } else {
            // Claim on a non-conference forum: a DBLP-known series must be cleared, or the next
            // rebuildFromEvidence() re-stamps the machine's conf/X forum right over this decision.
            clearEvidenceSeriesIfPresent(claim, publication);
        }

        claim.setLastAppliedAt(Instant.now());
        claim.setUpdatedAt(Instant.now());
        claimRepository.save(claim);
        if (markProjectionsDirty) {
            projectionDirtyService.markDirty(ScholardexEntityType.PUBLICATION, publication.getId(),
                    null, null, null, "venue-claim " + claim.getId() + " -> forum " + forum.getId());
        }
        log.info("Venue claim {} applied: pub={} forum={} workshopOf={}",
                claim.getId(), publication.getId(), forum.getId(), claim.isWorkshopOf());
        return new ClaimApplyResult(ClaimOutcome.APPLIED, publication.getId());
    }

    /* ------------------------------------------------------------------ */
    /*  Displacement + revert                                             */
    /* ------------------------------------------------------------------ */

    /** Idempotent: the FIRST apply records what was there; re-applies must not overwrite the revert target. */
    private void captureDisplacedOnce(PublicationVenueClaim claim, ScholardexPublicationFact publication) {
        if (claim.getDisplaced() != null) {
            return;
        }
        PublicationVenueClaim.Displaced displaced = new PublicationVenueClaim.Displaced();
        displaced.setForumId(publication.getForumId());
        Optional<ScholardexPublicationDblpEvidence> evidence =
                evidenceRepository.findByPublicationId(publication.getId());
        displaced.setEvidenceExisted(evidence.isPresent());
        evidence.ifPresent(ev -> {
            displaced.setEvidenceSeries(ev.getSeries());
            displaced.setEvidenceConferenceName(ev.getConferenceName());
            displaced.setEvidenceMatchMethod(ev.getMatchMethod());
        });
        claim.setDisplaced(displaced);
    }

    private void revertIfApplied(PublicationVenueClaim claim) {
        PublicationVenueClaim.Displaced displaced = claim.getDisplaced();
        if (displaced == null || claim.getLastAppliedAt() == null) {
            return;
        }
        publicationFactRepository.findById(claim.getPublicationId()).ifPresent(pub -> {
            // Only un-stamp if the claim's forum is still the one on the publication — a later rebuild or
            // merge may have moved it, and reverting over THAT would destroy newer information.
            if (claim.getClaimedForumId().equals(pub.getForumId())) {
                pub.setForumId(displaced.getForumId());
                publicationFactRepository.save(pub);
            }
        });
        Optional<ScholardexPublicationDblpEvidence> evidence =
                evidenceRepository.findByPublicationId(claim.getPublicationId());
        if (evidence.isPresent()) {
            ScholardexPublicationDblpEvidence ev = evidence.get();
            if (!displaced.isEvidenceExisted() && MATCH_METHOD_CLAIM.equals(ev.getMatchMethod())
                    && ev.getVolumeTitle() == null) {
                // The claim created this row and nothing else lives on it — remove it entirely.
                evidenceRepository.delete(ev);
            } else if (MATCH_METHOD_CLAIM.equals(ev.getMatchMethod())) {
                ev.setSeries(displaced.getEvidenceSeries());
                ev.setConferenceName(displaced.getEvidenceConferenceName());
                ev.setMatchMethod(displaced.getEvidenceMatchMethod());
                ev.setUpdatedAt(Instant.now());
                evidenceRepository.save(ev);
            }
        }
        claim.setLastAppliedAt(null);
        projectionDirtyService.markDirty(ScholardexEntityType.PUBLICATION, claim.getPublicationId(),
                null, null, null, "venue-claim revert " + claim.getId());
    }

    /* ------------------------------------------------------------------ */
    /*  Evidence plumbing                                                 */
    /* ------------------------------------------------------------------ */

    private void upsertEvidence(PublicationVenueClaim claim, ScholardexPublicationFact publication,
                                String streamKey, String conferenceName) {
        ScholardexPublicationDblpEvidence ev = evidenceRepository.findByPublicationId(publication.getId())
                .orElseGet(ScholardexPublicationDblpEvidence::new);
        Instant now = Instant.now();
        if (ev.getCreatedAt() == null) {
            ev.setCreatedAt(now);
        }
        ev.setPublicationId(publication.getId());
        if (ev.getDoi() == null) {
            ev.setDoi(publication.getDoi());
        }
        if (ev.getTitle() == null) {
            ev.setTitle(publication.getTitle());
        }
        ev.setSeries(streamKey);
        ev.setConferenceName(conferenceName);
        ev.setMatchMethod(MATCH_METHOD_CLAIM);
        ev.setUpdatedAt(now);
        evidenceRepository.save(ev);
    }

    private void clearEvidenceSeriesIfPresent(PublicationVenueClaim claim, ScholardexPublicationFact publication) {
        evidenceRepository.findByPublicationId(publication.getId()).ifPresent(ev -> {
            if (ev.getSeries() == null && ev.getConferenceName() == null) {
                return;
            }
            ev.setSeries(null);
            ev.setConferenceName(null);
            ev.setMatchMethod(MATCH_METHOD_CLAIM);
            ev.setUpdatedAt(Instant.now());
            evidenceRepository.save(ev);
        });
    }

    /** The claimed forum's {@code conf/X} stream key, or null when it is not a conference stream. */
    private static String conferenceStreamKey(ScholardexForumFact forum) {
        if (forum.getDblpIds() == null) {
            return null;
        }
        return forum.getDblpIds().stream()
                .filter(id -> id != null && id.startsWith("conf/") && id.length() > "conf/".length())
                .findFirst()
                .orElse(null);
    }

    /* ------------------------------------------------------------------ */
    /*  Anchoring                                                         */
    /* ------------------------------------------------------------------ */

    private ScholardexPublicationFact resolvePublication(PublicationVenueClaim claim) {
        Optional<ScholardexPublicationFact> byId = publicationFactRepository.findById(claim.getPublicationId());
        if (byId.isPresent()) {
            return byId.get();
        }
        if (claim.getDoiNormalized() != null && !claim.getDoiNormalized().isBlank()) {
            List<ScholardexPublicationFact> byDoi =
                    publicationFactRepository.findAllByDoiNormalized(claim.getDoiNormalized());
            if (byDoi.size() == 1) {
                return byDoi.getFirst();
            }
        }
        if (claim.getTitleNormalized() != null && !claim.getTitleNormalized().isBlank()) {
            List<ScholardexPublicationFact> byTitle =
                    publicationFactRepository.findAllByTitleNormalized(claim.getTitleNormalized());
            List<ScholardexPublicationFact> sameYear = byTitle.stream()
                    .filter(p -> claim.getYear() == null || claim.getYear().equals(yearOf(p)))
                    .toList();
            if (sameYear.size() == 1) {
                return sameYear.getFirst();
            }
        }
        return null;
    }

    private static Integer yearOf(ScholardexPublicationFact publication) {
        String coverDate = publication.getCoverDate();
        if (coverDate == null || coverDate.length() < 4) {
            return null;
        }
        try {
            return Integer.parseInt(coverDate.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Small helpers                                                     */
    /* ------------------------------------------------------------------ */

    private static String labelOf(PublicationVenueClaim claim) {
        String label = claim.getWorkshopLabel();
        return (label == null || label.isBlank()) ? DEFAULT_WORKSHOP_LABEL : label;
    }

    private static String normalizeLabel(String label) {
        if (label == null) {
            return null;
        }
        // The label lands inside "<label>@<ACRONYM>"; an '@' in it would fake a second marker.
        String cleaned = label.trim().replace("@", "");
        return cleaned.isBlank() ? null : cleaned;
    }

    private PublicationVenueClaim requireClaim(String claimId) {
        return claimRepository.findById(claimId)
                .orElseThrow(() -> new IllegalArgumentException("venue claim not found: " + claimId));
    }

    private ScholardexPublicationFact requirePublication(String publicationId) {
        return publicationFactRepository.findById(publicationId)
                .orElseThrow(() -> new IllegalArgumentException("publication not found: " + publicationId));
    }

    private ScholardexForumFact requireForum(String forumId) {
        return forumFactRepository.findById(forumId)
                .orElseThrow(() -> new IllegalArgumentException("forum not found: " + forumId));
    }

    /* ------------------------------------------------------------------ */
    /*  Result types                                                      */
    /* ------------------------------------------------------------------ */

    public enum ClaimOutcome {
        APPLIED,
        PUBLICATION_NOT_FOUND,
        FORUM_NOT_FOUND
    }

    public record ClaimApplyResult(ClaimOutcome outcome, String publicationId) {
    }

    public record ReapplySummary(int applied, int unresolved) {
    }
}
