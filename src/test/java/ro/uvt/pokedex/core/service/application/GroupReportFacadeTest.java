package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.service.application.reporting.GroupPublicationAggregator;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitRunRollupService;
import ro.uvt.pokedex.core.service.application.reporting.VenueClassifier;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.reporting.OrgUnitReportRefreshEventRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;
import ro.uvt.pokedex.core.service.reporting.CNFISScoringService2025;
import ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService;
import ro.uvt.pokedex.core.service.reporting.Score;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class GroupReportFacadeTest {

    private static final class Author extends ScholardexAuthorView { }
    private static final class Forum extends ScholardexForumView { }
    private static final class Publication extends ScholardexPublicationView { }

    @Mock
    private GroupRepository groupRepository;
    @Mock
    private IndividualReportRepository individualReportRepository;
    @Mock
    private CNFISScoringService2025 cnfisScoringService2025;
    @Mock
    private ComputerScienceConferenceScoringService computerScienceConferenceScoringService;
    @Mock
    private ScholardexProjectionReadService scholardexProjectionReadService;
    @Mock
    private ResearcherAuthorLookupService researcherAuthorLookupService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private GroupMembershipService groupMembershipService;
    @Mock
    private UserIndividualReportRunRepository userIndividualReportRunRepository;
    @Mock
    private ReportingDataEpochService reportingDataEpochService;
    @Mock
    private OrgUnitReportRefreshEventRepository orgUnitReportRefreshEventRepository;

    private GroupReportFacade facade;

    @BeforeEach
    void setUpLookupService() {
        lenient().when(researcherAuthorLookupService.resolveAuthorLookupKeys(any(User.ResearcherProfile.class)))
                .thenAnswer(invocation -> {
                    User.ResearcherProfile profile = invocation.getArgument(0);
                    return profile.getScopusId() == null ? List.of() : profile.getScopusId();
                });
        lenient().when(groupMembershipService.listCurrentMemberUserIds(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of("r1"));
        lenient().when(reportingDataEpochService.currentEpochInfo()).thenReturn(Optional.empty());
        lenient().when(orgUnitReportRefreshEventRepository
                        .findTop20ByUnitTypeAndUnitIdAndReportDefinitionIdOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(List.of());

        VenueClassifier venueClassifier = new VenueClassifier(
                cnfisScoringService2025, computerScienceConferenceScoringService);
        GroupPublicationAggregator aggregator = new GroupPublicationAggregator(
                groupRepository, userRepository, groupMembershipService, individualReportRepository,
                scholardexProjectionReadService, researcherAuthorLookupService, venueClassifier);
        // Real roster + rollup services over mocked repositories — the facade is just plumbing.
        OrgUnitRosterService rosterService = new OrgUnitRosterService(
                null, null, userRepository, groupMembershipService);
        OrgUnitRunRollupService rollupService = new OrgUnitRunRollupService(
                userIndividualReportRunRepository, reportingDataEpochService);

        facade = new GroupReportFacade(
                groupRepository, individualReportRepository, rosterService, rollupService,
                orgUnitReportRefreshEventRepository, aggregator);
    }
    @Test
    void buildGroupPublicationsViewReturnsRedirectWhenGroupMissing() {
        var result = facade.buildGroupPublicationsView("missing");
        assertEquals(true, result.isEmpty());
    }

    @Test
    void buildGroupPublicationsViewSkipsMalformedPublicationDatesInYearMaps() {
        lenient().when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(memberUser("r1", "R", "One", List.of("a1"))));
        Group group = new Group();

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
        lenient().when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(memberUser("r1", "R", "One", List.of("a1"))));
        Group group = new Group();

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
        lenient().when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(memberUser("r1", "R", "One", List.of("a1"))));
        Group group = new Group();

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
        lenient().when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(memberUser("r1", "R", "One", List.of("a1"))));
        Group group = new Group();

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
    void buildGroupIndividualReportViewReturnsEmptyWhenGroupOrReportMissing() {
        when(groupRepository.findById("missing")).thenReturn(Optional.empty());
        lenient().when(individualReportRepository.findById("rep1"))
                .thenReturn(Optional.of(individualReport("rep1")));

        assertTrue(facade.buildGroupIndividualReportView("missing", "rep1", null).isEmpty());

        when(groupRepository.findById("g1")).thenReturn(Optional.of(group("g1", "G")));
        when(individualReportRepository.findById("nope")).thenReturn(Optional.empty());
        assertTrue(facade.buildGroupIndividualReportView("g1", "nope", null).isEmpty());
    }

    @Test
    void buildGroupIndividualReportViewRollsUpEachMembersLatestPersistedRun() {
        when(groupRepository.findById("g1")).thenReturn(Optional.of(group("g1", "Data Lab")));
        when(individualReportRepository.findById("rep1")).thenReturn(Optional.of(individualReport("rep1")));
        when(groupMembershipService.listCurrentMemberUserIds("g1")).thenReturn(List.of("r1", "r2"));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(
                memberUser("r1", "A", "B", List.of("a1")),
                memberUser("r2", "C", "D", List.of("a2"))));

        UserIndividualReportRun run = new UserIndividualReportRun();
        run.setId("run-r1");
        run.setUserEmail("r1");
        run.setCreatedAt(Instant.parse("2026-07-01T10:00:00Z"));
        run.setCriteriaScores(new HashMap<>(Map.of(0, 2.0)));
        run.setStatus(UserIndividualReportRun.Status.READY);
        when(userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("r1", "rep1"))
                .thenReturn(Optional.of(run));
        when(userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("r2", "rep1"))
                .thenReturn(Optional.empty());

        Optional<OrgUnitReportViewModel> view = facade.buildGroupIndividualReportView("g1", "rep1", null);

        assertTrue(view.isPresent());
        OrgUnitReportViewModel vm = view.get();
        assertEquals("g1", vm.unitId());
        assertEquals("Data Lab", vm.unitName());
        assertEquals(2, vm.researchers().size());
        // Scores come straight from the persisted run — nothing is recomputed on view.
        assertEquals(Map.of(0, 2.0), vm.researcherScores().get("r1"));
        assertFalse(vm.researcherScores().containsKey("r2"));
        assertEquals(1, vm.membersWithoutRun());
        assertNotNull(vm.runMetaByEmail().get("r1"));
        verifyNoInteractions(scholardexProjectionReadService);
        // Group views carry no department labels.
        assertTrue(vm.departmentLabelByResearcher().isEmpty());
    }

    private static Group group(String id, String name) {
        Group g = new Group();
        g.setId(id);
        g.setName(name);
        return g;
    }

    private static IndividualReport individualReport(String id) {
        IndividualReport r = new IndividualReport();
        r.setId(id);
        r.setTitle("Report");
        return r;
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
