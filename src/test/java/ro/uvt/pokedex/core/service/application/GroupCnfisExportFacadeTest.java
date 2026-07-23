package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.WoSExtractor;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupCnfisExportFacadeTest {

    @Mock
    private GroupManagementFacade groupManagementFacade;
    @Mock
    private GroupMembershipService groupMembershipService;
    @Mock
    private ScholardexProjectionReadService scholardexProjectionReadService;
    @Mock
    private PublicationEnrichmentLinkerService publicationEnrichmentLinkerService;
    @Mock
    private CNFISScoringService2025 cnfiSScoringService2025;
    @Mock
    private WoSExtractor woSExtractor;
    @Mock
    private CNFISReportExportService exportService;
    @Mock
    private ResearcherAuthorLookupService researcherAuthorLookupService;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private GroupCnfisExportFacade facade;

    @BeforeEach
    void setUpLookupService() {
        lenient().when(researcherAuthorLookupService.resolveAuthorLookupKeys(any(User.ResearcherProfile.class)))
                .thenAnswer(invocation -> {
                    User.ResearcherProfile profile = invocation.getArgument(0);
                    return profile.getScopusId() == null ? List.of() : profile.getScopusId();
                });
        lenient().when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    var ids = (java.util.Collection<String>) invocation.getArgument(0);
                    return ids.stream().map(id -> {
                        var author = new ScholardexAuthorView();
                        author.setId(id);
                        return author;
                    }).toList();
                });
    }

    @Test
    void buildGroupCnfisExportReturnsEmptyWhenGroupMissing() {
        when(groupManagementFacade.buildGroupEditView("missing"))
                .thenReturn(new GroupEditViewModel(null, List.of(), List.of(), List.of(), List.of(), List.of()));

        Optional<?> result = facade.buildGroupCnfisExport("missing", 2021, 2024);

        assertTrue(result.isEmpty());
    }

    @Test
    void buildGroupCnfisExportFiltersByYearAndUsesAllDomain() {
        lenient().when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(memberUser("jane@uvt.ro", "Jane", "Doe", List.of("a1"))));
        Group group = new Group();
        when(groupMembershipService.listCurrentMemberUserIds(any())).thenReturn(List.of("jane@uvt.ro"));

        ScholardexPublicationView inRange = publication("p1", "f1", "2022-05-01");
        ScholardexPublicationView outOfRange = publication("p2", "f2", "2018-03-10");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("f1");

        Domain allDomain = new Domain();
        allDomain.setName("ALL");

        GroupListViewModel groupListViewModel = new GroupListViewModel(List.of(), List.of(allDomain), List.of(), List.of(), List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), new Group());
        when(groupManagementFacade.buildGroupEditView("g1"))
                .thenReturn(new GroupEditViewModel(group, List.of(), List.of(), List.of(), List.of(), List.of()));
        when(groupManagementFacade.buildGroupListView()).thenReturn(groupListViewModel);
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1")))
                .thenReturn(List.of(inRange, outOfRange));
        when(publicationEnrichmentLinkerService.linkWosEnrichment(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PublicationEnrichmentLinkerService.LinkResult(
                        PublicationEnrichmentLinkerService.LinkState.LINKED,
                        "linked",
                        "p1",
                        null
                ));
        when(cnfiSScoringService2025.getReport(any(ScoringPublicationReadModel.class), any())).thenReturn(new CNFISReport2025());
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum));

        var result = facade.buildGroupCnfisExport("g1", 2021, 2024);

        assertTrue(result.isPresent());
        assertEquals(1, result.get().publications().size());
        assertEquals("p1", result.get().publications().get(0).getId());
        ArgumentCaptor<Domain> domainCaptor = ArgumentCaptor.forClass(Domain.class);
        verify(cnfiSScoringService2025).getReport(any(ScoringPublicationReadModel.class), domainCaptor.capture());
        assertEquals("ALL", domainCaptor.getValue().getName());
    }

    @Test
    void buildGroupCnfisZipExportReturnsOneWorkbookPerResearcherWithExpectedName() throws IOException {
        lenient().when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(
                        memberUser("ana@uvt.ro", "Ana", "Popescu", List.of("a1")),
                        memberUser("dan@uvt.ro", "Dan", "Ionescu", List.of("a2"))
                ));
        Group group = new Group();
        when(groupMembershipService.listCurrentMemberUserIds(any())).thenReturn(List.of("ana@uvt.ro", "dan@uvt.ro"));
        Domain allDomain = new Domain();
        allDomain.setName("ALL");

        when(groupManagementFacade.buildGroupEditView("g1"))
                .thenReturn(new GroupEditViewModel(group, List.of(), List.of(), List.of(), List.of(), List.of()));
        when(groupManagementFacade.buildGroupListView())
                .thenReturn(new GroupListViewModel(List.of(), List.of(allDomain), List.of(), List.of(), List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), new Group()));

        ScholardexPublicationView p1 = publication("p1", "f1", "2022-01-01");
        ScholardexPublicationView p2 = publication("p2", "f2", "2023-01-01");
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(p1));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a2"))).thenReturn(List.of(p2));
        when(publicationEnrichmentLinkerService.linkWosEnrichment(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PublicationEnrichmentLinkerService.LinkResult(
                        PublicationEnrichmentLinkerService.LinkState.LINKED,
                        "linked",
                        "p1",
                        null
                ));
        when(cnfiSScoringService2025.getReport(any(ScoringPublicationReadModel.class), any(Domain.class))).thenReturn(new CNFISReport2025());

        ScholardexForumView f1 = new ScholardexForumView();
        f1.setId("f1");
        ScholardexForumView f2 = new ScholardexForumView();
        f2.setId("f2");
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(f1, f2));
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
        lenient().when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(memberUser("ana@uvt.ro", "Ana", "Popescu", List.of("a1"))));
        Group group = new Group();
        when(groupMembershipService.listCurrentMemberUserIds(any())).thenReturn(List.of("ana@uvt.ro"));
        Domain allDomain = new Domain();
        allDomain.setName("ALL");

        when(groupManagementFacade.buildGroupEditView("g1"))
                .thenReturn(new GroupEditViewModel(group, List.of(), List.of(), List.of(), List.of(), List.of()));
        when(groupManagementFacade.buildGroupListView())
                .thenReturn(new GroupListViewModel(List.of(), List.of(allDomain), List.of(), List.of(), List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), new Group()));

        ScholardexPublicationView publication = publication("p1", "f1", "2022-01-01");
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(publication));
        when(publicationEnrichmentLinkerService.linkWosEnrichment(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PublicationEnrichmentLinkerService.LinkResult(
                        PublicationEnrichmentLinkerService.LinkState.LINKED,
                        "linked",
                        "p1",
                        null
                ));
        when(cnfiSScoringService2025.getReport(any(ScoringPublicationReadModel.class), any(Domain.class))).thenReturn(new CNFISReport2025());
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of());
        when(exportService.generateCNFISReportWorkbook(anyList(), anyList(), anyMap(), anyList(), eq(true)))
                .thenReturn(new byte[]{9, 9, 9});

        var result = facade.buildGroupCnfisWorkbookExport("g1", 2021, 2024);

        assertTrue(result.isPresent());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", result.get().contentType());
        assertEquals("data/templates/AC2025_Anexa6-Tabel_institutional_articole_brevete-2025.xlsx", result.get().fileName());
        assertArrayEquals(new byte[]{9, 9, 9}, result.get().workbookBytes());

        ArgumentCaptor<List<ScoringPublicationReadModel>> publicationCaptor = ArgumentCaptor.forClass(List.class);
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
        lenient().when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(memberUser("ana@uvt.ro", "Ana", "Popescu", List.of("a1"))));
        Group group = new Group();
        when(groupMembershipService.listCurrentMemberUserIds(any())).thenReturn(List.of("ana@uvt.ro"));
        Domain allDomain = new Domain();
        allDomain.setName("ALL");

        when(groupManagementFacade.buildGroupEditView("g1"))
                .thenReturn(new GroupEditViewModel(group, List.of(), List.of(), List.of(), List.of(), List.of()));
        when(groupManagementFacade.buildGroupListView())
                .thenReturn(new GroupListViewModel(List.of(), List.of(allDomain), List.of(), List.of(), List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), new Group()));

        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of());
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of());
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
        lenient().when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(memberUser("jane@uvt.ro", "Jane", "Doe", List.of("a1"))));
        Group group = new Group();
        when(groupMembershipService.listCurrentMemberUserIds(any())).thenReturn(List.of("jane@uvt.ro"));
        Domain allDomain = new Domain();
        allDomain.setName("ALL");

        ScholardexPublicationView start = publication("pStart", "f1", "2021-01-01");
        ScholardexPublicationView end = publication("pEnd", "f1", "2024-12-31");
        ScholardexPublicationView out = publication("pOut", "f1", "2025-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("f1");

        when(groupManagementFacade.buildGroupEditView("g1"))
                .thenReturn(new GroupEditViewModel(group, List.of(), List.of(), List.of(), List.of(), List.of()));
        when(groupManagementFacade.buildGroupListView())
                .thenReturn(new GroupListViewModel(List.of(), List.of(allDomain), List.of(), List.of(), List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), new Group()));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1")))
                .thenReturn(List.of(start, end, out));
        when(publicationEnrichmentLinkerService.linkWosEnrichment(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PublicationEnrichmentLinkerService.LinkResult(
                        PublicationEnrichmentLinkerService.LinkState.LINKED,
                        "linked",
                        "p1",
                        null
                ));
        when(cnfiSScoringService2025.getReport(any(ScoringPublicationReadModel.class), any(Domain.class))).thenReturn(new CNFISReport2025());
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum));

        var result = facade.buildGroupCnfisExport("g1", 2021, 2024);

        assertTrue(result.isPresent());
        assertEquals(2, result.get().publications().size());
        assertTrue(result.get().publications().stream().anyMatch(p -> "pStart".equals(p.getId())));
        assertTrue(result.get().publications().stream().anyMatch(p -> "pEnd".equals(p.getId())));
    }

    @Test
    void buildGroupCnfisExportSkipsMalformedCoverDateWithoutCrash() {
        lenient().when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(memberUser("jane@uvt.ro", "Jane", "Doe", List.of("a1"))));
        Group group = new Group();
        when(groupMembershipService.listCurrentMemberUserIds(any())).thenReturn(List.of("jane@uvt.ro"));
        Domain allDomain = new Domain();
        allDomain.setName("ALL");

        ScholardexPublicationView valid = publication("pValid", "f1", "2022-01-01");
        ScholardexPublicationView invalid = publication("pInvalid", "f1", "20AB-99-99");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("f1");

        when(groupManagementFacade.buildGroupEditView("g1"))
                .thenReturn(new GroupEditViewModel(group, List.of(), List.of(), List.of(), List.of(), List.of()));
        when(groupManagementFacade.buildGroupListView())
                .thenReturn(new GroupListViewModel(List.of(), List.of(allDomain), List.of(), List.of(), List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), new Group()));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1")))
                .thenReturn(List.of(valid, invalid));
        when(publicationEnrichmentLinkerService.linkWosEnrichment(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PublicationEnrichmentLinkerService.LinkResult(
                        PublicationEnrichmentLinkerService.LinkState.LINKED,
                        "linked",
                        "pValid",
                        null
                ));
        when(cnfiSScoringService2025.getReport(any(ScoringPublicationReadModel.class), any(Domain.class))).thenReturn(new CNFISReport2025());
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum));

        var result = facade.buildGroupCnfisExport("g1", 2021, 2024);

        assertTrue(result.isPresent());
        assertEquals(1, result.get().publications().size());
        assertEquals("pValid", result.get().publications().getFirst().getId());
        verify(publicationEnrichmentLinkerService).linkWosEnrichment(eq("pValid"), any(), any(), any(), any(), any(), any());
    }

    private static User memberUser(String email, String firstName, String lastName, List<String> scopusIds) {
        User user = new User();
        user.setEmail(email);
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setFirstName(firstName);
        profile.setLastName(lastName);
        profile.setScopusId(new java.util.ArrayList<>(scopusIds));
        user.setResearcherProfile(profile);
        return user;
    }

    private static ScholardexPublicationView publication(String id, String forumId, String coverDate) {
        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId(id);
        publication.setForum(forumId);
        publication.setCoverDate(coverDate);
        publication.setAuthors(List.of("a"));
        return publication;
    }
}
