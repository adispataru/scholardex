package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.Indicator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class ScoringFactoryServiceTest {

    private final ComputerScienceConferenceScoringService csConferenceService = mock(ComputerScienceConferenceScoringService.class);
    private final ComputerScienceJournalScoringService csJournalService = mock(ComputerScienceJournalScoringService.class);
    private final ComputerScienceBookService csBookService = mock(ComputerScienceBookService.class);
    private final ComputerScienceScoringService csService = mock(ComputerScienceScoringService.class);
    private final ImpactFactorJournalScoringService ifService = mock(ImpactFactorJournalScoringService.class);
    private final RISJournalScoringService risService = mock(RISJournalScoringService.class);
    private final AISJournalScoringService aisService = mock(AISJournalScoringService.class);
    private final UniversityRankScoringService uniService = mock(UniversityRankScoringService.class);
    private final CNCSISPublisherListService cncsisService = mock(CNCSISPublisherListService.class);
    private final ArtEventScoringService artService = mock(ArtEventScoringService.class);
    private final EconomicsJournalScoringService econService = mock(EconomicsJournalScoringService.class);

    private final ScoringFactoryService factory = new ScoringFactoryService(
            csConferenceService, csJournalService, csBookService, csService,
            ifService, risService, aisService, uniService, cncsisService, artService, econService
    );

    @Test
    void returnsConfiguredServiceForKnownStrategy() {
        ScoringService resolved = factory.getScoringService(Indicator.Strategy.CS);
        assertSame(csService, resolved);
    }

    @Test
    void returnsBookServiceForCsSenseStrategy() {
        ScoringService resolved = factory.getScoringService(Indicator.Strategy.CS_SENSE);
        assertSame(csBookService, resolved);
    }

    @Test
    void throwsForUnmappedStrategy() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getScoringService(Indicator.Strategy.GENERIC_ACTIVITY)
        );

        assertEquals("Unsupported scoring strategy: GENERIC_ACTIVITY", exception.getMessage());
    }

    @Test
    void throwsForNullStrategy() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getScoringService(null)
        );

        assertEquals("Scoring strategy cannot be null", exception.getMessage());
    }
}
