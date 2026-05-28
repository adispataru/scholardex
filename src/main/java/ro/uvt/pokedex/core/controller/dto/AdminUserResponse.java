package ro.uvt.pokedex.core.controller.dto;

import ro.uvt.pokedex.core.model.user.User;

import java.time.Instant;
import java.util.List;

public record AdminUserResponse(
        String email,
        List<String> roles,
        boolean locked,
        ResearcherProfileResponse researcherProfile
) {
    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(
                user.getEmail(),
                user.getRoles().stream().map(Enum::name).sorted().toList(),
                user.isLocked(),
                user.getResearcherProfile() != null
                        ? ResearcherProfileResponse.from(user.getResearcherProfile())
                        : null
        );
    }

    public record ResearcherProfileResponse(
            String firstName,
            String lastName,
            String name,
            String scholarId,
            List<String> scopusId,
            List<String> wosId,
            String primaryScholardexAuthorId,
            List<String> currentAffiliationIds,
            List<String> pastAffiliationIds,
            Instant affiliationsConfirmedAt,
            String position
    ) {
        static ResearcherProfileResponse from(User.ResearcherProfile profile) {
            return new ResearcherProfileResponse(
                    profile.getFirstName(),
                    profile.getLastName(),
                    profile.getName(),
                    profile.getScholarId(),
                    profile.getScopusId(),
                    profile.getWosId(),
                    profile.getPrimaryScholardexAuthorId(),
                    profile.getCurrentAffiliationIds(),
                    profile.getPastAffiliationIds(),
                    profile.getAffiliationsConfirmedAt(),
                    profile.getPosition() != null ? profile.getPosition().name() : null
            );
        }
    }
}
