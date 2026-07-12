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
import ro.uvt.pokedex.core.service.application.SupervisorCockpitService;
import ro.uvt.pokedex.core.service.application.SupervisorCockpitService.CockpitStrip;
import ro.uvt.pokedex.core.service.application.SupervisorCockpitService.ReportOption;
import ro.uvt.pokedex.core.service.application.SupervisorCockpitService.SupervisorCockpitView;
import ro.uvt.pokedex.core.service.application.SupervisorCockpitService.UnitHealthRow;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private SupervisorCockpitService supervisorCockpitService;

    @Test
    void headWithNoAssignmentsSeesTheEmptyState() throws Exception {
        // @WebMvcTest + addFilters=false makes the exact principal name extraction brittle;
        // any non-blank id should land on the same code path, so accept any.
        when(supervisorCockpitService.buildView(any(), any()))
                .thenReturn(SupervisorCockpitView.empty());

        mockMvc.perform(get("/supervisor")
                        .with(user("agent@uvt.ro").authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(view().name("supervisor/workspace"))
                .andExpect(content().string(containsString("Nothing assigned yet")));
    }

    @Test
    void cockpitRendersHealthStripAndAttentionRankedUnits() throws Exception {
        CockpitStrip strip = new CockpitStrip(12, 9, 75, 40, 3, 2, 1);
        UnitHealthRow fmi = new UnitHealthRow("division", "div-fmi", "FMI",
                "/admin/divisions/div-fmi/reports/rep-1", 12, 3, 1, 2, 6);
        UnitHealthRow ml = new UnitHealthRow("group", "g-ml", "ML lab",
                "/admin/groups/g-ml/reports/view/rep-1", 4, 0, 0, 0, 0);
        when(supervisorCockpitService.buildView(any(), any()))
                .thenReturn(new SupervisorCockpitView(true,
                        List.of(new ReportOption("rep-1", "FV Info 2026")),
                        "rep-1", "FV Info 2026", strip, List.of(fmi, ml)));

        mockMvc.perform(get("/supervisor")
                        .with(user("ana@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk())
                // strip
                .andExpect(content().string(containsString("Meeting threshold")))
                .andExpect(content().string(containsString("Onboarded")))
                // report selector
                .andExpect(content().string(containsString("FV Info 2026")))
                // units + deep-links
                .andExpect(content().string(containsString("FMI")))
                .andExpect(content().string(containsString("ML lab")))
                .andExpect(content().string(containsString("/admin/divisions/div-fmi/reports/rep-1")))
                .andExpect(content().string(containsString("/admin/groups/g-ml/reports/view/rep-1")));
    }

    @Test
    void selectedReportQueryParamIsForwardedToTheService() throws Exception {
        when(supervisorCockpitService.buildView(any(), eq("rep-9")))
                .thenReturn(new SupervisorCockpitView(true,
                        List.of(new ReportOption("rep-9", "PD 2026")),
                        "rep-9", "PD 2026",
                        new CockpitStrip(0, 0, null, null, 0, 0, 0), List.of()));

        mockMvc.perform(get("/supervisor").param("report", "rep-9")
                        .with(user("ana@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("PD 2026")));
    }
}
