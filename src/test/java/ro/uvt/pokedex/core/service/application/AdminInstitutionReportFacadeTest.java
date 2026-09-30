package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The institution page draws on the staff of the institution's faculties (H134): each faculty's roster, each
 * researcher's effective publications, counted per faculty and per year, listed for one year at a time.
 */
@ExtendWith(MockitoExtension.class)
class AdminInstitutionReportFacadeTest {

    @Mock
    private InstitutionRepository institutionRepository;
    @Mock
    private ScholardexProjectionReadService scholardexProjectionReadService;
    @Mock
    private OrgDivisionRepository orgDivisionRepository;
    @Mock
    private OrgUnitRosterService orgUnitRosterService;
    @Mock
    private EffectiveAuthorshipReadService effectiveAuthorshipReadService;

    @InjectMocks
    private AdminInstitutionReportFacade facade;

    @Test
    void buildInstitutionPublicationsViewReturnsEmptyWhenInstitutionMissing() {
        when(institutionRepository.findById("missing")).thenReturn(Optional.empty());

        var result = facade.buildInstitutionPublicationsView("missing", null);

        assertTrue(result.isEmpty());
    }

    @Test
    void anInstitutionWithoutFacultiesRendersEmpty() {
        // The seeded inst-uvt once failed here with a NullPointerException on its missing Scopus affiliations
        // (prod, 2026-09-30); the affiliations no longer matter, the faculties do.
        Institution institution = institution("inst");
        institution.setScopusAffiliations(null);
        when(institutionRepository.findById("inst")).thenReturn(Optional.of(institution));
        when(orgDivisionRepository.findByInstitutionId("inst")).thenReturn(List.of());

        var result = facade.buildInstitutionPublicationsView("inst", null);

        assertTrue(result.isPresent());
        assertTrue(result.get().faculties().isEmpty());
        assertEquals(0, result.get().staffCount());
        assertEquals(0, result.get().publicationCount());
        assertTrue(result.get().publicationsCountByYear().isEmpty());
        assertNull(result.get().selectedYear());
        assertTrue(result.get().publications().isEmpty());
        assertTrue(result.get().authorMap().isEmpty());
        assertTrue(result.get().forumMap().isEmpty());
        verify(scholardexProjectionReadService, never()).findAuthorsByIdIn(anyCollection());
    }

