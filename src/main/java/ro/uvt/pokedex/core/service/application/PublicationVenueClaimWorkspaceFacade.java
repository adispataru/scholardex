package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationVenueClaim;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationVenueClaimRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * H93 S3 — the researcher-facing side of venue claims. A researcher can flag "this publication of mine
 * actually belongs to THIS venue" (optionally as a workshop of that venue's conference); the flag lands as
 * a PENDING claim in the S2 admin queue and applies nothing until approved.
 *
 * <p>Ownership is enforced here: only publications on the researcher's own effective list are claimable —
 * a claim moves every co-author's score too, which is exactly why it goes through review.</p>
 */
@Service
@RequiredArgsConstructor
public class PublicationVenueClaimWorkspaceFacade {

    private final EffectiveAuthorshipReadService effectiveAuthorshipReadService;
    private final PublicationVenueClaimService claimService;
    private final PublicationVenueClaimRepository claimRepository;
    private final ScholardexForumFactRepository forumFactRepository;

    /** Claim status per publication id, for the researcher's own list (drives the detail-panel state). */
    public ClaimWorkspaceView claimState(String userEmail) {
        List<ScholardexPublicationView> publications =
                effectiveAuthorshipReadService.findEffectivePublicationsForUser(userEmail);
        if (publications.isEmpty()) {
            return new ClaimWorkspaceView(Map.of());
        }
        Set<String> ownIds = new HashSet<>();
        publications.forEach(pub -> ownIds.add(pub.getId()));
        Map<String, String> stateByPublicationId = new HashMap<>();
        for (PublicationVenueClaim claim : claimRepository.findByPublicationIdIn(ownIds)) {
            stateByPublicationId.put(claim.getPublicationId(), claim.getStatus().name());
        }
        return new ClaimWorkspaceView(stateByPublicationId);
    }

    /** Flag a venue claim on an OWN publication; lands PENDING in the admin queue. */
    public PublicationVenueClaim requestClaim(String userEmail, String researcherId,
                                              String publicationId, String forumId,
                                              boolean workshopOf, String workshopLabel, String note) {
        boolean own = effectiveAuthorshipReadService.findEffectivePublicationsForUser(userEmail).stream()
                .anyMatch(pub -> pub.getId().equals(publicationId));
        if (!own) {
            throw new IllegalArgumentException("the publication must be on your own publication list");
        }
        return claimService.requestClaim(publicationId, forumId, workshopOf, workshopLabel,
                userEmail, researcherId, note);
    }

    /**
     * Forum search for the claim picker, RANKED so that the venues a claim is meant to point AT come
     * first and the ones it exists to get AWAY FROM come last:
     *
     * <ol>
     *   <li>conference stream forums (a {@code conf/X} DBLP id) — claiming one of these is what unlocks
     *       CORE resolution and the workshop ladder;</li>
     *   <li>other Conference Proceeding forums;</li>
     *   <li>journals and everything else;</li>
     *   <li>Book Series LAST — a series forum ("Lecture Notes on …") is the very state the claim flow
     *       exists to fix, so it must never be the easy pick.</li>
     * </ol>
     */
    public List<ForumOption> searchForums(String query) {
        if (query == null || query.trim().length() < 2) {
            return List.of();
        }
        return forumFactRepository.findTop100ByNameContainingIgnoreCase(query.trim()).stream()
                .map(ForumOption::of)
                .sorted(Comparator.comparingInt(ForumOption::rank)
                        .thenComparing(option -> option.name() == null ? "" : option.name()))
                .limit(20)
                .toList();
    }

    public record ClaimWorkspaceView(Map<String, String> claimStateByPublicationId) {
    }

    public record ForumOption(String id, String name, String aggregationType, boolean conferenceStream) {

        static ForumOption of(ScholardexForumFact forum) {
            boolean stream = forum.getDblpIds() != null
                    && forum.getDblpIds().stream().anyMatch(id -> id != null && id.startsWith("conf/"));
            return new ForumOption(forum.getId(), forum.getName(), forum.getAggregationType(), stream);
        }

        int rank() {
            if (conferenceStream) {
                return 0;
            }
            if ("Conference Proceeding".equalsIgnoreCase(aggregationType)) {
                return 1;
            }
            if ("Book Series".equalsIgnoreCase(aggregationType)) {
                return 3;
            }
            return 2;
        }
    }
}
