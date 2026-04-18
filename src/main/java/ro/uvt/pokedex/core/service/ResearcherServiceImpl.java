package ro.uvt.pokedex.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.utils.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Implementation of {@link ResearcherService} backed by {@link UserRepository}.
 * <p>
 * Researcher profiles are now stored as embedded sub-documents inside the
 * User collection. The researcher "id" is the owning user's email.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResearcherServiceImpl implements ResearcherService {

    private final UserRepository userRepository;

    /**
     * Upserts the researcher profile into the User identified by
     * {@code researcher.getId()} (which must be the user's email).
     */
    @Override
    public Researcher saveResearcher(Researcher researcher) {
        if (researcher.getId() == null || researcher.getId().isBlank()) {
            throw new IllegalArgumentException(
                    "Cannot save researcher profile: id (user email) must not be null");
        }
        User user = userRepository.findById(researcher.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No user found with email: " + researcher.getId()));
        applyToProfile(researcher, user);
        userRepository.save(user);
        return Researcher.fromUser(user);
    }

    /**
     * Looks up a researcher by user email. Returns empty if the user does not
     * exist or has no embedded researcher profile.
     */
    @Override
    public Optional<Researcher> findResearcherById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return userRepository.findById(id)
                .map(Researcher::fromUser);
    }

    @Override
    public List<Researcher> findAllResearchers() {
        return userRepository.findAllByResearcherProfileIsNotNull()
                .stream()
                .map(Researcher::fromUser)
                .collect(Collectors.toList());
    }

    @Override
    public Researcher updateResearcher(String id, Researcher researcher) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No user found with email: " + id));
        applyToProfile(researcher, user);
        userRepository.save(user);
        return Researcher.fromUser(user);
    }

    /**
     * Removes the researcher profile from the user. The user account itself
     * is kept.
     */
    @Override
    public void deleteResearcher(String id) {
        userRepository.findById(id).ifPresent(user -> {
            user.setResearcherProfile(null);
            userRepository.save(user);
        });
    }

    @Override
    public Optional<Researcher> matchAuthorToResearcher(String authorName) {
        String normalizedAuthorName = StringUtils.normalize(authorName);
        String[] authorNameParts = normalizedAuthorName.split("\\s+");

        List<Researcher> researchers = findAllResearchers();
        for (Researcher researcher : researchers) {
            String researcherName = StringUtils.normalize(researcher.getName());
            String[] researcherNameParts = researcherName.split("\\s+");
            if (nameMatches(authorNameParts, researcherNameParts)) {
                return Optional.of(researcher);
            }
        }
        return Optional.empty();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void applyToProfile(Researcher researcher, User user) {
        User.ResearcherProfile profile = user.getResearcherProfile();
        if (profile == null) profile = new User.ResearcherProfile();
        profile.setFirstName(researcher.getFirstName());
        profile.setLastName(researcher.getLastName());
        profile.setScholarId(researcher.getScholarId());
        profile.setScopusId(researcher.getScopusId() != null
                ? researcher.getScopusId() : new ArrayList<>());
        profile.setWosId(researcher.getWosId() != null
                ? researcher.getWosId() : new ArrayList<>());
        profile.setPrimaryScholardexAuthorId(researcher.getPrimaryScholardexAuthorId());
        if (researcher.getCurrentAffiliationIds() != null && !researcher.getCurrentAffiliationIds().isEmpty()) {
            profile.setCurrentAffiliationIds(researcher.getCurrentAffiliationIds());
        } else if (profile.getCurrentAffiliationIds() == null) {
            profile.setCurrentAffiliationIds(new ArrayList<>());
        }
        if (researcher.getPastAffiliationIds() != null && !researcher.getPastAffiliationIds().isEmpty()) {
            profile.setPastAffiliationIds(researcher.getPastAffiliationIds());
        } else if (profile.getPastAffiliationIds() == null) {
            profile.setPastAffiliationIds(new ArrayList<>());
        }
        if (researcher.getAffiliationsConfirmedAt() != null) {
            profile.setAffiliationsConfirmedAt(researcher.getAffiliationsConfirmedAt());
        }
        profile.setPosition(researcher.getPosition());
        user.setResearcherProfile(profile);
    }

    private boolean nameMatches(String[] authorNameParts, String[] researcherNameParts) {
        if (authorNameParts.length == 0 || researcherNameParts.length == 0) return false;
        String authorFirst      = authorNameParts[0];
        String authorLast       = authorNameParts[authorNameParts.length - 1];
        String researcherFirst  = researcherNameParts[0];
        String researcherLast   = researcherNameParts[researcherNameParts.length - 1];
        return authorFirst.equals(researcherFirst) && authorLast.equals(researcherLast);
    }
}
