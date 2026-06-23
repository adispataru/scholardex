package ro.uvt.pokedex.core.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.service.user.UserDeactivatedEvent;
import ro.uvt.pokedex.core.utils.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
    }

    public Optional<User> createUser(User user) {
        if(!userExists(user.getEmail())) {
            return Optional.of(userRepository.save(user));
        }
        return Optional.empty();
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public Optional<User> getUserByEmail(String email) {
        return userRepository.findById(email);
    }

    /**
     * Resolves emails to "Full Name &lt;email&gt;" labels for display in admin lists. Unknown
     * emails are returned as-is so callers can show the raw value as a fallback hint that
     * the underlying user is missing.
     */
    public java.util.Map<String, String> findDisplayLabels(java.util.Collection<String> emails) {
        if (emails == null || emails.isEmpty()) return java.util.Map.of();
        java.util.LinkedHashSet<String> clean = new java.util.LinkedHashSet<>();
        for (String e : emails) if (e != null && !e.isBlank()) clean.add(e);
        if (clean.isEmpty()) return java.util.Map.of();
        java.util.Map<String, String> labels = new java.util.HashMap<>();
        for (User u : userRepository.findAllById(clean)) {
            User.ResearcherProfile p = u.getResearcherProfile();
            String name = (p == null || p.getName() == null) ? "" : p.getName().trim();
            labels.put(u.getEmail(), name.isEmpty() ? u.getEmail() : name + " <" + u.getEmail() + ">");
        }
        // Pass-through for emails that don't resolve so the caller can flag them.
        for (String e : clean) labels.putIfAbsent(e, e);
        return labels;
    }

    public Optional<User> updateUser(String email, User updatedUser) {
        return userRepository.findById(email)
                .map(user -> userRepository.save(updatedUser));
    }

    public void deleteUser(String email) {
        if (email == null || email.isBlank()) return;
        userRepository.deleteById(email);
        eventPublisher.publishEvent(new UserDeactivatedEvent(email, "deleted"));
    }

    public void lockUser(String email) {
        userRepository.findById(email).ifPresent(user -> {
            user.setLocked(true);
            userRepository.save(user);
            eventPublisher.publishEvent(new UserDeactivatedEvent(email, "locked"));
        });
    }

    public void updateUserRoles(String email, List<String> newRoles) {
        userRepository.findById(email).ifPresent(user -> {
            if(newRoles != null) {
                user.getRoles().removeIf(role -> !newRoles.contains(role.name()));
                user.getRoles().addAll(parseRoles(newRoles));
                userRepository.save(user);
            }
        });
    }

    public Optional<User> createUser(String email, String password, List<String> roles) {
        if(!userExists(email)) {
            User user = new User();
            user.setEmail(email);
            user.setPassword(passwordEncoder.encode(password));
            user.setRoles(parseRoles(roles));
            return Optional.of(userRepository.save(user));
        }
        return Optional.empty();
    }

    public boolean areValidRoleNames(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        try {
            parseRoles(roles);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public Set<UserRole> parseRoles(List<String> roles) {
        Set<UserRole> resolvedRoles = new HashSet<>();
        for (String roleName : roles) {
            resolvedRoles.add(parseRoleOrThrow(roleName));
        }
        return resolvedRoles;
    }

    public UserRole parseRoleOrThrow(String roleName) {
        return UserRole.valueOf(roleName);
    }

    public boolean userExists(String email){
        return userRepository.findById(email).isPresent();
    }

    // ── Researcher-profile operations ────────────────────────────────────

    /** Returns all users that have a non-null researcher profile. */
    public List<User> findUsersWithResearcherProfile() {
        return userRepository.findAllByResearcherProfileIsNotNull();
    }

    /**
     * Upserts the researcher profile on the user identified by {@code email}.
     * Creates a new profile if none exists.
     */
    public User saveResearcherProfile(String email, User.ResearcherProfile profile) {
        User user = userRepository.findById(email)
                .orElseThrow(() -> new IllegalArgumentException("No user found with email: " + email));
        applyProfile(user, profile);
        return userRepository.save(user);
    }

    /**
     * Updates the researcher profile on an existing user. Throws if the user
     * does not exist.
     */
    public User updateResearcherProfile(String email, User.ResearcherProfile profile) {
        return saveResearcherProfile(email, profile);
    }

    /** Removes the researcher profile from the user (account is kept). */
    public void deleteResearcherProfile(String email) {
        userRepository.findById(email).ifPresent(user -> {
            user.setResearcherProfile(null);
            userRepository.save(user);
        });
    }

    /**
     * Finds the user whose researcher profile name best matches the given
     * author name (first + last token comparison, normalized).
     */
    public Optional<User> matchAuthorToUser(String authorName) {
        String normalized = StringUtils.normalize(authorName);
        String[] parts = normalized.split("\\s+");
        for (User user : findUsersWithResearcherProfile()) {
            User.ResearcherProfile profile = user.getResearcherProfile();
            String researcherName = StringUtils.normalize(profile.getName());
            String[] researcherParts = researcherName.split("\\s+");
            if (nameMatches(parts, researcherParts)) {
                return Optional.of(user);
            }
        }
        return Optional.empty();
    }

    private void applyProfile(User user, User.ResearcherProfile incoming) {
        User.ResearcherProfile profile = user.getResearcherProfile();
        if (profile == null) profile = new User.ResearcherProfile();
        profile.setFirstName(incoming.getFirstName());
        profile.setLastName(incoming.getLastName());
        // Google Scholar id is retired from the UI but still an author-resolution lookup key; preserve any existing
        // value when a caller doesn't supply one rather than wiping it.
        if (incoming.getScholarId() != null) profile.setScholarId(incoming.getScholarId());
        profile.setOrcid(incoming.getOrcid());
        profile.setScopusId(incoming.getScopusId() != null ? incoming.getScopusId() : new ArrayList<>());
        profile.setWosId(incoming.getWosId() != null ? incoming.getWosId() : new ArrayList<>());
        profile.setPrimaryScholardexAuthorId(incoming.getPrimaryScholardexAuthorId());
        if (incoming.getCurrentAffiliationIds() != null && !incoming.getCurrentAffiliationIds().isEmpty()) {
            profile.setCurrentAffiliationIds(incoming.getCurrentAffiliationIds());
        } else if (profile.getCurrentAffiliationIds() == null) {
            profile.setCurrentAffiliationIds(new ArrayList<>());
        }
        if (incoming.getPastAffiliationIds() != null && !incoming.getPastAffiliationIds().isEmpty()) {
            profile.setPastAffiliationIds(incoming.getPastAffiliationIds());
        } else if (profile.getPastAffiliationIds() == null) {
            profile.setPastAffiliationIds(new ArrayList<>());
        }
        if (incoming.getAffiliationsConfirmedAt() != null) {
            profile.setAffiliationsConfirmedAt(incoming.getAffiliationsConfirmedAt());
        }
        profile.setPosition(incoming.getPosition());
        user.setResearcherProfile(profile);
    }

    private boolean nameMatches(String[] authorParts, String[] researcherParts) {
        if (authorParts.length == 0 || researcherParts.length == 0) return false;
        String authorFirst     = authorParts[0];
        String authorLast      = authorParts[authorParts.length - 1];
        String researcherFirst = researcherParts[0];
        String researcherLast  = researcherParts[researcherParts.length - 1];
        return authorFirst.equals(researcherFirst) && authorLast.equals(researcherLast);
    }
}
