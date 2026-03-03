package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.CacheService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ComputerScienceScoringServiceTest {

    @Mock
    private ComputerScienceJournalScoringService journalScoringService;
    @Mock
    private ComputerScienceConferenceScoringService conferenceScoringService;
    @Mock
    private ComputerScienceBookService bookScoringService;
    @Mock
    private CacheService cacheService;

    @Test
    void bkSubtypeFallsBackToEmptyScoreCurrentBehaviorGuard() {
        ComputerScienceScoringService service = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                cacheService
        );

        Publication publication = new Publication();
        publication.setSubtype("bk");
        publication.setId("p-1");

        Score score = service.getScore(publication, new Indicator());

        assertEquals(0.0, score.getScore());
        assertEquals(0, score.getYear());
        assertEquals(CoreConferenceRanking.Rank.NON_RANK.toString(), score.getCategory());
        assertEquals(WoSRanking.Quarter.NOT_FOUND.toString(), score.getQuarter());
        verifyNoInteractions(journalScoringService, conferenceScoringService, bookScoringService);
    }
}
