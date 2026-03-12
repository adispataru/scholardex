package ro.uvt.pokedex.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.scopus.Affiliation;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
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
    private ScholardexProjectionReadService scopusProjectionReadService;
    @Mock
    private CoreConferenceRankingRepository coreConferenceRankingRepository;
    @Mock
    private GroupRepository groupRepository;
    @Mock
    private ResearcherAuthorLookupService researcherAuthorLookupService;

    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        Forum forum = new Forum();
        forum.setId("f1");
        CoreConferenceRanking core = new CoreConferenceRanking();
        core.setAcronym("ICSE");
        Author author = new Author();
        author.setId("a1");
        Affiliation affiliation = new Affiliation();
        affiliation.setAfid("af1");

        when(scopusProjectionReadService.findAllForums()).thenReturn(List.of(forum));
        when(coreConferenceRankingRepository.findAll()).thenReturn(List.of(core));
        when(scopusProjectionReadService.findAllAuthors()).thenReturn(List.of(author));
        when(scopusProjectionReadService.findAllAffiliations()).thenReturn(List.of(affiliation));
        when(groupRepository.findAll()).thenReturn(List.of());

        cacheService = new CacheService(
                scopusProjectionReadService,
                coreConferenceRankingRepository,
                groupRepository,
                researcherAuthorLookupService
        );
    }

    @Test
    void cachedForumLookupWorks() {
        Forum forum = cacheService.getCachedForums("f1");
        assertEquals("f1", forum.getId());
    }

    @Test
    void conferenceRankingLookupUsesCacheMap() {
        List<CoreConferenceRanking> rankings = cacheService.getCachedConfRankings("ICSE");
        assertEquals(1, rankings.size());
    }

    @Test
    void authorAndAffiliationCachesAreReadableAndMutable() {
        assertEquals("a1", cacheService.getAuthor("a1").getId());
        assertEquals("af1", cacheService.getAffiliation("af1").getAfid());

        Author replacementAuthor = new Author();
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
