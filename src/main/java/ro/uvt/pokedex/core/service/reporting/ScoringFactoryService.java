package ro.uvt.pokedex.core.service.reporting;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.Indicator;

@Service
@RequiredArgsConstructor
public class ScoringFactoryService {
    private final ComputerScienceConferenceScoringService computerScienceConferenceScoringService;
    private final ComputerScienceJournalScoringService computerScienceJournalScoringService;
    private final ComputerScienceBookService computerScienceBookService;
    private final ComputerScienceScoringService computerScienceScoringService;
    private final ImpactFactorJournalScoringService impactFactorJournalScoringService;
    private final RISJournalScoringService risJournalScoringService;
    private final AISJournalScoringService aisJournalScoringService;
    private final UniversityRankScoringService universityRankScoringService;
    private final CNCSISPublisherListService cncsisPublisherListService;
    private final ArtEventScoringService artEventScoringService;
    private final EconomicsJournalScoringService economicsJournalScoringService;

    public ScoringService getScoringService(Indicator.Strategy strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("Scoring strategy cannot be null");
        }
        if(strategy.equals(Indicator.Strategy.CS_CONFERENCE))
            return computerScienceConferenceScoringService;
        else if(strategy.equals(Indicator.Strategy.CS_JOURNAL))
            return computerScienceJournalScoringService;
        else if(strategy.equals(Indicator.Strategy.CS))
            return computerScienceScoringService;
        else if(strategy.equals(Indicator.Strategy.RIS))
            return risJournalScoringService;
        else if(strategy.equals(Indicator.Strategy.AIS))
            return aisJournalScoringService;
        else if(strategy.equals(Indicator.Strategy.CS_SENSE))
            return computerScienceBookService;
        else if(strategy.equals(Indicator.Strategy.UNI_RANKING))
            return universityRankScoringService;
        else if(strategy.equals(Indicator.Strategy.CNCSIS))
            return cncsisPublisherListService;
        else if(strategy.equals(Indicator.Strategy.ART_EVENT))
            return artEventScoringService;
        else if(strategy.equals(Indicator.Strategy.IMPACT_FACTOR))
            return impactFactorJournalScoringService;
        else if(strategy.equals(Indicator.Strategy.ECONOMICS_JOURNAL_AIS))
            return economicsJournalScoringService;
        throw new IllegalArgumentException("Unsupported scoring strategy: " + strategy);
    }
}
