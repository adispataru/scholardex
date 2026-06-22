package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * H67: the Hirsch index over a set of publications, generalized to any per-publication citation count so the same
 * counting-sort serves the Scholardex h (source-reported {@code citedByCount}) and the source-attributed variants
 * (Scopus-venue / WoS-venue / internal-graph counts from H67 S1). Replaces the duplicated {@code computeHIndex}.
 *
 * <p>The source-attributed numbers are <b>indicative</b>: they are computed from Scholardex's own citation graph
 * (incoming citers classified by the citing paper's forum indexing) and undercount vs the official Scopus/WoS index
 * for researchers whose full corpus we do not hold + via the WoS conference-index gap (see the H67 plan + {@code H76}).
 */
public final class HIndexCalculator {

    private HIndexCalculator() {
    }

    /** {@code h = max k} such that {@code k} publications each have ≥ {@code k} citations (by the given extractor). */
    public static int hIndex(List<ScholardexPublicationView> publications, ToIntFunction<ScholardexPublicationView> citations) {
        int n = publications.size();
        int[] buckets = new int[n + 1];
        for (ScholardexPublicationView pub : publications) {
            int c = Math.max(0, citations.applyAsInt(pub));
            buckets[Math.min(c, n)]++;
        }
        int papersWithAtLeast = 0;
        for (int k = n; k >= 0; k--) {
            papersWithAtLeast += buckets[k];
            if (papersWithAtLeast >= k) {
                return k;
            }
        }
        return 0;
    }

    /** The per-publication citation-count extractor for a given source (the S1 projection columns). */
    public static ToIntFunction<ScholardexPublicationView> extractorFor(
            ro.uvt.pokedex.core.model.reporting.scoring.HIndexSource source) {
        return switch (source) {
            case SCHOLARDEX -> ScholardexPublicationView::getCitedByCount;
            case GRAPH -> ScholardexPublicationView::getGraphCitationCount;
            case SCOPUS_VENUE -> ScholardexPublicationView::getScopusCitationCount;
            case WOS_VENUE -> ScholardexPublicationView::getWosCitationCount;
        };
    }

    /** h-index over a publication set for one source, from the precomputed S1 columns (no self-citation filter). */
    public static int hIndexForSource(List<ScholardexPublicationView> publications,
                                      ro.uvt.pokedex.core.model.reporting.scoring.HIndexSource source) {
        return hIndex(publications, extractorFor(source));
    }

    /** All four h-indices over the same publication set. */
    public static HIndexBreakdown breakdown(List<ScholardexPublicationView> publications) {
        return new HIndexBreakdown(
                hIndex(publications, ScholardexPublicationView::getCitedByCount),
                hIndex(publications, ScholardexPublicationView::getGraphCitationCount),
                hIndex(publications, ScholardexPublicationView::getScopusCitationCount),
                hIndex(publications, ScholardexPublicationView::getWosCitationCount));
    }

    /**
     * The h-index in four flavors. {@code scholardex} is the source-reported total (unchanged from before H67);
     * {@code scopusVenue}/{@code wosVenue} are the indicative source-attributed numbers; {@code graphTotal} is the
     * internal-graph total kept for comparability (graphTotal/scholardex ≥ scopusVenue, wosVenue) — not user-facing.
     */
    public record HIndexBreakdown(int scholardex, int graphTotal, int scopusVenue, int wosVenue) {
    }
}
