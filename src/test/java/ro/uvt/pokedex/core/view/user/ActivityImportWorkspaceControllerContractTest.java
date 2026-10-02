package ro.uvt.pokedex.core.view.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.ActivityFileImportService;
import ro.uvt.pokedex.core.service.application.ActivityReviewService;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** H142 slice 2 — the researcher imports their own fișă and reviews the imported records many at once. */
@WebMvcTest(ActivityImportWorkspaceController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class ActivityImportWorkspaceControllerContractTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ActivityFileImportService importService;
    @MockitoBean
    private ActivityReviewService reviewService;

    private final User user = new User();

    @BeforeEach
    void theResearcher() {
        user.setEmail("ion.popescu@e-uvt.ro");
    }

    @Test
    void aGridIsImportedForTheSignedInResearcherOnly() throws Exception {
        when(importService.importFile(eq("ion.popescu@e-uvt.ro"), eq("fisa.xlsx"), any(), isNull()))
                .thenReturn(new ActivityFileImportService.ImportReport(ActivityFileImportService.FileKind.MUSIC_GRID,
                        12, 3, 2, 4, Map.of("CS 1.1", 12), List.of(), List.of(), "Lect.univ.dr. POPESCU ION", 0));

        mockMvc.perform(multipart("/user/workspace/activities/import-file")
                        .file(new MockMultipartFile("file", "fisa.xlsx", XLSX, new byte[]{1, 2, 3}))
                        .with(authenticated(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(12))
                .andExpect(jsonPath("$.alreadyImported").value(3))
                .andExpect(jsonPath("$.withoutYear").value(2));
    }

    @Test
    void whatIsNotAPersonsSpreadsheetIsRefusedWithAReason() throws Exception {
        mockMvc.perform(multipart("/user/workspace/activities/import-file")
                        .file(new MockMultipartFile("file", "scan.pdf", "application/pdf", new byte[]{1}))
                        .with(authenticated(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("NOT_XLSX"));
        mockMvc.perform(multipart("/user/workspace/activities/import-file")
                        .file(new MockMultipartFile("file", "gol.xlsx", XLSX, new byte[0]))
                        .with(authenticated(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("EMPTY"));
        verify(importService, never()).importFile(anyString(), anyString(), any(), any());

        when(importService.importFile(eq("ion.popescu@e-uvt.ro"), eq("anexa61.xlsx"), any(), isNull()))
                .thenReturn(new ActivityFileImportService.ImportReport(ActivityFileImportService.FileKind.INSTITUTIONAL_TABLE,
                        0, 0, 0, 0, Map.of(), List.of(), List.of(), null, 0));
        mockMvc.perform(multipart("/user/workspace/activities/import-file")
                        .file(new MockMultipartFile("file", "anexa61.xlsx", XLSX, new byte[]{1}))
                        .with(authenticated(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INSTITUTIONAL_TABLE"));
    }

    @Test
    void withoutASignedInResearcherNothingIsImported() throws Exception {
        mockMvc.perform(multipart("/user/workspace/activities/import-file")
                        .file(new MockMultipartFile("file", "fisa.xlsx", XLSX, new byte[]{1})))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/user/workspace/activities/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"a1\"],\"action\":\"DELETE\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(importService, reviewService);
    }

    @Test
    void theBulkActionsReachTheReviewWithTheResearchersEmail() throws Exception {
        when(reviewService.setFields("ion.popescu@e-uvt.ro", List.of("a1", "a2"), Map.of("Rol", "Dirijor")))
                .thenReturn(new ActivityReviewService.BulkResult(2, 0, List.of()));
        when(reviewService.markReviewed("ion.popescu@e-uvt.ro", List.of("a1")))
                .thenReturn(new ActivityReviewService.BulkResult(1, 0, List.of()));

        mockMvc.perform(post("/user/workspace/activities/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"a1\",\"a2\"],\"action\":\"SET_FIELDS\",\"values\":{\"Rol\":\"Dirijor\"}}")
                        .with(authenticated(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(2));
        mockMvc.perform(post("/user/workspace/activities/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"a1\"],\"action\":\"MARK_REVIEWED\"}")
                        .with(authenticated(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(1));
        verify(reviewService).setFields("ion.popescu@e-uvt.ro", List.of("a1", "a2"), Map.of("Rol", "Dirijor"));
    }

    @Test
    void aBulkRequestWithoutRecordsOrWithAnUnknownActionIsRefused() throws Exception {
        mockMvc.perform(post("/user/workspace/activities/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[],\"action\":\"DELETE\"}")
                        .with(authenticated(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("NOTHING_SELECTED"));
        mockMvc.perform(post("/user/workspace/activities/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"a1\"],\"action\":\"PUBLISH\"}")
                        .with(authenticated(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNKNOWN_ACTION"));
        verifyNoInteractions(reviewService);
    }

    private static RequestPostProcessor authenticated(User user) {
        return request -> {
            TestingAuthenticationToken authentication = new TestingAuthenticationToken(user, null, "RESEARCHER");
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            request.setUserPrincipal(authentication);
            return request;
        };
    }
}
