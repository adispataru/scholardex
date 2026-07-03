package ro.uvt.pokedex.core.model.user;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.lang.NonNull;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import ro.uvt.pokedex.core.model.reporting.Position;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Document(collection = "scholardex.users")
@Data
public class User implements UserDetails {
    @Id
    private String email; // Used as the username

    private String password;
    private Set<UserRole> roles = new HashSet<>();

    /**
     * Embedded researcher profile. Non-null for users who have a researcher
     * identity; null for admin / system accounts.
     */
    private ResearcherProfile researcherProfile;

    @Transient
    private List<SimpleGrantedAuthority> authority;
    private boolean locked = false;

    @NonNull
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (authority == null) {
            authority = roles.stream()
                    .map(role -> new SimpleGrantedAuthority(role.name()))
                    .collect(Collectors.toList());
        }
        return authority;
    }

    @NonNull
    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return !locked;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !locked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return !locked;
    }

    @Override
    public boolean isEnabled() {
        return !locked;
    }

    public boolean hasRole(String roleName) {
        return roles.stream().anyMatch(role -> role.name().equals(roleName));
    }

    // Constructors, getters, and setters

    /**
     * Embedded document holding the researcher profile data.
     * Stored inline in the {@code scholardex.users} collection.
     */
    @Data
    public static class ResearcherProfile {
        private String firstName;
        private String lastName;
        /**
         * Year the researcher was awarded their PhD/doctorate. Nullable — many researchers won't have it set, and
         * criteria using a post-PhD temporal anchor (H68) must treat a missing value as "no anchor" (count all).
         */
        private Integer phdAwardYear;
        private String scholarId;
        private List<String> scopusId = new ArrayList<>();
        private List<String> wosId = new ArrayList<>();
        /** ORCID iD (bare 16-digit form, e.g. 0000-0002-1825-0097). 1:1 per researcher; drives the OpenAlex author sync (H66B Phase 4a). */
        private String orcid;
        private String primaryScholardexAuthorId;
        /**
         * The canonical Scholardex author records the researcher has confirmed as theirs (H70 onboarding).
         * A researcher can map to several author facts (e.g. name-order splits across sources);
         * {@link #primaryScholardexAuthorId} is the designated one of this set. Non-destructive — the records are
         * not merged, just pooled for lookup.
         */
        private List<String> confirmedScholardexAuthorIds = new ArrayList<>();
        private List<String> currentAffiliationIds = new ArrayList<>();
        private List<String> pastAffiliationIds = new ArrayList<>();
        private Instant affiliationsConfirmedAt;
        /** Set when the researcher finishes the H70 onboarding wizard (its terminal publication-claim step). */
        private Instant onboardingCompletedAt;
        private Position position;

        public String getName() {
            return firstName + " " + lastName;
        }
    }
}
