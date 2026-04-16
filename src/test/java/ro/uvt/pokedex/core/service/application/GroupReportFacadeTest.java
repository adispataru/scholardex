package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.GroupIndividualReportRun;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupIndividualReportRunRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.reporting.ActivityReportingService;
import ro.uvt.pokedex.core.service.reporting.CNFISScoringService2025;
import ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class GroupReportFacadeTest {

    private static final class Author extends ScholardexAuthorView { }
    private static final class Forum extends ScholardexForumView { }
    private static final class Publication extends ScholardexPublicationView { }
    private static final class Citation extends ScholardexCitationView { }

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
    private CNFISScoringService2025 cnfisScoringService2025;
    @Mock
    private ComputerScienceConferenceScoringService computerScienceConferenceScoringService;
    @Mock
    private ScholardexProjectionReadService scholardexProjectionReadService;
    @Mock
    private ResearcherAuthorLookupService researcherAuthorLookupService;
    @Mock
    private GroupIndividualReportRunRepository groupIndividualReportRunRepository;
    @Mock
    private ReportingLookupMemoization reportingLookupMemoization;

    @InjectMocks
    private GroupReportFacade facade;

    @BeforeEach
    void setUpLookupService() {
        lenient().when(researcherAuthorLookupService.resolveAuthorLookupKeys(any(Researcher.class)))
                .thenAnswer(invocation -> {
                    Researcher researcher = invocation.getArgument(0);
                    return researcher.getScopusId() == null ? List.of() : researcher.getScopusId();
                });
        lenient().doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get())
                .when(reportingLookupMemoization).withRefreshScope(any(Supplier.class));
        lenient().doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(reportingLookupMemoization).withRefreshScope(any(Runnable.class));
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
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(validPublication, invalidPublication));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum));
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
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(p1, p3, p2));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum));
        when(individualReportRepository.findAll()).thenReturn(List.of());

        var result = facade.buildGroupPublicationsView("g1");

        assertTrue(result.isPresent());
        assertEquals(List.of("p2", "p1", "p3"), result.get().publications().stream().map(p -> p.getId()).toList());
        assertEquals(List.of("p2", "p1"), result.get().publicationsByYear().get(2024).stream().map(p -> p.getId()).toList());
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
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(shared, shared));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum));
        when(individualReportRepository.findAll()).thenReturn(List.of());

        var result = facade.buildGroupPublicationsView("g1");

        assertTrue(result.isPresent());
        assertEquals(1, result.get().publications().size());
    }

    @Test
    void buildGroupPublicationsViewBuildsVenueClassCountByYear() {
        Group group = new Group();
        Researcher researcher = new Researcher();
        researcher.setId("r1");
        researcher.setFirstName("R");
        researcher.setLastName("One");
        researcher.setScopusId(List.of("a1"));
        group.setResearchers(new ArrayList<>(List.of(researcher)));

        Publication q1Journal = new Publication();
        q1Journal.setId("p1");
        q1Journal.setTitle("Q1 Journal");
        q1Journal.setCoverDate("2023-01-01");
        q1Journal.setAuthors(List.of("a1"));
        q1Journal.setForum("fj1");
        q1Journal.setSubtype("ar");

        Publication q3Journal = new Publication();
        q3Journal.setId("p2");
        q3Journal.setTitle("Q3 Journal");
        q3Journal.setCoverDate("2023-02-01");
        q3Journal.setAuthors(List.of("a1"));
        q3Journal.setForum("fj2");
        q3Journal.setSubtype("re");

        Publication aConference = new Publication();
        aConference.setId("p3");
        aConference.setTitle("A Conference");
        aConference.setCoverDate("2024-03-01");
        aConference.setAuthors(List.of("a1"));
        aConference.setForum("fc1");
        aConference.setSubtype("cp");

        Publication bConference = new Publication();
        bConference.setId("p4");
        bConference.setTitle("B Conference");
        bConference.setCoverDate("2024-04-01");
        bConference.setAuthors(List.of("a1"));
        bConference.setForum("fc2");
        bConference.setSubtype("cp");

        Publication unranked = new Publication();
        unranked.setId("p5");
        unranked.setTitle("Unranked");
        unranked.setCoverDate("2024-05-01");
        unranked.setAuthors(List.of("a1"));
        unranked.setForum("fu1");
        unranked.setSubtype("bk");

        Publication bookLncs = new Publication();
        bookLncs.setId("p6");
        bookLncs.setTitle("LNCS Chapter");
        bookLncs.setCoverDate("2024-06-01");
        bookLncs.setAuthors(List.of("a1"));
        bookLncs.setForum("fl1");
        bookLncs.setSubtype("ch");

        Publication bookLncsConference = new Publication();
        bookLncsConference.setId("p7");
        bookLncsConference.setTitle("LNCS Chapter With Conference Match");
        bookLncsConference.setCoverDate("2024-07-01");
        bookLncsConference.setAuthors(List.of("a1"));
        bookLncsConference.setForum("fl2");
        bookLncsConference.setSubtype("ch");

        Author author = new Author();
        author.setId("a1");
        author.setName("Author One");

        Forum journalForum1 = new Forum();
        journalForum1.setId("fj1");
        journalForum1.setPublicationName("Journal One");

        Forum journalForum2 = new Forum();
        journalForum2.setId("fj2");
        journalForum2.setPublicationName("Journal Two");

        Forum conferenceForum1 = new Forum();
        conferenceForum1.setId("fc1");
        conferenceForum1.setPublicationName("Conference A");

        Forum conferenceForum2 = new Forum();
        conferenceForum2.setId("fc2");
        conferenceForum2.setPublicationName("Conference B");

        Forum unrankedForum = new Forum();
        unrankedForum.setId("fu1");
        unrankedForum.setPublicationName("Book Venue");

        Forum lncsBookForum = new Forum();
        lncsBookForum.setId("fl1");
        lncsBookForum.setPublicationName("Lecture Notes in Computer Science");

        Forum lncsBookConferenceForum = new Forum();
        lncsBookConferenceForum.setId("fl2");
        lncsBookConferenceForum.setPublicationName("Lecture Notes in Computer Science, ICSE 2024");

        CNFISReport2025 q1Report = new CNFISReport2025();
        q1Report.setIsiQ1(true);
        CNFISReport2025 q3Report = new CNFISReport2025();
        q3Report.setIsiQ3(true);

        Score aScore = new Score();
        aScore.setCoreRankingEquivalent(CoreConferenceRanking.Rank.A_STAR.toString());
        Score bScore = new Score();
        bScore.setCoreRankingEquivalent(CoreConferenceRanking.Rank.B.toString());
        bScore.setQuarter("LNCS");
        Score chapterConferenceScore = new Score();
        chapterConferenceScore.setCoreRankingEquivalent(CoreConferenceRanking.Rank.B.toString());

        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1")))
                .thenReturn(List.of(q1Journal, q3Journal, aConference, bConference, unranked, bookLncs, bookLncsConference));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection()))
                .thenReturn(List.of(journalForum1, journalForum2, conferenceForum1, conferenceForum2, unrankedForum, lncsBookForum, lncsBookConferenceForum));
        when(individualReportRepository.findAll()).thenReturn(List.of());
        when(cnfisScoringService2025.getReport(org.mockito.ArgumentMatchers.argThat(pub -> pub != null && "p1".equals(pub.getId())), org.mockito.ArgumentMatchers.any())).thenReturn(q1Report);
        when(cnfisScoringService2025.getReport(org.mockito.ArgumentMatchers.argThat(pub -> pub != null && "p2".equals(pub.getId())), org.mockito.ArgumentMatchers.any())).thenReturn(q3Report);
        when(computerScienceConferenceScoringService.getScore(org.mockito.ArgumentMatchers.<ScoringPublicationReadModel>argThat(pub -> pub != null && "p3".equals(pub.getId())), org.mockito.ArgumentMatchers.any(Indicator.class)))
                .thenReturn(aScore);
        when(computerScienceConferenceScoringService.getScore(org.mockito.ArgumentMatchers.<ScoringPublicationReadModel>argThat(pub -> pub != null && "p4".equals(pub.getId())), org.mockito.ArgumentMatchers.any(Indicator.class)))
                .thenReturn(bScore);
        when(computerScienceConferenceScoringService.tryResolveCoreScore(org.mockito.ArgumentMatchers.argThat(pub -> pub != null && "p6".equals(pub.getId())), org.mockito.ArgumentMatchers.eq(lncsBookForum), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.empty());
        when(computerScienceConferenceScoringService.tryResolveCoreScore(org.mockito.ArgumentMatchers.argThat(pub -> pub != null && "p7".equals(pub.getId())), org.mockito.ArgumentMatchers.eq(lncsBookConferenceForum), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.of(chapterConferenceScore));

        var result = facade.buildGroupPublicationsView("g1");

        assertTrue(result.isPresent());
        assertEquals(1L, result.get().venueClassCountByYear().get(2023).get("Q1"));
        assertEquals(1L, result.get().venueClassCountByYear().get(2023).get("Q3"));
        assertEquals(1L, result.get().venueClassCountByYear().get(2024).get("A_STAR"));
        assertEquals(1L, result.get().venueClassCountByYear().get(2024).get("B"));
        assertEquals(1L, result.get().venueClassCountByYear().get(2024).get("LNCS"));
        assertEquals(1L, result.get().venueClassCountByYear().get(2024).get("BOOK_LNCS"));
        assertEquals(1L, result.get().venueClassCountByYear().get(2024).get("Unranked"));
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
        verifyNoInteractions(scholardexProjectionReadService, activityInstanceRepository);
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
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of());
        when(groupIndividualReportRunRepository.save(any(GroupIndividualReportRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = facade.refreshGroupIndividualReportView("g1", "rep1");

        assertEquals(null, result.redirect());
        assertTrue(result.attributes().containsKey("runStatus"));
        verify(reportingLookupMemoization, times(1)).withRefreshScope(any(Supplier.class));
    }

    @Test
    void buildGroupIndividualReportViewWhenRunMissingUsesMemoizedComputeScope() {
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

        Author author = new Author();
        author.setId("a1");

        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        when(individualReportRepository.findById("rep1")).thenReturn(Optional.of(report));
        when(groupIndividualReportRunRepository.findTopByGroupIdAndReportDefinitionIdOrderByCreatedAtDesc("g1", "rep1"))
                .thenReturn(Optional.empty());
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of());
        when(groupIndividualReportRunRepository.save(any(GroupIndividualReportRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = facade.buildGroupIndividualReportView("g1", "rep1");

        assertEquals(null, result.redirect());
        verify(reportingLookupMemoization, times(1)).withRefreshScope(any(Supplier.class));
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
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findAllCitationsByCitedIdIn(List.of("p1"))).thenReturn(List.of(citation));
        when(scholardexProjectionReadService.findAllPublicationsByIdIn(List.of("cp1"))).thenReturn(List.of(citingPublication));
        when(scientificProductionService.calculateScientificImpactScore(any(ScoringPublicationReadModel.class), any(List.class), any(Indicator.class), anyMap()))
                .thenAnswer(invocation -> {
                    Map<String, Score> scores = new java.util.HashMap<>();
                    scores.put("total", new Score());
                    return scores;
                });
        when(groupIndividualReportRunRepository.save(any(GroupIndividualReportRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        facade.refreshGroupIndividualReportView("g1", "rep1");

        verify(scholardexProjectionReadService, times(1)).findAllCitationsByCitedIdIn(List.of("p1"));
        verify(scholardexProjectionReadService, times(1)).findAllPublicationsByIdIn(List.of("cp1"));
        verify(scientificProductionService, times(2))
                .precomputeCitationBaseScores(any(List.class), any(Indicator.class));
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
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of());
        when(activityInstanceRepository.findAllByResearcherId("r1")).thenReturn(List.of(activityInstance));
        when(activityReportingService.calculateActivityScores(any(List.class), any(Indicator.class)))
                .thenReturn(Map.of("total", total));
        when(groupIndividualReportRunRepository.save(any(GroupIndividualReportRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        facade.refreshGroupIndividualReportView("g1", "rep1");

        verify(activityInstanceRepository, times(1)).findAllByResearcherId("r1");
    }

    @Test
    void refreshGroupIndividualReportViewPreservesCitationExcludeSelfSemantics() {
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

        AbstractReport.Criterion criterion0 = new AbstractReport.Criterion();
        criterion0.setIndicatorIndices(List.of(0));
        AbstractReport.Criterion criterion1 = new AbstractReport.Criterion();
        criterion1.setIndicatorIndices(List.of(1));

        IndividualReport report = new IndividualReport();
        report.setId("rep1");
        report.setCriteria(List.of(criterion0, criterion1));
        report.setIndicators(List.of(citations, citationsExcludeSelf));
        Institution affiliation = new Institution();
        affiliation.setName("ANY");
        report.setIndividualAffiliation(affiliation);

        Author author = new Author();
        author.setId("a1");

        Publication publication = new Publication();
        publication.setId("p1");
        publication.setAuthors(List.of("a1"));
        publication.setTitle("Root Publication");

        Citation citationSelf = new Citation();
        citationSelf.setCitedId("p1");
        citationSelf.setCitingId("cp-self");

        Citation citationExternal = new Citation();
        citationExternal.setCitedId("p1");
        citationExternal.setCitingId("cp-external");

        Publication citingSelf = new Publication();
        citingSelf.setId("cp-self");
        citingSelf.setAuthors(List.of("a1"));
        citingSelf.setTitle("Self Citing");

        Publication citingExternal = new Publication();
        citingExternal.setId("cp-external");
        citingExternal.setAuthors(List.of("a2"));
        citingExternal.setTitle("External Citing");

        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        when(individualReportRepository.findById("rep1")).thenReturn(Optional.of(report));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findAllCitationsByCitedIdIn(List.of("p1")))
                .thenReturn(List.of(citationSelf, citationExternal));
        when(scholardexProjectionReadService.findAllPublicationsByIdIn(List.of("cp-self", "cp-external")))
                .thenReturn(List.of(citingSelf, citingExternal));
        when(scientificProductionService.calculateScientificImpactScore(any(ScoringPublicationReadModel.class), any(List.class), any(Indicator.class), anyMap()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<ScoringPublicationReadModel> citingPublications = invocation.getArgument(1);
                    Map<String, Score> result = new HashMap<>();
                    for (ScoringPublicationReadModel citingPublication : citingPublications) {
                        Score score = new Score();
                        score.setAuthorScore(1.0);
                        score.setScore(1.0);
                        result.put(citingPublication.getId(), score);
                    }
                    Score total = new Score();
                    total.setAuthorScore((double) citingPublications.size());
                    total.setScore((double) citingPublications.size());
                    result.put("total", total);
                    return result;
                });
        when(groupIndividualReportRunRepository.save(any(GroupIndividualReportRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = facade.refreshGroupIndividualReportView("g1", "rep1");

        @SuppressWarnings("unchecked")
        Map<String, Map<Integer, Double>> researcherScores =
                (Map<String, Map<Integer, Double>>) result.attributes().get("researcherScores");
        Map<Integer, Double> scores = researcherScores.get("r1");
        assertEquals(2.0, scores.get(0));
        assertEquals(1.0, scores.get(1));
    }

    @Test
    void refreshGroupIndividualReportViewPreservesTop10CitationSelectorBehavior() {
        Group group = new Group();
        group.setId("g1");
        Researcher researcher = new Researcher();
        researcher.setId("r1");
        researcher.setFirstName("A");
        researcher.setLastName("B");
        researcher.setScopusId(List.of("a1"));
        group.setResearchers(new ArrayList<>(List.of(researcher)));

        Indicator citationsTop10 = new Indicator();
        citationsTop10.setOutputType(Indicator.Type.CITATIONS);
        citationsTop10.setSelector(Indicator.Selector.TOP_10);

        AbstractReport.Criterion criterion0 = new AbstractReport.Criterion();
        criterion0.setIndicatorIndices(List.of(0));

        IndividualReport report = new IndividualReport();
        report.setId("rep1");
        report.setCriteria(List.of(criterion0));
        report.setIndicators(List.of(citationsTop10));
        Institution affiliation = new Institution();
        affiliation.setName("ANY");
        report.setIndividualAffiliation(affiliation);

        Author author = new Author();
        author.setId("a1");

        Publication publication = new Publication();
        publication.setId("p1");
        publication.setAuthors(List.of("a1"));
        publication.setTitle("Root Publication");

        List<ScholardexCitationView> citations = new ArrayList<>();
        List<ScholardexPublicationView> citingPublications = new ArrayList<>();
        List<String> citingIds = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            String citingId = "cp-" + i;
            Citation citation = new Citation();
            citation.setCitedId("p1");
            citation.setCitingId(citingId);
            citations.add(citation);
            Publication citingPublication = new Publication();
            citingPublication.setId(citingId);
            citingPublication.setAuthors(List.of("a" + (i + 1)));
            citingPublication.setTitle("Citing " + i);
            citingPublications.add(citingPublication);
            citingIds.add(citingId);
        }

        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        when(individualReportRepository.findById("rep1")).thenReturn(Optional.of(report));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findAllCitationsByCitedIdIn(List.of("p1"))).thenReturn(citations);
        when(scholardexProjectionReadService.findAllPublicationsByIdIn(citingIds)).thenReturn(citingPublications);
        when(scientificProductionService.calculateScientificImpactScore(any(ScoringPublicationReadModel.class), any(List.class), any(Indicator.class), anyMap()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<ScoringPublicationReadModel> currentCitingPublications = invocation.getArgument(1);
                    Map<String, Score> result = new HashMap<>();
                    int scoreSeed = 1;
                    for (ScoringPublicationReadModel citingPublication : currentCitingPublications) {
                        Score score = new Score();
                        score.setAuthorScore((double) scoreSeed++);
                        score.setScore(1.0);
                        result.put(citingPublication.getTitle(), score);
                    }
                    Score total = new Score();
                    total.setAuthorScore(0.0);
                    total.setScore(0.0);
                    result.put("total", total);
                    return result;
                });
        when(groupIndividualReportRunRepository.save(any(GroupIndividualReportRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = facade.refreshGroupIndividualReportView("g1", "rep1");

        @SuppressWarnings("unchecked")
        Map<String, Map<Integer, Double>> researcherScores =
                (Map<String, Map<Integer, Double>>) result.attributes().get("researcherScores");
        Map<Integer, Double> scores = researcherScores.get("r1");
        assertEquals(75.0, scores.get(0));
    }
}
