package ro.uvt.pokedex.core.service.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ro.uvt.pokedex.core.model.ArtisticEventDomainExperts;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;
import ro.uvt.pokedex.core.repository.ArtisticEventDomainExpertsRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** H142 slice 3 — the experts of a domain: heads of the departments that answer for it, named experts, admins. */
class ArtisticEventAccessServiceTest {

    private final ArtisticEventDomainExpertsRepository settings = mock(ArtisticEventDomainExpertsRepository.class);
    private final DepartmentAffiliationRepository affiliations = mock(DepartmentAffiliationRepository.class);
    private final OrgUnitAccessService units = mock(OrgUnitAccessService.class);
    private final ArtisticEventAccessService access = new ArtisticEventAccessService(settings, affiliations, units);

    private static Authentication as(String email, String... roles) {
        return UsernamePasswordAuthenticationToken.authenticated(email, null,
                java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
    }

    private static ArtisticEventDomainExperts domain(String name, List<String> departments, List<String> experts) {
        ArtisticEventDomainExperts d = new ArtisticEventDomainExperts();
        d.setDomain(name);
        d.setDepartmentIds(new java.util.ArrayList<>(departments));
        d.setExpertEmails(new java.util.ArrayList<>(experts));
        return d;
    }

    @Test
    void headsOfTheDepartmentsANamedExpertAndAnAdminRankADomain() {
        when(settings.findAll()).thenReturn(List.of(domain("Muzică", List.of("dep-music"), List.of("critic@uvt.ro")),
                domain("Teatru şi artele spectacolului", List.of("dep-theatre"), List.of())));
        Authentication dean = as("dean@uvt.ro", "RESEARCHER");
        when(units.canManageDepartment(eq("dep-music"), eq(dean))).thenReturn(true);

        assertEquals(Set.of("Muzică"), access.domainsOf(dean));
        assertTrue(access.canReview("Muzică", dean));
        assertFalse(access.canReview("Teatru şi artele spectacolului", dean));
        assertEquals(Set.of("Muzică"), access.domainsOf(as("Critic@uvt.ro", "RESEARCHER")), "a named expert, any case");
        assertTrue(access.canReviewAny(as("admin@uvt.ro", "PLATFORM_ADMIN")));
        assertEquals(2, access.domainsOf(as("admin@uvt.ro", "PLATFORM_ADMIN")).size());
        assertFalse(access.canReviewAny(as("someone@uvt.ro", "RESEARCHER")));
    }

    @Test
    void aResearchersProposalsGoToTheDomainsOfTheirDepartments() {
        DepartmentAffiliation affiliation = new DepartmentAffiliation();
        affiliation.setDepartmentId("dep-music");
        when(affiliations.findByUserIdAndValidToIsNull("ana@uvt.ro")).thenReturn(List.of(affiliation));
        List<ArtisticEventDomainExperts> all = List.of(domain("Muzică", List.of("dep-music"), List.of()),
                domain("Teatru şi artele spectacolului", List.of("dep-theatre"), List.of()));
        assertEquals(Set.of("Muzică"), access.domainsOfResearcher("ana@uvt.ro", all));
        assertEquals(Set.of(), access.domainsOfResearcher("nobody@uvt.ro", all));
        assertFalse(access.canReview(null, as("dean@uvt.ro", "RESEARCHER")));
    }
}
