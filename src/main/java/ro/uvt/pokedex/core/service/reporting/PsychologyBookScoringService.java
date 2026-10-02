package ro.uvt.pokedex.core.service.reporting;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

/**
 * FSP (Psihologie, Anexa 28) book/chapter scoring. Returns the <b>tier multiplier {@code m}</b> as the
 * base score {@code S} (A1→3, A2→1, B→0.5) and exposes the tier as {@code category} (A1/A2/B); the
 * publication's own subtype reaches the formula as {@code docType} ({@code bk}/{@code ch}). The indicator
 * formula assembles the fișă points from those, e.g.:
 * <ul>
 *   <li>I3 book A1/A2, principal: {@code (category=='A1'||category=='A2') && docType=='bk' ? 12*S : 0}</li>
 *   <li>I4 chapter A1/A2, principal: {@code (category=='A1'||category=='A2') && docType=='ch' ? 3*S : 0}</li>
 *   <li>I7/I8 co-author: the same with {@code /N}</li>
 *   <li>I12/I13 tier-B: {@code category=='B' && docType=='bk|ch' ? 12*S/N : 3*S/N}</li>
 * </ul>
 * Books/chapters whose publisher is not on any FSP tier score 0 (fișă: "publicaţiile care nu îndeplinesc
 * criteriile minime … nu se punctează"). Journal articles/proceedings are scored by other strategies.
 *
 * <p><b>2026 rules</b> ({@link Comisia28Rules}, OM 3019/2025 Comisia 28): the A2/B tiers come from the
 * 2026 lists of the indicator's domain, and a publisher on neither list is classified <b>A1 when it is a house of
 * international prestige</b>: on the WoS Master Book List, else on an international list or ranking
 * ({@link InternationalPublisherSupport}: SENSE A and B, the UEFISCDI lists). The standard defines A1 per publication
 * (held by at least 25 EU/OECD university libraries in WorldCat), which the platform cannot query; an international
 * house on those lists is the closest computable stand-in, so the result names the list
 * ({@code tierBasis=WOS_MASTER_BOOK_LIST}, {@code SENSE}, …) and is indicative. The 2026 formulas use 16 (book) and 4
 * (chapter) as the base and never divide by the number of authors for Psychology.</p>
 *
 * <p><b>Comisia 25, 2026</b> ({@link Comisia25Rules}): two tiers, A1 and A2, both returning S = 1 — see
 * {@link #scoreForComisia25}.</p>
 */
@Service
public class PsychologyBookScoringService extends AbstractForumScoringService {

    private final PsihologiePublisherService publisherService;
    private final WosMasterBookListService wosMasterBookListService;

    public PsychologyBookScoringService(ReportingLookupPort lookupPort,
                                        PsihologiePublisherService publisherService,
                                        WosMasterBookListService wosMasterBookListService) {
        super(lookupPort);
        this.publisherService = publisherService;
        this.wosMasterBookListService = wosMasterBookListService;
    }

    @Override
    public ScoringStrategy strategy() {
        return ScoringStrategy.PSYCH_BOOK;
    }

