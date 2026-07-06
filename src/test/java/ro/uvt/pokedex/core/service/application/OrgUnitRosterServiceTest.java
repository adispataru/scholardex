package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgUnitRosterServiceTest {

    @Mock private DepartmentRepository departmentRepository;
    @Mock private DepartmentAffiliationRepository departmentAffiliationRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks
    private OrgUnitRosterService rosterService;

    @Test
    void divisionRosterAggregatesDepartmentsAndLabelsByOriginatingDepartment() {
        when(departmentRepository.findByDivisionId("div-fmi")).thenReturn(List.of(
                department("dept-cs", "Computer Science"),
                department("dept-math", "Mathematics")));
        when(departmentAffiliationRepository.findByDepartmentIdAndValidToIsNull("dept-cs"))
                .thenReturn(List.of(affiliation("dept-cs", "ana@uvt.ro")));
        when(departmentAffiliationRepository.findByDepartmentIdAndValidToIsNull("dept-math"))
                .thenReturn(List.of(affiliation("dept-math", "ioana@uvt.ro")));
        when(userRepository.findAllById(any())).thenReturn(List.of(
                user("ana@uvt.ro", "Ana"),
                user("ioana@uvt.ro", "Ioana")));

        List<OrgUnitRosterService.RosterMember> members = rosterService.divisionRoster("div-fmi");

        assertEquals(List.of("Ana", "Ioana"),
                members.stream().map(m -> m.user().getResearcherProfile().getName().trim()).toList());
        assertEquals("Computer Science", members.get(0).departmentLabel());
        assertEquals("Mathematics", members.get(1).departmentLabel());
    }

    @Test
    void divisionRosterDedupesJointAppointmentsAndCombinesLabels() {
        when(departmentRepository.findByDivisionId("div-fmi")).thenReturn(List.of(
                department("dept-cs", "Computer Science"),
                department("dept-math", "Mathematics")));
        when(departmentAffiliationRepository.findByDepartmentIdAndValidToIsNull("dept-cs"))
                .thenReturn(List.of(affiliation("dept-cs", "ana@uvt.ro")));
        when(departmentAffiliationRepository.findByDepartmentIdAndValidToIsNull("dept-math"))
                .thenReturn(List.of(affiliation("dept-math", "ana@uvt.ro")));
        when(userRepository.findAllById(any())).thenReturn(List.of(user("ana@uvt.ro", "Ana")));

        List<OrgUnitRosterService.RosterMember> members = rosterService.divisionRoster("div-fmi");

        assertEquals(1, members.size());
        assertEquals("Computer Science + Mathematics", members.get(0).departmentLabel());
    }

    @Test
    void rosterDropsUsersWithoutResearcherProfileAndSortsByName() {
        when(departmentAffiliationRepository.findByDepartmentIdAndValidToIsNull("dept-cs"))
                .thenReturn(List.of(
                        affiliation("dept-cs", "zoe@uvt.ro"),
                        affiliation("dept-cs", "ana@uvt.ro"),
                        affiliation("dept-cs", "noprofile@uvt.ro")));
        User noProfile = new User();
        noProfile.setEmail("noprofile@uvt.ro");
        when(userRepository.findAllById(any())).thenReturn(List.of(
                user("zoe@uvt.ro", "Zoe"),
                user("ana@uvt.ro", "Ana"),
                noProfile));

        List<OrgUnitRosterService.RosterMember> members = rosterService.departmentRoster("dept-cs");

        assertEquals(List.of("Ana", "Zoe"),
                members.stream().map(m -> m.user().getResearcherProfile().getName().trim()).toList());
        // Department rosters carry no department label.
        assertTrue(members.stream().allMatch(m -> m.departmentLabel().isEmpty()));
    }

    @Test
    void emptyAffiliationsProduceEmptyRoster() {
        when(departmentAffiliationRepository.findByDepartmentIdAndValidToIsNull("dept-cs"))
                .thenReturn(List.of());

        assertTrue(rosterService.departmentRoster("dept-cs").isEmpty());
    }

    private static Department department(String id, String name) {
        Department d = new Department();
        d.setId(id);
        d.setName(name);
        return d;
    }

    private static DepartmentAffiliation affiliation(String departmentId, String userId) {
        DepartmentAffiliation a = new DepartmentAffiliation();
        a.setDepartmentId(departmentId);
        a.setUserId(userId);
        return a;
    }

    private static User user(String email, String firstName) {
        User u = new User();
        u.setEmail(email);
        User.ResearcherProfile p = new User.ResearcherProfile();
        p.setFirstName(firstName);
        p.setLastName("");
        u.setResearcherProfile(p);
        return u;
    }
}