    @Test
    void thePublicationsAreTheStaffsCountedPerFacultyAndPerYearAndListedForTheNewestYear() {
        Institution institution = institution("inst");
        OrgDivision feaa = division("div-feaa", "FEAA");
        OrgDivision fmi = division("div-fmi", "FMI");
        ScholardexPublicationView shared = publication("p-shared", "e1", "f1", "2024-05-01", List.of("a1", "a2"), "Shared");
        ScholardexPublicationView older = publication("p-old", "e2", "f1", "2023-02-01", List.of("a1"), "Older");
        ScholardexPublicationView undated = publication("p-undated", "e3", null, "bad-date", List.of("a2"), "Undated");

        when(institutionRepository.findById("inst")).thenReturn(Optional.of(institution));
        when(orgDivisionRepository.findByInstitutionId("inst")).thenReturn(List.of(fmi, feaa));
        when(orgUnitRosterService.divisionRoster("div-feaa")).thenReturn(List.of(member("ana@uvt"), member("bogdan@uvt")));
        when(orgUnitRosterService.divisionRoster("div-fmi")).thenReturn(List.of(member("carmen@uvt")));
        when(effectiveAuthorshipReadService.findEffectivePublicationsForUser("ana@uvt")).thenReturn(List.of(shared, older));
        when(effectiveAuthorshipReadService.findEffectivePublicationsForUser("bogdan@uvt")).thenReturn(List.of(shared, undated));
        when(effectiveAuthorshipReadService.findEffectivePublicationsForUser("carmen@uvt")).thenReturn(List.of(shared));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author("a1", "Ana"), author("a2", "Bogdan")));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum("f1", "Forum One")));

        var result = facade.buildInstitutionPublicationsView("inst", null);

        assertTrue(result.isPresent());
        var vm = result.get();
        assertEquals(List.of("FEAA", "FMI"), vm.faculties().stream().map(f -> f.name()).toList(), "faculties in name order");
        assertEquals(2, vm.faculties().get(0).staff());
        assertEquals(3, vm.faculties().get(0).publications(), "the shared paper counts once within FEAA");
        assertEquals(1, vm.faculties().get(1).staff());
        assertEquals(1, vm.faculties().get(1).publications());
        assertEquals(3, vm.staffCount());
        assertEquals(3, vm.publicationCount(), "the shared paper counts once for the institution");
        assertEquals(List.of(2024, 2023, 0), List.copyOf(vm.publicationsCountByYear().keySet()), "newest first, undated last");
        assertEquals(1L, vm.publicationsCountByYear().get(2024));
        assertEquals(1L, vm.publicationsCountByYear().get(0));
        assertEquals(2024, vm.selectedYear(), "the newest year is listed when none is asked for");
        assertEquals(List.of("p-shared"), vm.publications().stream().map(ScholardexPublicationView::getId).toList());
        assertEquals(2, vm.authorMap().size());
        assertEquals(1, vm.forumMap().size());
    }

    @Test
    void theAskedForYearIsListedAndAnUnknownYearFallsBackToTheNewest() {
        Institution institution = institution("inst");
        ScholardexPublicationView newer = publication("p-new", "e1", "f1", "2024-05-01", List.of("a1"), "Newer");
        ScholardexPublicationView older = publication("p-old", "e2", "f1", "2023-02-01", List.of("a1"), "Older");
        when(institutionRepository.findById("inst")).thenReturn(Optional.of(institution));
        when(orgDivisionRepository.findByInstitutionId("inst")).thenReturn(List.of(division("d", "D")));
        when(orgUnitRosterService.divisionRoster("d")).thenReturn(List.of(member("ana@uvt")));
        when(effectiveAuthorshipReadService.findEffectivePublicationsForUser("ana@uvt")).thenReturn(List.of(newer, older));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author("a1", "Ana")));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum("f1", "F1")));

        var asked = facade.buildInstitutionPublicationsView("inst", 2023).orElseThrow();
        assertEquals(2023, asked.selectedYear());
        assertEquals(List.of("p-old"), asked.publications().stream().map(ScholardexPublicationView::getId).toList());
        assertEquals(2, asked.publicationCount(), "the total is the whole corpus, not the listed year");

        var unknown = facade.buildInstitutionPublicationsView("inst", 1999).orElseThrow();
        assertEquals(2024, unknown.selectedYear());
        assertEquals(List.of("p-new"), unknown.publications().stream().map(ScholardexPublicationView::getId).toList());
    }

    @Test
    void aResearcherInTwoFacultiesIsResolvedOnceAndCountedInBoth() {
        Institution institution = institution("inst");
        ScholardexPublicationView own = publication("p1", "e1", "f1", "2024-05-01", List.of("a1"), "Own");
        when(institutionRepository.findById("inst")).thenReturn(Optional.of(institution));
        when(orgDivisionRepository.findByInstitutionId("inst")).thenReturn(List.of(division("a", "A"), division("b", "B")));
        when(orgUnitRosterService.divisionRoster("a")).thenReturn(List.of(member("joint@uvt")));
        when(orgUnitRosterService.divisionRoster("b")).thenReturn(List.of(member("joint@uvt")));
        when(effectiveAuthorshipReadService.findEffectivePublicationsForUser("joint@uvt")).thenReturn(List.of(own));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author("a1", "J")));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum("f1", "F1")));

        var vm = facade.buildInstitutionPublicationsView("inst", null).orElseThrow();

        verify(effectiveAuthorshipReadService, times(1)).findEffectivePublicationsForUser("joint@uvt");
        assertEquals(1, vm.staffCount());
        assertEquals(1, vm.publicationCount());
        assertEquals(1, vm.faculties().get(0).publications());
        assertEquals(1, vm.faculties().get(1).publications());
    }

    @Test
    void theListedYearIsInDeterministicOrder() {
        Institution institution = institution("inst");
        ScholardexPublicationView p1 = publication("p1", "e1", "f1", "2024-01-10", List.of("a1"), "Beta");
        ScholardexPublicationView p2 = publication("p2", "e2", "f1", "2024-01-10", List.of("a1"), "Alpha");
        when(institutionRepository.findById("inst")).thenReturn(Optional.of(institution));
        when(orgDivisionRepository.findByInstitutionId("inst")).thenReturn(List.of(division("d", "D")));
        when(orgUnitRosterService.divisionRoster("d")).thenReturn(List.of(member("ana@uvt")));
        when(effectiveAuthorshipReadService.findEffectivePublicationsForUser("ana@uvt")).thenReturn(List.of(p1, p2));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author("a1", "Ana")));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum("f1", "F1")));

        var vm = facade.buildInstitutionPublicationsView("inst", null).orElseThrow();

        assertEquals(List.of("p2", "p1"), vm.publications().stream().map(ScholardexPublicationView::getId).toList());
    }

    @Test
    void buildInstitutionPublicationsExportBuildsCitationAuthorAndForumMapsFromTheStaffCorpus() {
        Institution institution = institution("inst");
        ScholardexPublicationView cited = publication("p1", "e1", "f1", "2023-02-01", List.of("a1"), "Cited");
        ScholardexPublicationView citing = publication("p2", "e2", "f2", "2024-03-01", List.of("a2"), "Citing");
        ScholardexCitationView citation = new ScholardexCitationView();
        citation.setCitedId("p1");
        citation.setCitingId("p2");

        when(institutionRepository.findById("inst")).thenReturn(Optional.of(institution));
        when(orgDivisionRepository.findByInstitutionId("inst")).thenReturn(List.of(division("d", "D")));
        when(orgUnitRosterService.divisionRoster("d")).thenReturn(List.of(member("ana@uvt")));
        when(effectiveAuthorshipReadService.findEffectivePublicationsForUser("ana@uvt")).thenReturn(List.of(cited));
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

    private static Institution institution(String id) {
        Institution institution = new Institution();
        institution.setId(id);
        institution.setName(id);
        return institution;
    }

    private static OrgDivision division(String id, String name) {
        OrgDivision division = new OrgDivision();
        division.setId(id);
        division.setName(name);
        return division;
    }

    private static OrgUnitRosterService.RosterMember member(String email) {
        User user = new User();
        user.setEmail(email);
        return new OrgUnitRosterService.RosterMember(user, "");
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
