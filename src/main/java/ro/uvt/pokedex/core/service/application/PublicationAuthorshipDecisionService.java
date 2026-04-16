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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class PublicationAuthorshipDecisionService {

    private final PublicationAuthorshipDecisionRepository decisionRepository;
    private final UserRepository userRepository;
    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final ScholardexAuthorshipFactRepository authorshipFactRepository;

    public PublicationAuthorshipDecisionService(PublicationAuthorshipDecisionRepository decisionRepository,
                                                UserRepository userRepository,
                                                ScholardexPublicationFactRepository publicationFactRepository,
                                                ScholardexAuthorshipFactRepository authorshipFactRepository) {
        this.decisionRepository = decisionRepository;
        this.userRepository = userRepository;
        this.publicationFactRepository = publicationFactRepository;
        this.authorshipFactRepository = authorshipFactRepository;
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
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }

        User user = userRepository.findById(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userEmail));
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

    public boolean clearDecision(String userEmail, String publicationId) {
        return decisionRepository.deleteByUserEmailAndPublicationId(userEmail, publicationId) > 0;
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

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
