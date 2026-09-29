package ro.uvt.pokedex.core.service.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * Who decides on a declaration of principal authorship. Cast: the Psychology department of a faculty; the
 * director heads the department, the dean heads the faculty, another director heads a sibling department.
 */
@ExtendWith(MockitoExtension.class)
class PrincipalAuthorDeclarationAccessServiceTest {

    @Mock
    private DepartmentAffiliationRepository affiliations;
    @Mock
    private DepartmentRepository departments;
    @Mock
    private OrgDivisionRepository divisions;

    private PrincipalAuthorDeclarationAccessService access;

    @BeforeEach
    void setUp() {
        access = new PrincipalAuthorDeclarationAccessService(affiliations, new OrgUnitAccessService(departments, divisions));

        OrgDivision faculty = new OrgDivision();
        faculty.setId("fpse");
        faculty.setHeadUserIds(new ArrayList<>(List.of("dean@uvt.ro")));
        lenient().when(divisions.findById("fpse")).thenReturn(Optional.of(faculty));
        lenient().when(departments.findById("psy")).thenReturn(Optional.of(department("psy", "director.psy@uvt.ro")));
        lenient().when(departments.findById("edu")).thenReturn(Optional.of(department("edu", "director.edu@uvt.ro")));

        lenient().when(affiliations.findByUserIdAndValidToIsNull("researcher@uvt.ro")).thenReturn(List.of(in("psy")));
        lenient().when(affiliations.findByUserIdAndValidToIsNull("director.psy@uvt.ro")).thenReturn(List.of(in("psy")));
        lenient().when(affiliations.findByUserIdAndValidToIsNull("nowhere@uvt.ro")).thenReturn(List.of());
    }

    private static Department department(String id, String director) {
        Department department = new Department();
        department.setId(id);
        department.setDivisionId("fpse");
        department.setHeadUserIds(new ArrayList<>(List.of(director)));
        return department;
    }

    private static DepartmentAffiliation in(String departmentId) {
        DepartmentAffiliation affiliation = new DepartmentAffiliation();
        affiliation.setDepartmentId(departmentId);
        return affiliation;
    }

    private static Authentication as(String email, boolean head, UserRole... stored) {
        User user = new User();
        user.setEmail(email);
        user.setRoles(new HashSet<>(Set.of(stored)));
        user.setSupervisorByPosition(head);
        return UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
    }

    @Test
    void theDirectorOfTheDepartmentAndTheHeadOfTheFacultyDecide() {
        assertTrue(access.canDecide("researcher@uvt.ro", as("director.psy@uvt.ro", true, UserRole.RESEARCHER)));
        assertTrue(access.canDecide("researcher@uvt.ro", as("dean@uvt.ro", true, UserRole.RESEARCHER)));
    }

    @Test
    void theDirectorOfAnotherDepartmentDoesNot() {
        assertFalse(access.canDecide("researcher@uvt.ro", as("director.edu@uvt.ro", true, UserRole.RESEARCHER)));
    }

    @Test
    void aSupervisorWhoHeadsNoUnitOfTheResearcherDoesNot() {
        // The role alone — a group supervisor, or a stored SUPERVISOR role — is not the line of the unit.
        assertFalse(access.canDecide("researcher@uvt.ro",
                as("group.lead@uvt.ro", false, UserRole.RESEARCHER, UserRole.SUPERVISOR)));
        assertFalse(access.canDecide("researcher@uvt.ro", as("colleague@uvt.ro", false, UserRole.RESEARCHER)));
    }

    @Test
    void nobodyDecidesOnTheirOwnDeclaration() {
        // The director's own declarations go to the faculty.
        assertFalse(access.canDecide("director.psy@uvt.ro", as("director.psy@uvt.ro", true, UserRole.RESEARCHER)));
        assertTrue(access.canDecide("director.psy@uvt.ro", as("dean@uvt.ro", true, UserRole.RESEARCHER)));
        assertFalse(access.canDecide("admin@uvt.ro", as("admin@uvt.ro", false, UserRole.PLATFORM_ADMIN)));
        assertFalse(access.canDecide("Admin@UVT.ro", as("admin@uvt.ro", false, UserRole.PLATFORM_ADMIN)));
    }

    @Test
    void aPlatformAdminDecidesForAnybodyElse() {
        assertTrue(access.canDecide("researcher@uvt.ro", as("admin@uvt.ro", false, UserRole.PLATFORM_ADMIN)));
        assertTrue(access.canDecide("nowhere@uvt.ro", as("admin@uvt.ro", false, UserRole.PLATFORM_ADMIN)));
    }

    @Test
    void aResearcherWithoutADepartmentHasOnlyTheAdmins() {
        assertFalse(access.canDecide("nowhere@uvt.ro", as("dean@uvt.ro", true, UserRole.RESEARCHER)));
    }

    @Test
    void withoutASignedInPersonOrAResearcherNothingIsAllowed() {
        assertFalse(access.canDecide("researcher@uvt.ro", null));
        assertFalse(access.canDecide(null, as("dean@uvt.ro", true, UserRole.RESEARCHER)));
        assertFalse(access.canDecide(" ", as("dean@uvt.ro", true, UserRole.RESEARCHER)));
    }
}
