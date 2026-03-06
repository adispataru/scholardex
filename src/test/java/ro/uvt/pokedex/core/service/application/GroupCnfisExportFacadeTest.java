package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.WoSExtractor;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.application.model.GroupEditViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupListViewModel;
import ro.uvt.pokedex.core.service.reporting.CNFISReportExportService;
import ro.uvt.pokedex.core.service.reporting.CNFISScoringService2025;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupCnfisExportFacadeTest {

    @Mock
    private GroupManagementFacade groupManagementFacade;
    @Mock
    private ScopusProjectionReadService scopusProjectionReadService;
    @Mock
    private CNFISScoringService2025 cnfiSScoringService2025;
    @Mock
    private WoSExtractor woSExtractor;
    @Mock
    private CNFISReportExportService exportService;

    @InjectMocks
    private GroupCnfisExportFacade facade;

    @Test
    void buildGroupCnfisExportReturnsEmptyWhenGroupMissing() {
        when(groupManagementFacade.buildGroupEditView("missing"))
                .thenReturn(new GroupEditViewModel(null, List.of(), List.of(), List.of()));

        Optional<?> result = facade.buildGroupCnfisExport("missing", 2021, 2024);

        assertTrue(result.isEmpty());
    }

    @Test
    void buildGroupCnfisExportFiltersByYearAndUsesAllDomain() {
        Group group = new Group();
        group.setResearchers(List.of(researcher("Jane", "Doe", List.of("a1"))));

        Publication inRange = publication("p1", "f1", "2022-05-01");
        Publication outOfRange = publication("p2", "f2", "2018-03-10");
        Forum forum = new Forum();
        forum.setId("f1");

        Domain allDomain = new Domain();
        allDomain.setName("ALL");

        GroupListViewModel groupListViewModel = new GroupListViewModel(List.of(), List.of(allDomain), List.of(), List.of(), new Group());
        when(groupManagementFacade.buildGroupEditView("g1"))
                .thenReturn(new GroupEditViewModel(group, List.of(), List.of(), List.of()));
        when(groupManagementFacade.buildGroupListView()).thenReturn(groupListViewModel);
        when(scopusProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1")))
                .thenReturn(List.of(inRange, outOfRange));
        when(woSExtractor.findPublicationWosId(inRange)).thenReturn(inRange);
        when(cnfiSScoringService2025.getReport(eq(inRange), any())).thenReturn(new CNFISReport2025());
        when(scopusProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum));
        lenient().when(scopusProjectionReadService.findPublicationViewById(any())).thenReturn(Optional.empty());

        var result = facade.buildGroupCnfisExport("g1", 2021, 2024);

        assertTrue(result.isPresent());
        assertEquals(1, result.get().publications().size());
        assertEquals("p1", result.get().publications().get(0).getId());
        ArgumentCaptor<Domain> domainCaptor = ArgumentCaptor.forClass(Domain.class);
        verify(cnfiSScoringService2025).getReport(eq(inRange), domainCaptor.capture());
        assertEquals("ALL", domainCaptor.getValue().getName());
    }

    @Test
    void buildGroupCnfisZipExportReturnsOneWorkbookPerResearcherWithExpectedName() throws IOException {
        Group group = new Group();
        group.setResearchers(List.of(
                researcher("Ana", "Popescu", List.of("a1")),
                researcher("Dan", "Ionescu", List.of("a2"))
        ));
        Domain allDomain = new Domain();
        allDomain.setName("ALL");

        when(groupManagementFacade.buildGroupEditView("g1"))
                .thenReturn(new GroupEditViewModel(group, List.of(), List.of(), List.of()));
        when(groupManagementFacade.buildGroupListView())
                .thenReturn(new GroupListViewModel(List.of(), List.of(allDomain), List.of(), List.of(), new Group()));

        Publication p1 = publication("p1", "f1", "2022-01-01");
        Publication p2 = publication("p2", "f2", "2023-01-01");
        when(scopusProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(p1));
        when(scopusProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a2"))).thenReturn(List.of(p2));
        when(woSExtractor.findPublicationWosId(any(Publication.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cnfiSScoringService2025.getReport(any(Publication.class), any(Domain.class))).thenReturn(new CNFISReport2025());

        Forum f1 = new Forum();
        f1.setId("f1");
        Forum f2 = new Forum();
        f2.setId("f2");
        when(scopusProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(f1, f2));
        lenient().when(scopusProjectionReadService.findPublicationViewById(any())).thenReturn(Optional.empty());
        when(exportService.generateCNFISReportWorkbook(anyList(), anyList(), anyMap(), anyList(), eq(false)))
                .thenReturn(new byte[]{1, 2, 3});

        var result = facade.buildGroupCnfisZipExport("g1", 2021, 2024);

        assertTrue(result.isPresent());
        assertEquals(2, result.get().workbooks().size());
        assertEquals("Popescu_A_AB.xlsx", result.get().workbooks().get(0).entryName());
        assertEquals("Ionescu_D_AB.xlsx", result.get().workbooks().get(1).entryName());
        verify(exportService, times(2))
                .generateCNFISReportWorkbook(anyList(), anyList(), anyMap(), anyList(), eq(false));
    }

    @Test
    void buildGroupCnfisWorkbookExportReturnsWorkbookMetadata() throws IOException {
        Group group = new Group();
        group.setResearchers(List.of(researcher("Ana", "Popescu", List.of("a1"))));
        Domain allDomain = new Domain();
        allDomain.setName("ALL");

        when(groupManagementFacade.buildGroupEditView("g1"))
                .thenReturn(new GroupEditViewModel(group, List.of(), List.of(), List.of()));
        when(groupManagementFacade.buildGroupListView())
                .thenReturn(new GroupListViewModel(List.of(), List.of(allDomain), List.of(), List.of(), new Group()));

        Publication publication = publication("p1", "f1", "2022-01-01");
        when(scopusProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(publication));
        when(woSExtractor.findPublicationWosId(any(Publication.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cnfiSScoringService2025.getReport(any(Publication.class), any(Domain.class))).thenReturn(new CNFISReport2025());
        when(scopusProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of());
        lenient().when(scopusProjectionReadService.findPublicationViewById(any())).thenReturn(Optional.empty());
        when(exportService.generateCNFISReportWorkbook(anyList(), anyList(), anyMap(), anyList(), eq(true)))
                .thenReturn(new byte[]{9, 9, 9});

        var result = facade.buildGroupCnfisWorkbookExport("g1", 2021, 2024);

        assertTrue(result.isPresent());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", result.get().contentType());
        assertEquals("data/templates/AC2025_Anexa6-Tabel_institutional_articole_brevete-2025.xlsx", result.get().fileName());
        assertArrayEquals(new byte[]{9, 9, 9}, result.get().workbookBytes());

        ArgumentCaptor<List<Publication>> publicationCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<CNFISReport2025>> reportCaptor = ArgumentCaptor.forClass(List.class);
        verify(exportService).generateCNFISReportWorkbook(
                publicationCaptor.capture(),
                reportCaptor.capture(),
                anyMap(),
                eq(List.of("a1")),
                eq(true)
        );
        assertEquals(1, publicationCaptor.getValue().size());
        assertEquals("p1", publicationCaptor.getValue().getFirst().getId());
        assertEquals(1, reportCaptor.getValue().size());
    }

    @Test
    void buildGroupCnfisZipExportHandlesResearcherWithoutPublications() throws IOException {
        Group group = new Group();
        group.setResearchers(List.of(researcher("Ana", "Popescu", List.of("a1"))));
        Domain allDomain = new Domain();
        allDomain.setName("ALL");

        when(groupManagementFacade.buildGroupEditView("g1"))
                .thenReturn(new GroupEditViewModel(group, List.of(), List.of(), List.of()));
        when(groupManagementFacade.buildGroupListView())
                .thenReturn(new GroupListViewModel(List.of(), List.of(allDomain), List.of(), List.of(), new Group()));

        when(scopusProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of());
        when(scopusProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of());
        lenient().when(scopusProjectionReadService.findPublicationViewById(any())).thenReturn(Optional.empty());
        when(exportService.generateCNFISReportWorkbook(anyList(), anyList(), anyMap(), eq(List.of("a1")), eq(false)))
                .thenReturn(new byte[]{5, 5});

        var result = facade.buildGroupCnfisZipExport("g1", 2021, 2024);

        assertTrue(result.isPresent());
        assertEquals(1, result.get().workbooks().size());
        assertEquals("Popescu_A_AB.xlsx", result.get().workbooks().getFirst().entryName());
        assertArrayEquals(new byte[]{5, 5}, result.get().workbooks().getFirst().workbookBytes());
        verify(exportService).generateCNFISReportWorkbook(anyList(), anyList(), anyMap(), eq(List.of("a1")), eq(false));
    }

    @Test
    void buildGroupCnfisExportIncludesBoundaryYears() {
        Group group = new Group();
        group.setResearchers(List.of(researcher("Jane", "Doe", List.of("a1"))));
        Domain allDomain = new Domain();
        allDomain.setName("ALL");

        Publication start = publication("pStart", "f1", "2021-01-01");
        Publication end = publication("pEnd", "f1", "2024-12-31");
        Publication out = publication("pOut", "f1", "2025-01-01");
        Forum forum = new Forum();
        forum.setId("f1");

        when(groupManagementFacade.buildGroupEditView("g1"))
                .thenReturn(new GroupEditViewModel(group, List.of(), List.of(), List.of()));
        when(groupManagementFacade.buildGroupListView())
                .thenReturn(new GroupListViewModel(List.of(), List.of(allDomain), List.of(), List.of(), new Group()));
        when(scopusProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1")))
                .thenReturn(List.of(start, end, out));
        when(woSExtractor.findPublicationWosId(any(Publication.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cnfiSScoringService2025.getReport(any(Publication.class), any(Domain.class))).thenReturn(new CNFISReport2025());
        when(scopusProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum));
        lenient().when(scopusProjectionReadService.findPublicationViewById(any())).thenReturn(Optional.empty());

        var result = facade.buildGroupCnfisExport("g1", 2021, 2024);

        assertTrue(result.isPresent());
        assertEquals(2, result.get().publications().size());
        assertTrue(result.get().publications().stream().anyMatch(p -> "pStart".equals(p.getId())));
        assertTrue(result.get().publications().stream().anyMatch(p -> "pEnd".equals(p.getId())));
    }

    @Test
    void buildGroupCnfisExportSkipsMalformedCoverDateWithoutCrash() {
        Group group = new Group();
        group.setResearchers(List.of(researcher("Jane", "Doe", List.of("a1"))));
        Domain allDomain = new Domain();
        allDomain.setName("ALL");

        Publication valid = publication("pValid", "f1", "2022-01-01");
        Publication invalid = publication("pInvalid", "f1", "20AB-99-99");
        Forum forum = new Forum();
        forum.setId("f1");

        when(groupManagementFacade.buildGroupEditView("g1"))
                .thenReturn(new GroupEditViewModel(group, List.of(), List.of(), List.of()));
        when(groupManagementFacade.buildGroupListView())
                .thenReturn(new GroupListViewModel(List.of(), List.of(allDomain), List.of(), List.of(), new Group()));
        when(scopusProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1")))
                .thenReturn(List.of(valid, invalid));
        when(woSExtractor.findPublicationWosId(any(Publication.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cnfiSScoringService2025.getReport(any(Publication.class), any(Domain.class))).thenReturn(new CNFISReport2025());
        when(scopusProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum));
        lenient().when(scopusProjectionReadService.findPublicationViewById(any())).thenReturn(Optional.empty());

        var result = facade.buildGroupCnfisExport("g1", 2021, 2024);

        assertTrue(result.isPresent());
        assertEquals(1, result.get().publications().size());
        assertEquals("pValid", result.get().publications().getFirst().getId());
        verify(scopusProjectionReadService, never()).savePublicationView(any());
    }

    private static Researcher researcher(String firstName, String lastName, List<String> scopusIds) {
        Researcher researcher = new Researcher();
        researcher.setFirstName(firstName);
        researcher.setLastName(lastName);
        researcher.setScopusId(scopusIds);
        return researcher;
    }

    private static Publication publication(String id, String forumId, String coverDate) {
        Publication publication = new Publication();
        publication.setId(id);
        publication.setForum(forumId);
        publication.setCoverDate(coverDate);
        publication.setAuthors(List.of("a"));
        return publication;
    }
}