    @Override
    public Score getScore(ScoringPublicationReadModel publication, Indicator indicator) {
        Score score = new Score();
        if (publication == null) {
            return score;
        }
        String subtype = PublicationSubtypeSupport.resolveSubtype(publication);
        if (!"bk".equals(subtype) && !"ch".equals(subtype)) {
            // Not book-shaped — counted by the journal/proceedings indicators instead. The marker keeps
            // the UI's VENUE_TYPE_MISMATCH ("counted elsewhere") bucket instead of a misleading
            // generic formula-cutoff flag.
            score.getScoringInfo().put("zeroReason", "VENUE_TYPE_MISMATCH");
            return score;
        }
        String publisher = resolvePublisher(publication);
        java.util.Optional<Comisia25Rules> comisia25 = Comisia25Rules.of(indicator);
        if (comisia25.isPresent()) {
            return scoreForComisia25(comisia25.get(), publisher);
        }
        String tier;
        java.util.Optional<Comisia28Rules> rules = Comisia28Rules.of(indicator);
        if (rules.isPresent()) {
            tier = publisherService.tierFor2026(rules.get(), publisher);
            String international = tier == null ? internationalBasis(publisher) : null;
            if (international != null) {
                tier = "A1";
                score.getScoringInfo().put("tierBasis", international);
            }
        } else {
            tier = publisherService.tierFor(publisher);
        }
        Double m = multiplierFor(tier);
        if (m == null) {
            return score; // unlisted publisher → not punctable
        }
        score.setScore(m);
        score.setCoreRankingEquivalent(tier); // reaches the formula as `category`
        score.setScoringSource(strategy().name());
        return score;
    }

    /**
     * COMISIA 25, 2026 (definition [4]): a book or chapter counts when its publisher is on the A2 list of the
     * group, or has international prestige (A1). The annex points at "Lista A1, în vigoare" without printing
     * it; the WoS Master Book List and the international lists stand in for it, as they do for Comisia 28, and the
     * result names the list ({@code tierBasis}). The listed tier wins over the stand-in. Both tiers return
     * S = 1: here the tier changes the points of a chapter only, and the formula reads it as {@code category}.
     * Holdings in at least six WorldCat libraries, which the annex treats like A2, cannot be looked up and
     * are declared by the candidate as an activity.
     */
    private Score scoreForComisia25(Comisia25Rules rules, String publisher) {
        Score score = new Score();
        String tier = publisherService.tierFromList(rules.publisherList(), publisher);
        String international = tier == null ? internationalBasis(publisher) : null;
        if (international != null) {
            tier = "A1";
            score.getScoringInfo().put("tierBasis", international);
        }
        if (tier == null) {
            return score; // publisher on neither list → not counted
        }
        score.setScore(1.0);
        score.setCoreRankingEquivalent(tier);
        score.setScoringSource(strategy().name());
        return score;
    }

    /**
     * The list that makes a house one of international prestige — the WoS Master Book List, else an international list
     * or ranking — or null. A declared book's publisher is classified the same way ({@link PublisherCategoryService}).
     */
    private String internationalBasis(String publisher) {
        if (wosMasterBookListService.isRecognized(publisher)) {
            return "WOS_MASTER_BOOK_LIST";
        }
        return InternationalPublisherSupport.recognize(publisher).map(InternationalPublisherSupport.Recognition::key)
                .orElse(null);
    }

    @Override
    public Score getScore(ActivityInstance activity, Indicator indicator) {
        return new Score(); // books/chapters are publication-shaped, not activity-shaped
    }

    @Override
    public String getDescription() {
        return "FSP Psihologie book/chapter multiplier by A1/A2/B publisher tier (m = 3/1/0.5), exposed as S "
                + "with the tier as `category`; the indicator formula applies the 12/3/8 base and /N.\n";
    }

    private static Double multiplierFor(String tier) {
        if (tier == null) {
            return null;
        }
        return switch (tier) {
            case "A1" -> 3.0;
            case "A2" -> 1.0;
            case "B" -> 0.5;
            default -> null;
        };
    }

    /**
     * Resolve the publisher from the book registry ({@code scholardex.book_facts}) via {@code bookId};
     * otherwise fall back to the forum's publisher. Mirrors {@link FeaaBookScoringService}.
     */
    private String resolvePublisher(ScoringPublicationReadModel publication) {
        String bookId = publication.getBookId();
        if (bookId != null && !bookId.isBlank()) {
            ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact book = lookupPort.getBook(bookId);
            if (book != null) {
                return book.getPublisher();
            }
        }
        ScholardexForumView forum = lookupPort.getForum(publication.getForumId());
        return forum != null ? forum.getPublisher() : null;
    }
}
