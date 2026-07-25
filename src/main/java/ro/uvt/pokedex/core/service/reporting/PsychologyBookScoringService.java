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
 */
@Service
public class PsychologyBookScoringService extends AbstractForumScoringService {

    private final PsihologiePublisherService publisherService;

    public PsychologyBookScoringService(ReportingLookupPort lookupPort,
                                        PsihologiePublisherService publisherService) {
        super(lookupPort);
        this.publisherService = publisherService;
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
        String tier = publisherService.tierFor(resolvePublisher(publication));
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
