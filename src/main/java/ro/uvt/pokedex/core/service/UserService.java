package ro.uvt.pokedex.core.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.UserRepository;
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

    @Autowired
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
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

    public Optional<User> updateUser(String email, User updatedUser) {
        return userRepository.findById(email)
                .map(user -> userRepository.save(updatedUser));
    }

    public void deleteUser(String email) {
        userRepository.deleteById(email);
    }

    public void lockUser(String email) {
        userRepository.findById(email).ifPresent(user -> {
            user.setLocked(true);
            userRepository.save(user);
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
        profile.setScholarId(incoming.getScholarId());
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
