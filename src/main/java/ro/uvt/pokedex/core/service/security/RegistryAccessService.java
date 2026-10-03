package ro.uvt.pokedex.core.service.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.registry.RegistryDomainExperts;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.RegistryDomainExpertsRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * H142 slice 3, H144 — who ranks the entries of a domain, in every registry (artistic events, conferences,
 * organisations, awards): a platform admin; an expert an admin named for the domain; a head of a department that answers
 * for the domain — its director, or a head of the faculty above it ({@link OrgUnitAccessService#canManageDepartment}).
 * The rule of "never one's own" lives with the decisions: nobody ranks a name their own records give.
 */
@Service("registryAccess")
@RequiredArgsConstructor
public class RegistryAccessService {

    private final RegistryDomainExpertsRepository domainExpertsRepository;
    private final DepartmentAffiliationRepository departmentAffiliationRepository;
    private final OrgUnitAccessService orgUnitAccessService;

    /** The domains this person ranks the entries of (every configured one for an admin). */
    public Set<String> domainsOf(Authentication authentication) {
        Set<String> domains = new LinkedHashSet<>();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            return domains;
        }
        boolean admin = isPlatformAdmin(authentication);
        String me = authentication.getName().trim();
        for (RegistryDomainExperts settings : domainExpertsRepository.findAll()) {
            if (admin || named(settings, me) || headsOne(settings, authentication)) {
                domains.add(settings.getDomain());
            }
        }
        return domains;
    }

    public boolean canReview(String domain, Authentication authentication) {
        if (isPlatformAdmin(authentication)) {
            return true;
        }
        return domain != null && domainsOf(authentication).contains(domain);
    }

    /** Whether the experts' page is for this person at all (the sidebar asks). */
    public boolean canReviewAny(Authentication authentication) {
        return isPlatformAdmin(authentication) || !domainsOf(authentication).isEmpty();
    }

    /** The domains whose departments a researcher belongs to — where their proposals go. */
    public Set<String> domainsOfResearcher(String researcherEmail, List<RegistryDomainExperts> settings) {
        Set<String> domains = new LinkedHashSet<>();
        if (researcherEmail == null || researcherEmail.isBlank()) {
            return domains;
        }
        List<String> departments = departmentAffiliationRepository.findByUserIdAndValidToIsNull(researcherEmail.trim())
                .stream().map(DepartmentAffiliation::getDepartmentId).toList();
        for (RegistryDomainExperts s : settings) {
            if (s.getDepartmentIds() != null && s.getDepartmentIds().stream().anyMatch(departments::contains)) {
                domains.add(s.getDomain());
            }
        }
        return domains;
    }

    public boolean isPlatformAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(ga -> UserRole.PLATFORM_ADMIN.name().equals(ga.getAuthority()));
    }

    private static boolean named(RegistryDomainExperts settings, String me) {
        return settings.getExpertEmails() != null
                && settings.getExpertEmails().stream().anyMatch(e -> e != null && e.trim().equalsIgnoreCase(me));
    }

    private boolean headsOne(RegistryDomainExperts settings, Authentication authentication) {
        return settings.getDepartmentIds() != null && settings.getDepartmentIds().stream()
                .anyMatch(id -> orgUnitAccessService.canManageDepartment(id, authentication));
    }
}
