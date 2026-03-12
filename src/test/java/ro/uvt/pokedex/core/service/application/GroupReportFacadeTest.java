package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.GroupIndividualReportRun;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupIndividualReportRunRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.reporting.ActivityReportingService;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;

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
    private ScopusProjectionReadService scopusProjectionReadService;
    @Mock
    private ResearcherAuthorLookupService researcherAuthorLookupService;
    @Mock
    private GroupIndividualReportRunRepository groupIndividualReportRunRepository;

    @InjectMocks
    private GroupReportFacade facade;

    @BeforeEach
    void setUpLookupService() {
        lenient().when(researcherAuthorLookupService.resolveAuthorLookupKeys(any(Researcher.class)))
                .thenAnswer(invocation -> {
                    Researcher researcher = invocation.getArgument(0);
                    return researcher.getScopusId() == null ? List.of() : researcher.getScopusId();
                });
    }

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
        when(scopusProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(validPublication, invalidPublication));
        when(scopusProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author));
        when(scopusProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum));
        when(individualReportRepository.findAll()).thenReturn(List.of());

        var result = facade.buildGroupPublicationsView("g1");

        assertTrue(result.isPresent());
        assertTrue(result.get().publicationsByYear().containsKey(2023));
        assertEquals(1, result.get().publicationsByYear().get(2023).size());
        assertEquals(1L, result.get().publicationsCountByYear().get(2023));
        assertEquals(2, result.get().publications().size());
    }

    @Test
    void buildGroupPublicationsViewAppliesDeterministicOrderingAndYearBucketSorting() {
        Group group = new Group();
        Researcher researcher = new Researcher();
        researcher.setId("r1");
        researcher.setFirstName("R");
        researcher.setLastName("One");
        researcher.setScopusId(List.of("a1"));
        group.setResearchers(new ArrayList<>(List.of(researcher)));

        Publication p1 = new Publication();
        p1.setId("p1");
        p1.setTitle("Beta");
        p1.setCoverDate("2024-01-01");
        p1.setAuthors(List.of("a1"));
        p1.setForum("f1");

        Publication p2 = new Publication();
        p2.setId("p2");
        p2.setTitle("Alpha");
        p2.setCoverDate("2024-01-01");
        p2.setAuthors(List.of("a1"));
        p2.setForum("f1");

        Publication p3 = new Publication();
        p3.setId("p3");
        p3.setTitle("Zeta");
        p3.setCoverDate("bad-date");
        p3.setAuthors(List.of("a1"));
        p3.setForum("f1");

        Author author = new Author();
        author.setId("a1");
        author.setName("Author One");

        Forum forum = new Forum();
        forum.setId("f1");
        forum.setPublicationName("Forum One");

        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        when(scopusProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(p1, p3, p2));
        when(scopusProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author));
        when(scopusProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum));
        when(individualReportRepository.findAll()).thenReturn(List.of());

        var result = facade.buildGroupPublicationsView("g1");

        assertTrue(result.isPresent());
        assertEquals(List.of("p2", "p1", "p3"), result.get().publications().stream().map(Publication::getId).toList());
        assertEquals(List.of("p2", "p1"), result.get().publicationsByYear().get(2024).stream().map(Publication::getId).toList());
    }

    @Test
    void buildGroupPublicationsViewDedupesDuplicatePublications() {
        Group group = new Group();
        Researcher researcher = new Researcher();
        researcher.setId("r1");
        researcher.setFirstName("R");
        researcher.setLastName("One");
        researcher.setScopusId(List.of("a1"));
        group.setResearchers(new ArrayList<>(List.of(researcher)));

        Publication shared = new Publication();
        shared.setId("p-shared");
        shared.setTitle("Shared");
        shared.setCoverDate("2023-01-01");
        shared.setAuthors(List.of("a1"));
        shared.setForum("f1");

        Author author = new Author();
        author.setId("a1");
        author.setName("Author One");

        Forum forum = new Forum();
        forum.setId("f1");
        forum.setPublicationName("Forum One");

        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        when(scopusProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(shared, shared));
        when(scopusProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author));
        when(scopusProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum));
        when(individualReportRepository.findAll()).thenReturn(List.of());

        var result = facade.buildGroupPublicationsView("g1");

        assertTrue(result.isPresent());
        assertEquals(1, result.get().publications().size());
    }

    @Test
    void buildGroupIndividualReportViewUsesPersistedRunWithoutRecomputing() {
        Group group = new Group();
        group.setId("g1");
        Researcher researcher = new Researcher();
        researcher.setId("r1");
        researcher.setFirstName("A");
        researcher.setLastName("B");
        group.setResearchers(new ArrayList<>(List.of(researcher)));

        IndividualReport report = new IndividualReport();
        report.setId("rep1");
        report.setCriteria(List.of());

        GroupIndividualReportRun run = new GroupIndividualReportRun();
        run.setResearcherScores(java.util.Map.of("r1", java.util.Map.of(0, 4.0)));
        run.setCriteriaThresholds(java.util.Map.of(0, java.util.Map.of("ASSISTANT", 2.0)));
        run.setStatus(GroupIndividualReportRun.Status.READY);
        run.setBuildErrors(List.of());

        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        when(individualReportRepository.findById("rep1")).thenReturn(Optional.of(report));
        when(groupIndividualReportRunRepository.findTopByGroupIdAndReportDefinitionIdOrderByCreatedAtDesc("g1", "rep1"))
                .thenReturn(Optional.of(run));

        var result = facade.buildGroupIndividualReportView("g1", "rep1");

        assertEquals(null, result.redirect());
        assertEquals("g1", ((Group) result.attributes().get("group")).getId());
        verifyNoInteractions(scopusProjectionReadService, activityInstanceRepository);
    }

    @Test
    void refreshGroupIndividualReportViewComputesAndPersistsNewRun() {
        Group group = new Group();
        group.setId("g1");
        Researcher researcher = new Researcher();
        researcher.setId("r1");
        researcher.setFirstName("A");
        researcher.setLastName("B");
        researcher.setScopusId(List.of("a1"));
        group.setResearchers(new ArrayList<>(List.of(researcher)));

        IndividualReport report = new IndividualReport();
        report.setId("rep1");
        report.setCriteria(List.of());
        report.setIndicators(List.of());
        Institution affiliation = new Institution();
        affiliation.setName("ANY");
        report.setIndividualAffiliation(affiliation);

        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        when(individualReportRepository.findById("rep1")).thenReturn(Optional.of(report));
        Author author = new Author();
        author.setId("a1");
        when(scopusProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(scopusProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of());
        when(groupIndividualReportRunRepository.save(any(GroupIndividualReportRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = facade.refreshGroupIndividualReportView("g1", "rep1");

        assertEquals(null, result.redirect());
        assertTrue(result.attributes().containsKey("runStatus"));
    }

    @Test
    void refreshGroupIndividualReportViewLoadsCitationDataOncePerResearcher() {
        Group group = new Group();
        group.setId("g1");
        Researcher researcher = new Researcher();
        researcher.setId("r1");
        researcher.setFirstName("A");
        researcher.setLastName("B");
        researcher.setScopusId(List.of("a1"));
        group.setResearchers(new ArrayList<>(List.of(researcher)));

        Indicator citations = new Indicator();
        citations.setOutputType(Indicator.Type.CITATIONS);
        Indicator citationsExcludeSelf = new Indicator();
        citationsExcludeSelf.setOutputType(Indicator.Type.CITATIONS_EXCLUDE_SELF);

        IndividualReport report = new IndividualReport();
        report.setId("rep1");
        report.setCriteria(List.of());
        report.setIndicators(List.of(citations, citationsExcludeSelf));
        Institution affiliation = new Institution();
        affiliation.setName("ANY");
        report.setIndividualAffiliation(affiliation);

        Author author = new Author();
        author.setId("a1");

        Publication publication = new Publication();
        publication.setId("p1");
        publication.setAuthors(List.of("a1"));

        Citation citation = new Citation();
        citation.setCitedId("p1");
        citation.setCitingId("cp1");

        Publication citingPublication = new Publication();
        citingPublication.setId("cp1");
        citingPublication.setAuthors(List.of("a2"));

        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        when(individualReportRepository.findById("rep1")).thenReturn(Optional.of(report));
        when(scopusProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(scopusProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(publication));
        when(scopusProjectionReadService.findAllCitationsByCitedIdIn(List.of("p1"))).thenReturn(List.of(citation));
        when(scopusProjectionReadService.findAllPublicationsByIdIn(List.of("cp1"))).thenReturn(List.of(citingPublication));
        when(scientificProductionService.calculateScientificImpactScore(any(Publication.class), any(List.class), any(Indicator.class)))
                .thenAnswer(invocation -> {
                    Map<String, Score> scores = new java.util.HashMap<>();
                    scores.put("total", new Score());
                    return scores;
                });
        when(groupIndividualReportRunRepository.save(any(GroupIndividualReportRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        facade.refreshGroupIndividualReportView("g1", "rep1");

        verify(scopusProjectionReadService, times(1)).findAllCitationsByCitedIdIn(List.of("p1"));
        verify(scopusProjectionReadService, times(1)).findAllPublicationsByIdIn(List.of("cp1"));
    }

    @Test
    void refreshGroupIndividualReportViewLoadsActivitiesOncePerResearcher() {
        Group group = new Group();
        group.setId("g1");
        Researcher researcher = new Researcher();
        researcher.setId("r1");
        researcher.setFirstName("A");
        researcher.setLastName("B");
        researcher.setScopusId(List.of("a1"));
        group.setResearchers(new ArrayList<>(List.of(researcher)));

        Activity activity = new Activity();
        activity.setName("Forum Activity");

        Indicator indicator1 = new Indicator();
        indicator1.setOutputType(Indicator.Type.ACTIVITY_FORUM);
        indicator1.setActivity(activity);

        Indicator indicator2 = new Indicator();
        indicator2.setOutputType(Indicator.Type.ACTIVITY_FORUM);
        indicator2.setActivity(activity);

        IndividualReport report = new IndividualReport();
        report.setId("rep1");
        report.setCriteria(List.of());
        report.setIndicators(List.of(indicator1, indicator2));
        Institution affiliation = new Institution();
        affiliation.setName("ANY");
        report.setIndividualAffiliation(affiliation);

        Author author = new Author();
        author.setId("a1");

        ActivityInstance activityInstance = new ActivityInstance();
        activityInstance.setResearcherId("r1");
        activityInstance.setActivity(activity);

        Score total = new Score();
        total.setAuthorScore(1d);

        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        when(individualReportRepository.findById("rep1")).thenReturn(Optional.of(report));
        when(scopusProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(scopusProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of());
        when(activityInstanceRepository.findAllByResearcherId("r1")).thenReturn(List.of(activityInstance));
        when(activityReportingService.calculateActivityScores(any(List.class), any(Indicator.class)))
                .thenReturn(Map.of("total", total));
        when(groupIndividualReportRunRepository.save(any(GroupIndividualReportRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        facade.refreshGroupIndividualReportView("g1", "rep1");

        verify(activityInstanceRepository, times(1)).findAllByResearcherId("r1");
    }
}
