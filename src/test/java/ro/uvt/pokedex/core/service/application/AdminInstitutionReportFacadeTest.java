package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;

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
    private ScholardexProjectionReadService scholardexProjectionReadService;
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
        ScholardexPublicationView publication = publication("p1", "e1", "f1", "2023-02-01", List.of("a1"), "Paper");
        ScholardexAuthorView author = author("a1", "Author One");
        ScholardexForumView forum = forum("f1", "Forum One");
        IndividualReport report = new IndividualReport();
        report.setTitle("R1");

        when(institutionRepository.findById("inst")).thenReturn(Optional.of(institution));
        when(scholardexProjectionReadService.findAllPublicationsByAffiliationsContaining("af1")).thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum));
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
        ScholardexPublicationView validPublication = publication("p1", "e1", "f1", "2023-02-01", List.of("a1"), "Valid");
        ScholardexPublicationView invalidPublication = publication("p2", "e2", "f1", "bad-date", List.of("a1"), "Invalid");
        ScholardexAuthorView author = author("a1", "Author One");
        ScholardexForumView forum = forum("f1", "Forum One");

        when(institutionRepository.findById("inst")).thenReturn(Optional.of(institution));
        when(scholardexProjectionReadService.findAllPublicationsByAffiliationsContaining("af1")).thenReturn(List.of(validPublication, invalidPublication));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum));
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
        ScholardexPublicationView cited = publication("p1", "e1", "f1", "2023-02-01", List.of("a1"), "Cited");
        ScholardexPublicationView citing = publication("p2", "e2", "f2", "2024-03-01", List.of("a2"), "Citing");
        ScholardexCitationView citation = new ScholardexCitationView();
        citation.setCitedId("p1");
        citation.setCitingId("p2");

        when(institutionRepository.findById("inst")).thenReturn(Optional.of(institution));
        when(scholardexProjectionReadService.findAllPublicationsByAffiliationsContaining("af1")).thenReturn(List.of(cited));
        when(scholardexProjectionReadService.findAllCitationsByCitedIdIn(List.of("p1"))).thenReturn(List.of(citation));
        when(scholardexProjectionReadService.findPublicationByAnyId("p2")).thenReturn(Optional.of(citing));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author("a1", "A1"), author("a2", "A2")));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum("f1", "F1"), forum("f2", "F2")));

        var result = facade.buildInstitutionPublicationsExport("inst");

        assertTrue(result.isPresent());
        assertEquals(1, result.get().publications().size());
        assertEquals(1, result.get().citationMap().size());
        assertEquals(2, result.get().authorMap().size());
        assertEquals(2, result.get().forumMap().size());
        assertEquals(1, result.get().citationMap().get("p1").size());
    }

    @Test
    void buildInstitutionPublicationsViewReturnsDeterministicPublicationOrderAndYearBucketOrder() {
        Institution institution = institution("inst", "af1");
        ScholardexPublicationView p1 = publication("p1", "e1", "f1", "2024-01-10", List.of("a1"), "Beta");
        ScholardexPublicationView p2 = publication("p2", "e2", "f1", "2024-01-10", List.of("a1"), "Alpha");
        ScholardexPublicationView p3 = publication("p3", "e3", "f1", "bad-date", List.of("a1"), "Zeta");
        ScholardexAuthorView author = author("a1", "Author One");
        ScholardexForumView forum = forum("f1", "Forum One");

        when(institutionRepository.findById("inst")).thenReturn(Optional.of(institution));
        when(scholardexProjectionReadService.findAllPublicationsByAffiliationsContaining("af1")).thenReturn(List.of(p1, p3, p2));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum));
        when(individualReportRepository.findAll()).thenReturn(List.of());

        var result = facade.buildInstitutionPublicationsView("inst");

        assertTrue(result.isPresent());
        assertEquals(List.of("p2", "p1", "p3"), result.get().publications().stream().map(p -> p.getId()).toList());
        assertEquals(List.of("p2", "p1"), result.get().publicationsByYear().get(2024).stream().map(p -> p.getId()).toList());
    }

    @Test
    void buildInstitutionPublicationsViewDedupesPublicationsAcrossAffiliations() {
        Institution institution = new Institution();
        ScholardexAffiliationView af1 = new ScholardexAffiliationView();
        af1.setAfid("af1");
        ScholardexAffiliationView af2 = new ScholardexAffiliationView();
        af2.setAfid("af2");
        institution.setScopusAffiliations(List.of(af1, af2));

        ScholardexPublicationView shared = publication("p-shared", "e1", "f1", "2023-01-01", List.of("a1"), "Shared");
        when(institutionRepository.findById("inst")).thenReturn(Optional.of(institution));
        when(scholardexProjectionReadService.findAllPublicationsByAffiliationsContaining("af1")).thenReturn(List.of(shared));
        when(scholardexProjectionReadService.findAllPublicationsByAffiliationsContaining("af2")).thenReturn(List.of(shared));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author("a1", "A1")));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum("f1", "F1")));
        when(individualReportRepository.findAll()).thenReturn(List.of());

        var result = facade.buildInstitutionPublicationsView("inst");

        assertTrue(result.isPresent());
        assertEquals(1, result.get().publications().size());
    }

    private static Institution institution(String id, String afid) {
        Institution institution = new Institution();
        institution.setName(id);
        ScholardexAffiliationView affiliation = new ScholardexAffiliationView();
        affiliation.setAfid(afid);
        institution.setScopusAffiliations(List.of(affiliation));
        return institution;
    }

    private static ScholardexPublicationView publication(String id, String eid, String forumId, String coverDate, List<String> authors, String title) {
        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId(id);
        publication.setEid(eid);
        publication.setForum(forumId);
        publication.setCoverDate(coverDate);
        publication.setAuthors(authors);
        publication.setTitle(title);
        return publication;
    }

    private static ScholardexAuthorView author(String id, String name) {
        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId(id);
        author.setName(name);
        return author;
    }

    private static ScholardexForumView forum(String id, String name) {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId(id);
        forum.setPublicationName(name);
        return forum;
    }
}
