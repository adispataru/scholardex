package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.service.application.ChangelogService;
import ro.uvt.pokedex.core.service.application.model.ChangelogEntry;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(ChangelogViewController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class ChangelogViewControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChangelogService changelogService;

    private static ChangelogEntry entry(String title, ChangelogEntry.Audience audience, boolean impact) {
        return new ChangelogEntry(LocalDate.of(2026, 7, 25), title, "Ce s-a schimbat.", audience, impact,
                ChangelogEntry.Scope.REPORT, List.of("FV Info 2026"), List.of("Perspectiva D"));
    }

    private static Map<LocalDate, List<ChangelogEntry>> grouped(List<ChangelogEntry> entries) {
        Map<LocalDate, List<ChangelogEntry>> map = new LinkedHashMap<>();
        entries.forEach(e -> map.computeIfAbsent(e.date(), k -> new java.util.ArrayList<>()).add(e));
        return map;
    }

    @Test
    void researcherSeesSharedEntriesAndTheScoringImpactMarker() throws Exception {
        List<ChangelogEntry> visible = List.of(
                entry("Conferințele ACM intră în categoria C", ChangelogEntry.Audience.RESEARCHER, true),
                entry("Ora reală în istoricul de sincronizare", ChangelogEntry.Audience.ALL, false));
        when(changelogService.groupedByDate(false)).thenReturn(grouped(visible));
        when(changelogService.entriesFor(false)).thenReturn(visible);

        mockMvc.perform(get("/changelog").with(researcher()))
                .andExpect(status().isOk())
                .andExpect(view().name("changelog"))
                .andExpect(content().string(containsString("Conferințele ACM intră în categoria C")))
                .andExpect(content().string(containsString("Ora reală în istoricul de sincronizare")))
                .andExpect(content().string(containsString("afectează punctajul")))
                .andExpect(content().string(containsString("app-changelog__entry--impact")))
                .andExpect(content().string(containsString("FV Info 2026")))
                .andExpect(content().string(containsString("app-changelog__scope-chip")))
                // the admin-only editing footnote must not leak to researchers
                .andExpect(content().string(not(containsString("changelog.json"))));
    }

    @Test
    void adminViewRequestsTheAdminVisibleSetAndShowsTheMaintenanceFootnote() throws Exception {
        List<ChangelogEntry> visible = List.of(entry("Coadă de unificare", ChangelogEntry.Audience.ADMIN, false));
        when(changelogService.groupedByDate(true)).thenReturn(grouped(visible));
        when(changelogService.entriesFor(true)).thenReturn(visible);

        mockMvc.perform(get("/changelog").with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Coadă de unificare")))
                .andExpect(content().string(containsString("administrare")))
                .andExpect(content().string(containsString("changelog.json")));
    }

    @Test
    void emptyChangelogRendersTheEmptyStateInsteadOfAnError() throws Exception {
        when(changelogService.groupedByDate(false)).thenReturn(Map.of());
        when(changelogService.entriesFor(false)).thenReturn(List.of());

        mockMvc.perform(get("/changelog").with(researcher()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nu există încă intrări")));
    }

    private RequestPostProcessor researcher() {
        return authenticated("RESEARCHER");
    }

    private RequestPostProcessor admin() {
        return authenticated("PLATFORM_ADMIN");
    }

    private RequestPostProcessor authenticated(String authority) {
        return request -> {
            TestingAuthenticationToken authentication =
                    new TestingAuthenticationToken("u@uvt.ro", null, authority);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            request.setUserPrincipal(authentication);
            return request;
        };
    }
}
