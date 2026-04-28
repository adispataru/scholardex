package ro.uvt.pokedex.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.reporting.CoreConferenceRankingRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.service.application.ResearcherAuthorLookupService;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheServiceTest {

    @Mock
    private ScholardexProjectionReadService scholardexProjectionReadService;
    @Mock
    private CoreConferenceRankingRepository coreConferenceRankingRepository;
    @Mock
    private GroupRepository groupRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ResearcherAuthorLookupService researcherAuthorLookupService;

    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("f1");
        CoreConferenceRanking core = new CoreConferenceRanking();
        core.setId("ICSE-International Conference on Software Engineering");
        core.setAcronym("ICSE");
        core.setName("International Conference on Software Engineering");
        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId("a1");
        ScholardexAffiliationView affiliation = new ScholardexAffiliationView();
        affiliation.setAfid("af1");

        when(scholardexProjectionReadService.findAllForums()).thenReturn(List.of(forum));
        when(coreConferenceRankingRepository.findAll()).thenReturn(List.of(core));
        when(scholardexProjectionReadService.findAllAuthors()).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findAllAffiliations()).thenReturn(List.of(affiliation));
        when(groupRepository.findAll()).thenReturn(List.of());

        cacheService = new CacheService(
                scholardexProjectionReadService,
                coreConferenceRankingRepository,
                groupRepository,
                userRepository,
                researcherAuthorLookupService
        );
    }

    @Test
    void cachedForumLookupWorks() {
        ScholardexForumView forum = cacheService.getCachedForums("f1");
        assertEquals("f1", forum.getId());
    }

    @Test
    void conferenceRankingLookupUsesCacheMap() {
        List<CoreConferenceRanking> rankings = cacheService.getCachedConfRankings("ICSE");
        assertEquals(1, rankings.size());
    }

    @Test
    void conferenceRankingLookupLoadsRepositoryOnCacheMiss() {
        CoreConferenceRanking miss = ranking("SPLASH", "Systems Programming Languages and Applications");
        when(coreConferenceRankingRepository.findAllByAcronymIgnoreCase("SPLASH")).thenReturn(List.of(miss));

        List<CoreConferenceRanking> rankings = cacheService.getCachedConfRankings("SPLASH");

        assertEquals(List.of(miss), rankings);
        verify(coreConferenceRankingRepository).findAllByAcronymIgnoreCase("SPLASH");
    }

    @Test
    void conferenceRankingLookupByNormalizedTitleUsesCacheMap() {
        List<CoreConferenceRanking> fullTitle = cacheService.getCachedConfRankingsByNormalizedTitle(
                "international conference on software engineering");
        List<CoreConferenceRanking> withoutBoilerplate = cacheService.getCachedConfRankingsByNormalizedTitle(
                "software engineering");

        assertEquals(1, fullTitle.size());
        assertEquals("ICSE", fullTitle.getFirst().getAcronym());
        assertEquals(1, withoutBoilerplate.size());
        assertEquals("ICSE", withoutBoilerplate.getFirst().getAcronym());
    }

    @Test
    void conferenceRankingLookupByNormalizedTitleReturnsEmptyForBlankKeys() {
        assertTrue(cacheService.getCachedConfRankingsByNormalizedTitle(null).isEmpty());
        assertTrue(cacheService.getCachedConfRankingsByNormalizedTitle("   ").isEmpty());
    }

    @Test
    void conferenceRankingTitleIndexKeepsDistinctRankingsWithSameNormalizedTitle() {
        CoreConferenceRanking first = ranking("A", "Shared Conference Title");
        CoreConferenceRanking second = ranking("B", "Shared Conference Title");
        when(coreConferenceRankingRepository.findAll()).thenReturn(List.of(first, second));

        CacheService service = new CacheService(
                scholardexProjectionReadService,
                coreConferenceRankingRepository,
                groupRepository,
                userRepository,
                researcherAuthorLookupService
        );

        List<CoreConferenceRanking> rankings = service.getCachedConfRankingsByNormalizedTitle("shared conference title");
        assertEquals(2, rankings.size());
        assertEquals(List.of("A", "B"), rankings.stream().map(CoreConferenceRanking::getAcronym).toList());
    }

    @Test
    void conferenceRankingTitleIndexDeduplicatesSameRankingKey() {
        CoreConferenceRanking first = ranking("A", "Shared Conference Title");
        first.setId("shared-id");
        CoreConferenceRanking duplicate = ranking("B", "Shared Conference Title");
        duplicate.setId("shared-id");
        when(coreConferenceRankingRepository.findAll()).thenReturn(List.of(first, duplicate));

        CacheService service = new CacheService(
                scholardexProjectionReadService,
                coreConferenceRankingRepository,
                groupRepository,
                userRepository,
                researcherAuthorLookupService
        );

        List<CoreConferenceRanking> rankings = service.getCachedConfRankingsByNormalizedTitle("shared conference title");
        assertEquals(1, rankings.size());
        assertSame(first, rankings.getFirst());
    }

    @Test
    void authorAndAffiliationCachesAreReadableAndMutable() {
        assertEquals("a1", cacheService.getAuthor("a1").getId());
        assertEquals("af1", cacheService.getAffiliation("af1").getAfid());

        ScholardexAuthorView replacementAuthor = new ScholardexAuthorView();
        replacementAuthor.setId("a2");
        cacheService.putAuthor("a2", replacementAuthor);
        assertSame(replacementAuthor, cacheService.getAuthor("a2"));

        ScholardexAffiliationView replacementAffiliation = new ScholardexAffiliationView();
        replacementAffiliation.setAfid("af2");
        cacheService.putAffiliation("af2", replacementAffiliation);
        assertSame(replacementAffiliation, cacheService.getAffiliation("af2"));
    }

    @Test
    void forumCacheIsReadableMutableAndClearable() {
        assertEquals("f1", cacheService.getForum("f1").getId());

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("f2");
        cacheService.putForum("f2", forum);
        assertSame(forum, cacheService.getForum("f2"));

        cacheService.clear();

        assertNull(cacheService.getForum("f1"));
        assertNull(cacheService.getAuthor("a1"));
        assertNull(cacheService.getAffiliation("af1"));
    }

    @Test
    void saveOperationsFlushCachedValuesToTheirRepositories() {
        cacheService.syncCoreConferenceRankingCacheToDb();
        cacheService.saveAllAuthors();
        cacheService.saveAllForums();
        cacheService.saveAllAffiliations();

        verify(coreConferenceRankingRepository).saveAll(cacheService.getConfRankingCache().get("ICSE"));
        verify(scholardexProjectionReadService).saveAuthor(cacheService.getAuthor("a1"));
        verify(scholardexProjectionReadService).saveForum(cacheService.getForum("f1"));
        verify(scholardexProjectionReadService).saveAffiliation(cacheService.getAffiliation("af1"));
    }

    @Test
    void universityAuthorIdsReturnsSet() {
        Set<String> ids = cacheService.getUniversityAuthorIds();
        assertEquals(0, ids.size());
    }

    @Test
    void constructorResolvesUniversityAuthorIdsFromGroupMembers() {
        Group group = new Group();
        group.setMemberIds(List.of("ada@uvt.ro"));
        User researcher = new User();
        researcher.setEmail("ada@uvt.ro");
        researcher.setResearcherProfile(new User.ResearcherProfile());
        ScholardexAuthorView universityAuthor = new ScholardexAuthorView();
        universityAuthor.setId("author-uvt");
        when(groupRepository.findAll()).thenReturn(List.of(group));
        when(userRepository.findAllById(List.of("ada@uvt.ro"))).thenReturn(List.of(researcher));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(researcher.getResearcherProfile()))
                .thenReturn(List.of("author-uvt"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("author-uvt")))
                .thenReturn(List.of(universityAuthor));

        CacheService service = new CacheService(
                scholardexProjectionReadService,
                coreConferenceRankingRepository,
                groupRepository,
                userRepository,
                researcherAuthorLookupService
        );

        assertEquals(Set.of("author-uvt"), service.getUniversityAuthorIds());
    }

    @Test
    void constructorSkipsGroupMembersWithoutResearcherProfiles() {
        Group group = new Group();
        group.setMemberIds(List.of("admin@uvt.ro"));
        User admin = new User();
        admin.setEmail("admin@uvt.ro");
        when(groupRepository.findAll()).thenReturn(List.of(group));
        when(userRepository.findAllById(List.of("admin@uvt.ro"))).thenReturn(List.of(admin));

        CacheService service = new CacheService(
                scholardexProjectionReadService,
                coreConferenceRankingRepository,
                groupRepository,
                userRepository,
                researcherAuthorLookupService
        );

        assertTrue(service.getUniversityAuthorIds().isEmpty());
        verify(researcherAuthorLookupService, never()).resolveAuthorLookupKeys(any());
    }

    private CoreConferenceRanking ranking(String acronym, String name) {
        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym(acronym);
        ranking.setName(name);
        return ranking;
    }
}
