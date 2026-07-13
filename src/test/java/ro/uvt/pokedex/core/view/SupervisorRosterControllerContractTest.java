package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.DepartmentAffiliationService;
import ro.uvt.pokedex.core.service.security.OrgUnitAccessService;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SupervisorRosterController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class SupervisorRosterControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DepartmentAffiliationService departmentAffiliationService;
    @MockitoBean
    private DepartmentRepository departmentRepository;
    @MockitoBean
    private UserService userService;
    @MockitoBean(name = "orgUnitAccess")
    private OrgUnitAccessService orgUnitAccess;

    @Test
    void rosterSeparatesCurrentMembersFromAddCandidates() throws Exception {
        Department dept = new Department();
        dept.setId("dept-cs");
        dept.setName("Computer Science");
        when(departmentRepository.findById("dept-cs")).thenReturn(Optional.of(dept));
        when(departmentAffiliationService.listCurrentAffiliations("dept-cs"))
                .thenReturn(List.of(affiliation("ana@uvt.ro")));
        when(userService.findUsersWithResearcherProfile())
                .thenReturn(List.of(researcher("ana@uvt.ro", "Ana"), researcher("bob@uvt.ro", "Bob")));

        mockMvc.perform(get("/supervisor/departments/dept-cs/members")
                        .with(user("ana@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Computer Science")))
                // Ana is a current member; Bob is an add candidate.
                .andExpect(content().string(containsString("ana@uvt.ro")))
                .andExpect(content().string(containsString("bob@uvt.ro")));
    }

    @Test
    void addForwardsToTheServiceAndRedirectsBack() throws Exception {
        when(departmentAffiliationService.addMember("dept-cs", "bob@uvt.ro")).thenReturn(true);

        mockMvc.perform(post("/supervisor/departments/dept-cs/members/add")
                        .param("userId", "bob@uvt.ro")
                        .with(user("ana@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/supervisor/departments/dept-cs/members"));
        verify(departmentAffiliationService).addMember("dept-cs", "bob@uvt.ro");
    }

    @Test
    void removeForwardsToTheServiceAndRedirectsBack() throws Exception {
        when(departmentAffiliationService.removeMember("dept-cs", "ana@uvt.ro")).thenReturn(true);

        mockMvc.perform(post("/supervisor/departments/dept-cs/members/remove")
                        .param("userId", "ana@uvt.ro")
                        .with(user("ana@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/supervisor/departments/dept-cs/members"));
        verify(departmentAffiliationService).removeMember("dept-cs", "ana@uvt.ro");
    }

    private ro.uvt.pokedex.core.model.org.DepartmentAffiliation affiliation(String userId) {
        ro.uvt.pokedex.core.model.org.DepartmentAffiliation a =
                new ro.uvt.pokedex.core.model.org.DepartmentAffiliation();
        a.setUserId(userId);
        a.setDepartmentId("dept-cs");
        return a;
    }

    private User researcher(String email, String name) {
        User u = new User();
        u.setEmail(email);
        User.ResearcherProfile p = new User.ResearcherProfile();
        p.setFirstName(name);
        u.setResearcherProfile(p);
        return u;
    }
}
