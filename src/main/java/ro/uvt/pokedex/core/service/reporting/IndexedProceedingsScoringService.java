package ro.uvt.pokedex.core.service.reporting;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;

/**
 * FSP (Psihologie, Anexa 28) indicators I9/I10: an in-extenso proceedings paper indexed in WoS or another
 * recognised BDI scores a flat 1.0 (the co-author variant applies {@code /N} in the formula). Unlike the
 * CS conference scorer this ignores the CORE rank ladder — the fișă awards a flat point regardless of
 * conference rank. Corpus membership (Scopus/WoS-sourced) is used as the BDI-indexed proxy: only the
 * conference-proceedings subtype ({@code cp}) is scored.
 *
 * <p>The "cel mult două contribuţii/ediţie conferinţă" cap is NOT applied here (per-item scoring) — pair
 * the indicator with a {@code PerForumCap(2)} selector, which caps the kept items per proceedings forum
 * at the combine step.
 */
@Service
public class IndexedProceedingsScoringService extends AbstractForumScoringService {

    public IndexedProceedingsScoringService(ReportingLookupPort lookupPort) {
        super(lookupPort);
    }

    @Override
    public ScoringStrategy strategy() {
        return ScoringStrategy.INDEXED_PROCEEDINGS;
    }

    @Override
    public Score getScore(ScoringPublicationReadModel publication, Indicator indicator) {
        Score score = new Score();
        if (publication == null) {
            return score;
        }
        if (!"cp".equals(PublicationSubtypeSupport.resolveSubtype(publication))) {
            // Not a proceedings paper — journals/books are counted by their own indicators.
            score.getScoringInfo().put("zeroReason", "VENUE_TYPE_MISMATCH");
            return score;
        }
        score.setScore(1.0);
        score.setScoringSource(strategy().name());
        score.setCoreRankingEquivalent("BDI"); // reaches the formula as `category`
        return score;
    }

    @Override
    public Score getScore(ActivityInstance activity, Indicator indicator) {
        return new Score(); // proceedings papers are publication-shaped, not activity-shaped
    }

    @Override
    public String getDescription() {
        return "FSP Psihologie proceedings (I9/I10): flat 1.0 for an in-extenso BDI-indexed proceedings paper "
                + "(subtype cp); pair with a PerForumCap(2) selector for the per-edition cap.\n";
    }
}
