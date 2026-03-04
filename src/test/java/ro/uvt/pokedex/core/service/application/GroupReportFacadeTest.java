package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusAuthorRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusCitationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusPublicationRepository;
import ro.uvt.pokedex.core.service.reporting.ActivityReportingService;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupReportFacadeTest {

    @Mock
    private GroupRepository groupRepository;
    @Mock
    private IndividualReportRepository individualReportRepository;
    @Mock
    private ActivityInstanceRepository activityInstanceRepository;
    @Mock
    private ActivityReportingService activityReportingService;
    @Mock
    private ScientificProductionService scientificProductionService;
    @Mock
    private ScopusPublicationRepository scopusPublicationRepository;
    @Mock
    private ScopusAuthorRepository scopusAuthorRepository;
    @Mock
    private ScopusForumRepository scopusForumRepository;
    @Mock
    private ScopusCitationRepository scopusCitationRepository;

    @InjectMocks
    private GroupReportFacade facade;

    @Test
    void buildGroupPublicationsViewReturnsRedirectWhenGroupMissing() {
        var result = facade.buildGroupPublicationsView("missing");
        assertEquals(true, result.isEmpty());
    }

    @Test
    void buildGroupPublicationsViewSkipsMalformedPublicationDatesInYearMaps() {
        Group group = new Group();
        Researcher researcher = new Researcher();
        researcher.setId("r1");
        researcher.setFirstName("R");
        researcher.setLastName("One");
        researcher.setScopusId(List.of("a1"));
        group.setResearchers(new ArrayList<>(List.of(researcher)));

        Publication validPublication = new Publication();
        validPublication.setId("p1");
        validPublication.setCoverDate("2023-02-01");
        validPublication.setAuthors(List.of("a1"));
        validPublication.setForum("f1");

        Publication invalidPublication = new Publication();
        invalidPublication.setId("p2");
        invalidPublication.setCoverDate("bad-date");
        invalidPublication.setAuthors(List.of("a1"));
        invalidPublication.setForum("f1");

        Author author = new Author();
        author.setId("a1");
        author.setName("Author One");

        Forum forum = new Forum();
        forum.setId("f1");
        forum.setPublicationName("Forum One");

        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        when(scopusPublicationRepository.findAllByAuthorsIn(List.of("a1"))).thenReturn(List.of(validPublication, invalidPublication));
        when(scopusAuthorRepository.findByIdIn(anyCollection())).thenReturn(List.of(author));
        when(scopusForumRepository.findByIdIn(anyCollection())).thenReturn(List.of(forum));
        when(individualReportRepository.findAll()).thenReturn(List.of());

        var result = facade.buildGroupPublicationsView("g1");

        assertTrue(result.isPresent());
        assertTrue(result.get().publicationsByYear().containsKey(2023));
        assertEquals(1, result.get().publicationsByYear().get(2023).size());
        assertEquals(1L, result.get().publicationsCountByYear().get(2023));
        assertEquals(2, result.get().publications().size());
    }
}
