package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.activities.PublisherClaim;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.service.reporting.PublisherRules;
import ro.uvt.pokedex.core.service.security.PrincipalAuthorDeclarationAccessService;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * H143 — a head of a researcher's department or faculty, or a platform admin, decides the researcher's requests to
 * classify the publisher of a declared book; the same people who decide principal-author declarations, and nobody on
 * their own request ({@link PrincipalAuthorDeclarationAccessService}). An approved request counts in the next run.
 */
@Service
@RequiredArgsConstructor
public class PublisherClaimReviewService {

    public enum Refusal { NOT_FOUND, NOT_PENDING, NOT_APPROVED, NOT_ALLOWED, NOTE_REQUIRED, NOTE_TOO_LONG }

    static final int NOTE_MAX = 1000;

    /** A decision the service refused, with the reason the page shows. */
    public static class ClaimRefused extends RuntimeException {
        private final Refusal refusal;

        public ClaimRefused(Refusal refusal) {
            super(refusal.name());
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    /** A request as a head sees it: the record, what the lists say under each standard, the request. */
    public record ClaimItem(String activityId, String researcherEmail, String typeName, String title,
                            PublisherCategoryFacade.RecordView record, List<String> facts) {
    }

    private final ActivityInstanceRepository activityInstanceRepository;
    private final PrincipalAuthorDeclarationAccessService access;
    private final PublisherCategoryFacade categories;

    /** The requests waiting for a decision that the principal may decide, oldest first. */
    public List<ClaimItem> pendingFor(Authentication authentication) {
        return visible(authentication, Set.of(PublisherClaim.Status.PENDING),
                Comparator.comparing(i -> requestedAt(i), Comparator.nullsLast(Comparator.naturalOrder())), Integer.MAX_VALUE);
    }

    /** The latest decisions on the requests of the researchers the principal answers for. */
    public List<ClaimItem> decidedFor(Authentication authentication, int limit) {
        return visible(authentication, Set.of(PublisherClaim.Status.APPROVED, PublisherClaim.Status.REJECTED),
                Comparator.comparing((ActivityInstance i) -> i.getPublisherClaim().getDecidedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder())), limit);
    }

    public ActivityInstance approve(String activityId, Authentication authentication, String note) {
        return decide(activityId, authentication, note, PublisherClaim.Status.PENDING, false,
                PublisherClaim.Status.APPROVED, PublisherClaim.Action.APPROVED);
    }

    /** A rejection says why. */
    public ActivityInstance reject(String activityId, Authentication authentication, String note) {
        return decide(activityId, authentication, note, PublisherClaim.Status.PENDING, true,
                PublisherClaim.Status.REJECTED, PublisherClaim.Action.REJECTED);
    }

    /** Withdraws an approval that turned out wrong, saying why; the request then counts no more. */
    public ActivityInstance revoke(String activityId, Authentication authentication, String note) {
        return decide(activityId, authentication, note, PublisherClaim.Status.APPROVED, true,
                PublisherClaim.Status.REJECTED, PublisherClaim.Action.REJECTED);
    }

    private ActivityInstance decide(String activityId, Authentication authentication, String note,
                                    PublisherClaim.Status from, boolean noteRequired,
                                    PublisherClaim.Status status, PublisherClaim.Action action) {
        ActivityInstance instance = activityInstanceRepository.findById(activityId)
                .orElseThrow(() -> new ClaimRefused(Refusal.NOT_FOUND));
        PublisherClaim claim = instance.getPublisherClaim();
        if (claim == null || claim.getStatus() != from) {
            throw new ClaimRefused(from == PublisherClaim.Status.APPROVED ? Refusal.NOT_APPROVED : Refusal.NOT_PENDING);
        }
        if (!access.canDecide(instance.getResearcherId(), authentication)) {
            throw new ClaimRefused(Refusal.NOT_ALLOWED);
        }
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (noteRequired && cleanNote == null) {
            throw new ClaimRefused(Refusal.NOTE_REQUIRED);
        }
        if (cleanNote != null && cleanNote.length() > NOTE_MAX) {
            throw new ClaimRefused(Refusal.NOTE_TOO_LONG);
        }
        claim.setStatus(status);
        claim.setFacts(PublisherClaim.factsOf(instance)); // H145: the decision is about the record as the head saw it
        claim.setDecidedBy(authentication.getName());
        claim.setDecidedAt(Instant.now());
        claim.setDecisionNote(cleanNote);
        claim.getHistory().add(PublisherClaim.Event.of(action, authentication.getName(), cleanNote));
        return activityInstanceRepository.save(instance);
    }

    private List<ClaimItem> visible(Authentication authentication, Set<PublisherClaim.Status> statuses,
                                    Comparator<ActivityInstance> order, int limit) {
        Map<String, Set<PublisherRules>> rulesByType = categories.rulesByActivityType();
        return activityInstanceRepository.findByPublisherClaim_StatusIn(statuses).stream()
                .filter(i -> i.getPublisherClaim() != null && access.canDecide(i.getResearcherId(), authentication))
                .sorted(order)
                .limit(Math.max(0, limit))
                .map(i -> new ClaimItem(i.getId(), i.getResearcherId(),
                        i.getActivity() == null ? null : i.getActivity().getName(), title(i), categories.view(i, rulesByType),
                        facts(i)))
                .toList();
    }

    /**
     * H145 — what the record states, as the head approves it: every field but the request's own, then its references
     * (the journal's ISSN, the named conference, …). A change of any of them sends the request back.
     */
    static List<String> facts(ActivityInstance instance) {
        List<String> facts = new java.util.ArrayList<>();
        if (instance.getDate() != null && !instance.getDate().isBlank()) {
            facts.add("Data: " + instance.getDate());
        }
        if (instance.getFields() != null) {
            new java.util.TreeMap<>(instance.getFields()).forEach((k, v) -> {
                if (!PublisherClaim.REQUEST_FIELD.equals(k) && !PublisherClaim.EVIDENCE_FIELD.equals(k)
                        && v != null && !v.isBlank()) {
                    facts.add(k + ": " + v.trim());
                }
            });
        }
        if (instance.getReferenceFields() != null) {
            instance.getReferenceFields().forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank()) {
                    facts.add(k.name() + ": " + v.trim());
                }
            });
        }
        return facts;
    }

    private static Instant requestedAt(ActivityInstance instance) {
        return instance.getPublisherClaim() == null ? null : instance.getPublisherClaim().getRequestedAt();
    }

    /** The book's title as the record has it: its title field, else its name. */
    private static String title(ActivityInstance instance) {
        Map<String, String> fields = instance.getFields();
        String title = fields == null ? null : fields.get("Titlu");
        return title != null && !title.isBlank() ? title : instance.getName();
    }
}
