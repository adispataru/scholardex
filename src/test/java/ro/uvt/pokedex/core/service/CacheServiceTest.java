package ro.uvt.pokedex.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.repository.reporting.CoreConferenceRankingRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.RankingRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusAffiliationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusAuthorRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheServiceTest {

    @Mock
    private ScopusForumRepository scopusForumRepository;
    @Mock
    private CoreConferenceRankingRepository coreConferenceRankingRepository;
    @Mock
    private RankingRepository rankingRepository;
    @Mock
    private ScopusAuthorRepository scopusAuthorRepository;
    @Mock
    private ScopusAffiliationRepository scopusAffiliationRepository;
    @Mock
    private GroupRepository groupRepository;

    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        when(scopusForumRepository.findAll()).thenReturn(List.of());
        when(coreConferenceRankingRepository.findAll()).thenReturn(List.of());
        when(rankingRepository.findAll()).thenReturn(List.of());
        when(scopusAuthorRepository.findAll()).thenReturn(List.of());
        when(scopusAffiliationRepository.findAll()).thenReturn(List.of());
        when(groupRepository.findAll()).thenReturn(List.of());
        cacheService = new CacheService(
                scopusForumRepository,
                coreConferenceRankingRepository,
                rankingRepository,
                scopusAuthorRepository,
                scopusAffiliationRepository,
                groupRepository
        );
        clearInvocations(rankingRepository);
    }

    @Test
    void getCachedRankingsByIssnUsesRepositoryIssnLookup() {
        WoSRanking ranking = ranking("r1", "1234-5678", null);
        when(rankingRepository.findAllByIssn("1234-5678")).thenReturn(List.of(ranking));
        when(rankingRepository.findAllByeIssn("1234-5678")).thenReturn(List.of());

        List<WoSRanking> results = cacheService.getCachedRankingsByIssn("1234-5678");

        assertEquals(1, results.size());
        assertEquals("r1", results.getFirst().getId());
        verify(rankingRepository).findAllByIssn("1234-5678");
        verify(rankingRepository).findAllByeIssn("1234-5678");
    }

    @Test
    void getCachedRankingsByIssnIncludesEIssnFallbackAndDeduplicatesById() {
        WoSRanking ranking = ranking("same", null, "8765-4321");
        when(rankingRepository.findAllByIssn("8765-4321")).thenReturn(List.of(ranking));
        when(rankingRepository.findAllByeIssn("8765-4321")).thenReturn(List.of(ranking));

        List<WoSRanking> results = cacheService.getCachedRankingsByIssn("8765-4321");

        assertEquals(1, results.size());
        assertEquals("same", results.getFirst().getId());
    }

    @Test
    void getCachedRankingsByIssnUsesNormalizedKeyForCacheAndRepositoryQueries() {
        WoSRanking ranking = ranking("r1", "abcd-1234", null);
        when(rankingRepository.findAllByIssn("abcd-1234")).thenReturn(List.of(ranking));
        when(rankingRepository.findAllByeIssn("abcd-1234")).thenReturn(List.of());

        List<WoSRanking> first = cacheService.getCachedRankingsByIssn("  ABCD-1234 ");
        List<WoSRanking> second = cacheService.getCachedRankingsByIssn("abcd-1234");

        assertEquals(1, first.size());
        assertEquals(1, second.size());
        verify(rankingRepository, times(1)).findAllByIssn("abcd-1234");
        verify(rankingRepository, times(1)).findAllByeIssn("abcd-1234");
    }

    @Test
    void cacheRankingsDoesNotPolluteIssnCacheWithRankingIdKeys() {
        WoSRanking ranking = ranking("ranking-id", "1111-2222", null);
        when(rankingRepository.findAll()).thenReturn(List.of(ranking));
        when(rankingRepository.findAllByIssn("ranking-id")).thenReturn(List.of());
        when(rankingRepository.findAllByeIssn("ranking-id")).thenReturn(List.of());

        cacheService.cacheRankings();
        List<WoSRanking> results = cacheService.getCachedRankingsByIssn("ranking-id");

        assertEquals(0, results.size());
        verify(rankingRepository).findAllByIssn("ranking-id");
        verify(rankingRepository).findAllByeIssn("ranking-id");
    }

    private static WoSRanking ranking(String id, String issn, String eIssn) {
        WoSRanking ranking = new WoSRanking();
        ranking.setId(id);
        ranking.setIssn(issn);
        ranking.setEIssn(eIssn);
        ranking.setScore(new WoSRanking.Score());
        ranking.setWebOfScienceCategoryIndex(Map.of());
        return ranking;
    }
}
