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
 * 2026 lists of the indicator's domain, and a publisher on neither list is classified <b>A1 when it is on the WoS
 * Master Book List</b>. The standard defines A1 per publication (held by at least 25 EU/OECD university
 * libraries in WorldCat), which the platform cannot query; an international house on the Master Book
 * List is the closest computable stand-in, so the result is marked {@code tierBasis=WOS_MASTER_BOOK_LIST}
 * and is indicative. The 2026 formulas use 16 (book) and 4 (chapter) as the base and never divide by the
 * number of authors for Psychology.</p>
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
        String tier;
        java.util.Optional<Comisia28Rules> rules = Comisia28Rules.of(indicator);
        if (rules.isPresent()) {
            tier = publisherService.tierFor2026(rules.get(), publisher);
            if (tier == null && wosMasterBookListService.isRecognized(publisher)) {
                tier = "A1";
                score.getScoringInfo().put("tierBasis", "WOS_MASTER_BOOK_LIST");
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
