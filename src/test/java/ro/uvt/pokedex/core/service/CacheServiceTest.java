package ro.uvt.pokedex.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.reporting.CoreConferenceRankingRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.service.application.ResearcherAuthorLookupService;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void authorAndAffiliationCachesAreReadableAndMutable() {
        assertEquals("a1", cacheService.getAuthor("a1").getId());
        assertEquals("af1", cacheService.getAffiliation("af1").getAfid());

        ScholardexAuthorView replacementAuthor = new ScholardexAuthorView();
        replacementAuthor.setId("a2");
        cacheService.putAuthor("a2", replacementAuthor);
        assertSame(replacementAuthor, cacheService.getAuthor("a2"));
    }

    @Test
    void universityAuthorIdsReturnsSet() {
        Set<String> ids = cacheService.getUniversityAuthorIds();
        assertEquals(0, ids.size());
    }
}
