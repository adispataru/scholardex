package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.scopus.Affiliation;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusAuthorRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusCitationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusPublicationRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminInstitutionReportFacadeTest {

    @Mock
    private InstitutionRepository institutionRepository;
    @Mock
    private ScopusPublicationRepository scopusPublicationRepository;
    @Mock
    private ScopusCitationRepository scopusCitationRepository;
    @Mock
    private ScopusAuthorRepository scopusAuthorRepository;
    @Mock
    private ScopusForumRepository scopusForumRepository;
    @Mock
    private IndividualReportRepository individualReportRepository;

    @InjectMocks
    private AdminInstitutionReportFacade facade;

    @Test
    void buildInstitutionPublicationsViewReturnsEmptyWhenInstitutionMissing() {
        when(institutionRepository.findById("missing")).thenReturn(Optional.empty());

        var result = facade.buildInstitutionPublicationsView("missing");

        assertTrue(result.isEmpty());
    }

    @Test
    void buildInstitutionPublicationsViewBuildsMapsAndCounts() {
        Institution institution = institution("inst", "af1");
        Publication publication = publication("p1", "e1", "f1", "2023-02-01", List.of("a1"));
        Author author = author("a1", "Author One");
        Forum forum = forum("f1", "Forum One");
        IndividualReport report = new IndividualReport();
        report.setTitle("R1");

        when(institutionRepository.findById("inst")).thenReturn(Optional.of(institution));
        when(scopusPublicationRepository.findAllByAffiliationsContaining("af1")).thenReturn(List.of(publication));
        when(scopusAuthorRepository.findByIdIn(anyCollection())).thenReturn(List.of(author));
        when(scopusForumRepository.findByIdIn(anyCollection())).thenReturn(List.of(forum));
        when(individualReportRepository.findAll()).thenReturn(List.of(report));

        var result = facade.buildInstitutionPublicationsView("inst");

        assertTrue(result.isPresent());
        assertEquals(1, result.get().publications().size());
        assertEquals(1, result.get().authorMap().size());
        assertEquals(1, result.get().forumMap().size());
        assertEquals(1, result.get().publicationsByYear().get(2023).size());
        assertEquals(1L, result.get().publicationsCountByYear().get(2023));
        assertEquals(1, result.get().individualReports().size());
    }

    @Test
    void buildInstitutionPublicationsViewSkipsMalformedPublicationDatesInYearMaps() {
        Institution institution = institution("inst", "af1");
        Publication validPublication = publication("p1", "e1", "f1", "2023-02-01", List.of("a1"));
        Publication invalidPublication = publication("p2", "e2", "f1", "bad-date", List.of("a1"));
        Author author = author("a1", "Author One");
        Forum forum = forum("f1", "Forum One");

        when(institutionRepository.findById("inst")).thenReturn(Optional.of(institution));
        when(scopusPublicationRepository.findAllByAffiliationsContaining("af1")).thenReturn(List.of(validPublication, invalidPublication));
        when(scopusAuthorRepository.findByIdIn(anyCollection())).thenReturn(List.of(author));
        when(scopusForumRepository.findByIdIn(anyCollection())).thenReturn(List.of(forum));
        when(individualReportRepository.findAll()).thenReturn(List.of());

        var result = facade.buildInstitutionPublicationsView("inst");

        assertTrue(result.isPresent());
        assertTrue(result.get().publicationsByYear().containsKey(2023));
        assertEquals(1, result.get().publicationsByYear().get(2023).size());
        assertEquals(1L, result.get().publicationsCountByYear().get(2023));
        assertEquals(2, result.get().publications().size());
    }

    @Test
    void buildInstitutionPublicationsExportBuildsCitationAuthorAndForumMaps() {
        Institution institution = institution("inst", "af1");
        Publication cited = publication("p1", "e1", "f1", "2023-02-01", List.of("a1"));
        Publication citing = publication("p2", "e2", "f2", "2024-03-01", List.of("a2"));
        Citation citation = new Citation();
        citation.setCitedId("p1");
        citation.setCitingId("p2");

        when(institutionRepository.findById("inst")).thenReturn(Optional.of(institution));
        when(scopusPublicationRepository.findAllByAffiliationsContaining("af1")).thenReturn(List.of(cited));
        when(scopusCitationRepository.findAllByCitedIdIn(List.of("p1"))).thenReturn(List.of(citation));
        when(scopusPublicationRepository.findById("p2")).thenReturn(Optional.of(citing));
        when(scopusAuthorRepository.findByIdIn(anyCollection())).thenReturn(List.of(author("a1", "A1"), author("a2", "A2")));
        when(scopusForumRepository.findByIdIn(anyCollection())).thenReturn(List.of(forum("f1", "F1"), forum("f2", "F2")));

        var result = facade.buildInstitutionPublicationsExport("inst");

        assertTrue(result.isPresent());
        assertEquals(1, result.get().publications().size());
        assertEquals(1, result.get().citationMap().size());
        assertEquals(2, result.get().authorMap().size());
        assertEquals(2, result.get().forumMap().size());
        assertEquals(1, result.get().citationMap().get("p1").size());
    }

    private static Institution institution(String id, String afid) {
        Institution institution = new Institution();
        institution.setName(id);
        Affiliation affiliation = new Affiliation();
        affiliation.setAfid(afid);
        institution.setScopusAffiliations(List.of(affiliation));
        return institution;
    }

    private static Publication publication(String id, String eid, String forumId, String coverDate, List<String> authors) {
        Publication publication = new Publication();
        publication.setId(id);
        publication.setEid(eid);
        publication.setForum(forumId);
        publication.setCoverDate(coverDate);
        publication.setAuthors(authors);
        publication.setTitle(id);
        return publication;
    }

    private static Author author(String id, String name) {
        Author author = new Author();
        author.setId(id);
        author.setName(name);
        return author;
    }

    private static Forum forum(String id, String name) {
        Forum forum = new Forum();
        forum.setId(id);
        forum.setPublicationName(name);
        return forum;
    }
}
