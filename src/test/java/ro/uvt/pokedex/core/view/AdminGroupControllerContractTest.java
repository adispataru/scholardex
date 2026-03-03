package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.service.application.GroupCnfisExportFacade;
import ro.uvt.pokedex.core.service.application.GroupExportFacade;
import ro.uvt.pokedex.core.service.application.GroupManagementFacade;
import ro.uvt.pokedex.core.service.application.GroupReportFacade;
import ro.uvt.pokedex.core.service.application.model.GroupCnfisZipExportViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupMemberCnfisWorkbook;
import ro.uvt.pokedex.core.service.application.model.GroupWorkbookExportResult;
import ro.uvt.pokedex.core.service.importing.GroupService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminGroupController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminGroupControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GroupManagementFacade groupManagementFacade;
    @MockBean
    private GroupReportFacade groupReportFacade;
    @MockBean
    private GroupExportFacade groupExportFacade;
    @MockBean
    private GroupCnfisExportFacade groupCnfisExportFacade;
    @MockBean
    private GroupService groupService;

    @Test
    void exportCnfis2025ReturnsWorkbookHeadersAndBody() throws Exception {
        byte[] bytes = new byte[]{1, 2, 3};
        when(groupCnfisExportFacade.buildGroupCnfisWorkbookExport(eq("g1"), eq(2021), eq(2024)))
                .thenReturn(Optional.of(new GroupWorkbookExportResult(
                        bytes,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "data/templates/AC2025_Anexa6-Tabel_institutional_articole_brevete-2025.xlsx"
                )));

        mockMvc.perform(get("/admin/groups/{id}/publications/exportCNFIS2025", "g1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"data/templates/AC2025_Anexa6-Tabel_institutional_articole_brevete-2025.xlsx\""))
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void exportCnfis2025MissingGroupReturnsEmptyResponseWithoutHeaders() throws Exception {
        when(groupCnfisExportFacade.buildGroupCnfisWorkbookExport(eq("missing"), eq(2021), eq(2024)))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/groups/{id}/publications/exportCNFIS2025", "missing"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Disposition"));
    }

    @Test
    void exportAllReportsReturnsZipResponseContract() throws Exception {
        when(groupCnfisExportFacade.buildGroupCnfisZipExport(eq("g1"), eq(2021), eq(2024)))
                .thenReturn(Optional.of(new GroupCnfisZipExportViewModel(
                        List.of(new GroupMemberCnfisWorkbook("Popescu_A_AB.xlsx", new byte[]{7, 8, 9}))
                )));

        byte[] responseBytes = mockMvc.perform(get("/admin/groups/{id}/publications/exportAllReports", "g1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=group_reports.zip"))
                .andExpect(content().contentType("application/zip"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertTrue(responseBytes.length > 4);
        assertTrue(responseBytes[0] == 'P' && responseBytes[1] == 'K');
    }
}
