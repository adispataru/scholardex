package ro.uvt.pokedex.core.service.reporting;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.service.importing.ArtisticEventsService;

@Service
public class ScoringFactoryService {
    @Autowired
    private ComputerScienceConferenceScoringService computerScienceConferenceScoringService;
    @Autowired
    private ComputerScienceJournalScoringService computerScienceJournalScoringService;
    @Autowired
    private ComputerScienceBookService computerScienceBookService;
    @Autowired
    private ComputerScienceScoringService computerScienceScoringService;
    @Autowired
    private ImpactFactorJournalScoringService impactFactorJournalScoringService;
    @Autowired
    private RISJournalScoringService risJournalScoringService;
    @Autowired
    private AISJournalScoringService aisJournalScoringService;
    @Autowired
    private UniversityRankScoringService universityRankScoringService;
    @Autowired
    private CNCSISPublisherListService cncsisPublisherListService;
    @Autowired
    private ArtEventScoringService artEventScoringService;
    @Autowired
    private EconomicsJournalScoringService economicsJournalScoringService;

    public ScoringService getScoringService(Indicator.Strategy strategy) {
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
        return null;
    }
}
