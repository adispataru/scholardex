package ro.uvt.pokedex.core.service.application;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationAuthorshipDecisionRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class PublicationAuthorshipDecisionService {

    private final PublicationAuthorshipDecisionRepository decisionRepository;
    private final UserRepository userRepository;
    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final ScholardexAuthorshipFactRepository authorshipFactRepository;
    private final UserIndicatorResultService userIndicatorResultService;
    private final UserIndividualReportRunService userIndividualReportRunService;

    public PublicationAuthorshipDecisionService(PublicationAuthorshipDecisionRepository decisionRepository,
                                                UserRepository userRepository,
                                                ScholardexPublicationFactRepository publicationFactRepository,
                                                ScholardexAuthorshipFactRepository authorshipFactRepository,
                                                UserIndicatorResultService userIndicatorResultService,
                                                UserIndividualReportRunService userIndividualReportRunService) {
        this.decisionRepository = decisionRepository;
        this.userRepository = userRepository;
        this.publicationFactRepository = publicationFactRepository;
        this.authorshipFactRepository = authorshipFactRepository;
        this.userIndicatorResultService = userIndicatorResultService;
        this.userIndividualReportRunService = userIndividualReportRunService;
    }

    public Optional<PublicationAuthorshipDecision> findDecision(String userEmail, String publicationId) {
        return decisionRepository.findByUserEmailAndPublicationId(userEmail, publicationId);
    }

    public List<PublicationAuthorshipDecision> findDecisionsForUser(String userEmail) {
        return decisionRepository.findByUserEmailOrderByUpdatedAtDesc(userEmail);
    }

    public List<PublicationAuthorshipDecision> findDecisionsForUserAndPublications(String userEmail,
                                                                                   Collection<String> publicationIds) {
        if (publicationIds == null || publicationIds.isEmpty()) {
            return List.of();
        }
        return decisionRepository.findByUserEmailAndPublicationIdIn(userEmail, publicationIds);
    }

    public PublicationAuthorshipDecision upsertDecision(String userEmail,
                                                        String publicationId,
                                                        PublicationAuthorshipDecision.Status status,
                                                        String reason) {
        PublicationAuthorshipDecision saved = upsertDecisionInternal(userEmail, publicationId, status, reason);
        invalidateUserReportingCaches(userEmail);
        return saved;
    }

    private PublicationAuthorshipDecision upsertDecisionInternal(String userEmail,
                                                                 String publicationId,
                                                                 PublicationAuthorshipDecision.Status status,
                                                                 String reason) {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }

        User user = userRepository.findById(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userEmail));
        if (requiresAffiliationScopeConfirmation(user)) {
            throw new IllegalStateException("Confirm your current and past affiliations in the profile tab before reviewing publication authorship.");
        }
        ScholardexPublicationFact publication = publicationFactRepository.findById(publicationId)
                .orElseThrow(() -> new IllegalArgumentException("Publication not found: " + publicationId));

        Instant now = Instant.now();
        PublicationAuthorshipDecision decision = decisionRepository.findByUserEmailAndPublicationId(userEmail, publicationId)
                .orElseGet(PublicationAuthorshipDecision::new);

        boolean newDecision = decision.getId() == null;
        if (newDecision) {
            decision.setUserEmail(userEmail);
            decision.setPublicationId(publicationId);
            decision.setCreatedAt(now);
        }

        decision.setResearcherId(resolveResearcherId(user));
        decision.setStatus(status);
        decision.setDecisionSource(PublicationAuthorshipDecision.DecisionSource.USER_REVIEW);
        decision.setReason(normalize(reason));
        decision.setUpdatedAt(now);

        if (decision.getSnapshot() == null) {
            decision.setSnapshot(buildSnapshot(user, publication));
        }

        return decisionRepository.save(decision);
    }

    public BulkDecisionResult upsertBulkDecisions(String userEmail,
                                                  Collection<String> publicationIds,
                                                  PublicationAuthorshipDecision.Status status,
                                                  String reason) {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        User user = userRepository.findById(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userEmail));
        if (requiresAffiliationScopeConfirmation(user)) {
            throw new IllegalStateException("Confirm your current and past affiliations in the profile tab before reviewing publication authorship.");
        }

        Map<String, PublicationAuthorshipDecision> succeeded = new LinkedHashMap<>();
        List<DecisionFailure> failed = new ArrayList<>();
        Set<String> normalizedPublicationIds = new LinkedHashSet<>();
        if (publicationIds != null) {
            publicationIds.stream()
                    .map(this::normalize)
                    .filter(value -> value != null)
                    .forEach(normalizedPublicationIds::add);
        }

        if (normalizedPublicationIds.isEmpty()) {
            return new BulkDecisionResult(succeeded, failed);
        }

        List<PublicationAuthorshipDecision> existingDecisions =
                decisionRepository.findByUserEmailAndPublicationIdIn(userEmail, normalizedPublicationIds);
        Map<String, PublicationAuthorshipDecision> decisionsByPublicationId = new LinkedHashMap<>();
        existingDecisions.forEach(decision -> decisionsByPublicationId.put(decision.getPublicationId(), decision));

        for (String publicationId : normalizedPublicationIds) {
            PublicationAuthorshipDecision existing = decisionsByPublicationId.get(publicationId);
            if (existing != null && existing.getStatus() != null) {
                failed.add(new DecisionFailure(publicationId, "Only pending publications can be reviewed in bulk."));
                continue;
            }
            try {
                PublicationAuthorshipDecision saved = upsertDecisionInternal(userEmail, publicationId, status, reason);
                succeeded.put(publicationId, saved);
            } catch (IllegalArgumentException | IllegalStateException ex) {
                failed.add(new DecisionFailure(publicationId, ex.getMessage()));
            }
        }
        if (!succeeded.isEmpty()) {
            invalidateUserReportingCaches(userEmail);
        }
        return new BulkDecisionResult(succeeded, failed);
    }

    public boolean clearDecision(String userEmail, String publicationId) {
        boolean deleted = decisionRepository.deleteByUserEmailAndPublicationId(userEmail, publicationId) > 0;
        if (deleted) {
            invalidateUserReportingCaches(userEmail);
        }
        return deleted;
    }

    private PublicationAuthorshipDecision.Snapshot buildSnapshot(User user, ScholardexPublicationFact publication) {
        PublicationAuthorshipDecision.Snapshot snapshot = new PublicationAuthorshipDecision.Snapshot();

        PublicationAuthorshipDecision.PublicationSnapshot publicationSnapshot = snapshot.getPublication();
        publicationSnapshot.setTitle(publication.getTitle());
        publicationSnapshot.setEid(publication.getEid());
        publicationSnapshot.setDoi(publication.getDoi());

        PublicationAuthorshipDecision.UserSnapshot userSnapshot = snapshot.getUser();
        userSnapshot.setResearcherId(resolveResearcherId(user));
        if (user.getResearcherProfile() != null) {
            userSnapshot.setPrimaryScholardexAuthorId(user.getResearcherProfile().getPrimaryScholardexAuthorId());
            userSnapshot.setFirstName(user.getResearcherProfile().getFirstName());
            userSnapshot.setLastName(user.getResearcherProfile().getLastName());
        }

        snapshot.setLinkedAuthorIds(resolveLinkedAuthorIds(publication));
        return snapshot;
    }

    private List<String> resolveLinkedAuthorIds(ScholardexPublicationFact publication) {
        Set<String> authorIds = new LinkedHashSet<>();
        if (publication.getAuthorIds() != null) {
            publication.getAuthorIds().stream()
                    .map(this::normalize)
                    .filter(value -> value != null)
                    .forEach(authorIds::add);
        }

        List<ScholardexAuthorshipFact> authorshipFacts = authorshipFactRepository.findByPublicationId(publication.getId());
        authorshipFacts.stream()
                .map(ScholardexAuthorshipFact::getAuthorId)
                .map(this::normalize)
                .filter(value -> value != null)
                .forEach(authorIds::add);

        return new ArrayList<>(authorIds);
    }

    private String resolveResearcherId(User user) {
        if (user.getResearcherProfile() != null && normalize(user.getResearcherProfile().getPrimaryScholardexAuthorId()) != null) {
            return normalize(user.getResearcherId());
        }
        return normalize(user.getResearcherId());
    }

    private boolean requiresAffiliationScopeConfirmation(User user) {
        User.ResearcherProfile profile = user.getResearcherProfile();
        if (profile == null) {
            return false;
        }
        boolean hasLinkedIdentity = normalize(profile.getPrimaryScholardexAuthorId()) != null
                || hasValues(profile.getScopusId())
                || hasValues(profile.getWosId());
        return hasLinkedIdentity && profile.getAffiliationsConfirmedAt() == null;
    }

    private boolean hasValues(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        return values.stream().map(this::normalize).anyMatch(value -> value != null);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void invalidateUserReportingCaches(String userEmail) {
        userIndicatorResultService.invalidateLatestResults(userEmail);
        userIndividualReportRunService.invalidateLatestRuns(userEmail);
    }

    public record BulkDecisionResult(
            Map<String, PublicationAuthorshipDecision> succeededByPublicationId,
            List<DecisionFailure> failures
    ) {
    }

    public record DecisionFailure(
            String publicationId,
            String message
    ) {
    }
}
