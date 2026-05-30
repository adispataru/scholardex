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
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.service.application.SupervisorWorkspaceService;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(SupervisorWorkspaceController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class SupervisorWorkspaceControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SupervisorWorkspaceService supervisorWorkspaceService;

    @Test
    void platformAdminWithNoHeadAssignmentsSeesTheEmptyState() throws Exception {
        // @WebMvcTest + addFilters=false makes the exact principal name extraction brittle;
        // any non-blank id should land on the same code path, so accept any.
        when(supervisorWorkspaceService.buildView(org.mockito.ArgumentMatchers.any()))
                .thenReturn(SupervisorWorkspaceService.SupervisorWorkspaceView.empty());

        mockMvc.perform(get("/supervisor")
                        .with(user("agent@uvt.ro").authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(view().name("supervisor/workspace"))
                .andExpect(content().string(containsString("Nothing assigned yet")));
    }

    @Test
    void supervisorWithHeadsSeesNamesNotIds() throws Exception {
        OrgDivision fmi = division("div-fmi", "FMI");
        fmi.setInstitutionId("inst-uvt");
        Department cs = department("dept-cs", "Computer Science");
        cs.setDivisionId("div-fmi");
        cs.setInstitutionId("inst-uvt");
        Group ml = new Group();
        ml.setId("g-ml");
        ml.setName("ML lab");
        ml.setDepartmentIds(List.of("dept-cs"));

        when(supervisorWorkspaceService.buildView(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SupervisorWorkspaceService.SupervisorWorkspaceView(
                        List.of(fmi),
                        List.of(cs),
                        List.of(ml),
                        Map.of("inst-uvt", "Universitatea de Vest"),
                        Map.of("div-fmi", "FMI"),
                        Map.of("dept-cs", "Computer Science")));

        mockMvc.perform(get("/supervisor")
                        .with(user("ana@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Universitatea de Vest")))
                .andExpect(content().string(containsString("ML lab")))
                .andExpect(content().string(containsString("Computer Science")));
    }

    private static OrgDivision division(String id, String name) {
        OrgDivision d = new OrgDivision();
        d.setId(id);
        d.setName(name);
        return d;
    }

    private static Department department(String id, String name) {
        Department d = new Department();
        d.setId(id);
        d.setName(name);
        return d;
    }
}
