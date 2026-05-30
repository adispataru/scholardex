package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.application.model.GroupEditViewModel;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupExportFacadeTest {

    @Mock
    private GroupManagementFacade groupManagementFacade;
    @Mock
    private GroupMembershipService groupMembershipService;
    @Mock
    private ScholardexProjectionReadService scholardexProjectionReadService;
    @Mock
    private ResearcherAuthorLookupService researcherAuthorLookupService;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private GroupExportFacade facade;

    @BeforeEach
    void setUpLookupService() {
        lenient().when(researcherAuthorLookupService.resolveAuthorLookupKeys(any(User.ResearcherProfile.class)))
                .thenAnswer(invocation -> {
                    User.ResearcherProfile profile = invocation.getArgument(0);
                    return profile.getScopusId() == null ? List.of() : profile.getScopusId();
                });
        lenient().when(groupMembershipService.listCurrentMemberUserIds(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("jane@uvt.ro"));
    }

    @Test
    void buildGroupPublicationCsvExportReturnsEmptyWhenGroupMissing() {
        when(groupManagementFacade.buildGroupEditView("missing"))
                .thenReturn(new GroupEditViewModel(null, List.of(), List.of(), List.of(), List.of(), List.of()));

        Optional<?> result = facade.buildGroupPublicationCsvExport("missing");

        assertTrue(result.isEmpty());
    }

    @Test
    void buildGroupPublicationCsvExportBuildsPublicationAndLookupMaps() {
        User member = memberUser("jane@uvt.ro", "Jane", "Doe", List.of("a1"));
        lenient().when(userRepository.findAllById(anyCollection())).thenReturn(List.of(member));

        Group group = new Group();

        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setAuthors(List.of("a1"));
        publication.setForum("f1");

        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId("a1");
        author.setName("Jane Doe");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("f1");
        forum.setPublicationName("Forum One");

        when(groupManagementFacade.buildGroupEditView("g1"))
                .thenReturn(new GroupEditViewModel(group, List.of(), List.of(), List.of(), List.of(), List.of()));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1")))
                .thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection()))
                .thenReturn(List.of(author));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection()))
                .thenReturn(List.of(forum));

        var result = facade.buildGroupPublicationCsvExport("g1");

        assertTrue(result.isPresent());
        assertEquals(1, result.get().publications().size());
        assertEquals("Jane Doe", result.get().authorMap().get("a1").getName());
        assertEquals("Forum One", result.get().forumMap().get("f1").getPublicationName());
        assertTrue(result.get().affiliatedAuthorIds().contains("a1"));
    }

    @Test
    void buildGroupPublicationCsvExportSortsAndDedupesPublications() {
        User member = memberUser("jane@uvt.ro", "Jane", "Doe", List.of("a1"));
        lenient().when(userRepository.findAllById(anyCollection())).thenReturn(List.of(member));

        Group group = new Group();

        ScholardexPublicationView p1 = new ScholardexPublicationView();
        p1.setId("p1");
        p1.setTitle("Beta");
        p1.setCoverDate("2024-01-01");
        p1.setAuthors(List.of("a1"));
        p1.setForum("f1");

        ScholardexPublicationView p2 = new ScholardexPublicationView();
        p2.setId("p2");
        p2.setTitle("Alpha");
        p2.setCoverDate("2024-01-01");
        p2.setAuthors(List.of("a1"));
        p2.setForum("f1");

        ScholardexPublicationView malformed = new ScholardexPublicationView();
        malformed.setId("p3");
        malformed.setTitle("Zeta");
        malformed.setCoverDate("bad-date");
        malformed.setAuthors(List.of("a1"));
        malformed.setForum("f1");

        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId("a1");
        author.setName("Jane Doe");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("f1");
        forum.setPublicationName("Forum One");

        when(groupManagementFacade.buildGroupEditView("g1"))
                .thenReturn(new GroupEditViewModel(group, List.of(), List.of(), List.of(), List.of(), List.of()));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1")))
                .thenReturn(List.of(p1, malformed, p2, p1));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection()))
                .thenReturn(List.of(author));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection()))
                .thenReturn(List.of(forum));

        var result = facade.buildGroupPublicationCsvExport("g1");

        assertTrue(result.isPresent());
        assertEquals(List.of("p2", "p1", "p3"),
                result.get().publications().stream().map(p -> p.getId()).toList());
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
}
