package ro.uvt.pokedex.core.service.reporting;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

/**
 * FEAA (Ordin 6129/2016, Anexa 27) book/chapter scoring. Returns the per-publication <b>coefficient</b>
 * as the base score; the indicator formula ({@code S/N}) turns it into Pi. The coefficient is fixed by
 * publication subtype × Anexa 1 prestige-publisher membership, matching the four fišă slots:
 * <ul>
 *   <li>book ∈ Anexa 1 → 0.5 (slot 7)</li>
 *   <li>chapter ∈ Anexa 1 → 0.25 (slot 8)</li>
 *   <li>book ∉ Anexa 1 → 0.2 (slot 9)</li>
 *   <li>chapter ∉ Anexa 1, or ISI Proceedings paper → 0.1 (slot 10)</li>
 * </ul>
 * Articles (journal papers) get no score here — they are scored by the {@code ECONOMICS_JOURNAL_AIS}
 * strategy. The coefficient value uniquely identifies the slot, so the renderer groups by it.
 */
@Service
public class FeaaBookScoringService extends AbstractForumScoringService {

    private final FeaaAnexa1PublisherService anexa1PublisherService;
    private final FeaaNationalPublisherService nationalPublisherLookup;

    public FeaaBookScoringService(ReportingLookupPort lookupPort,
                                  FeaaAnexa1PublisherService anexa1PublisherService,
                                  FeaaNationalPublisherService nationalPublisherLookup) {
        super(lookupPort);
        this.anexa1PublisherService = anexa1PublisherService;
        this.nationalPublisherLookup = nationalPublisherLookup;
    }

    @Override
    public ScoringStrategy strategy() {
        return ScoringStrategy.FEAA_BOOK;
    }

    @Override
    public Score getScore(ScoringPublicationReadModel publication, Indicator indicator) {
        Score score = new Score();
        if (publication == null) {
            return score;
        }
        String subtype = PublicationSubtypeSupport.resolveSubtype(publication);
        boolean tiers2026 = indicator != null && indicator.usesFeaaBookTiers2026();
        Double coefficient = tiers2026
                ? coefficientFor2026(subtype, publication)
                : coefficientFor(subtype, publication);
        if (coefficient == null) {
            return score; // not a book/chapter/proceedings → no FEAA book points
        }
        score.setScore(coefficient);
        score.setScoringSource(strategy().name());
        score.setCoreRankingEquivalent(tiers2026 ? slotLabel2026(subtype, coefficient) : slotLabel(coefficient));
        return score;
    }

    @Override
    public Score getScore(ActivityInstance activity, Indicator indicator) {
        return new Score(); // books/chapters are publication-shaped, not activity-shaped
    }

    @Override
    public String getDescription() {
        return "FEAA book/chapter coefficient by subtype × Anexa 1 prestige-publisher membership "
                + "(book 0.5/0.2, chapter 0.25/0.1, ISI proceedings 0.1); final Pi = coefficient / N.\n";
    }

    private Double coefficientFor(String subtype, ScoringPublicationReadModel publication) {
        if (subtype == null) {
            return null;
        }
        return switch (subtype) {
            case "bk" -> isPrestige(publication) ? 0.5 : 0.2;
            case "ch" -> isPrestige(publication) ? 0.25 : 0.1;
            case "cp" -> 0.1; // articol în volume ISI Proceedings → slot 10
            default -> null;
        };
    }

    private boolean isPrestige(ScoringPublicationReadModel publication) {
        return anexa1PublisherService.isPrestigePublisher(resolvePublisher(publication));
    }

    /**
     * H66B M7: a book/chapter publication resolves its publisher from the book registry
     * ({@code scholardex.book_facts}) via {@code bookId}; otherwise (or if the book is unlisted) fall back to
     * the forum's publisher. With observed-book minting the bookId path resolves for every book venue.
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

    /** Stable label for the slot a coefficient belongs to (also lets the renderer group by it). */
    private static String slotLabel(double coefficient) {
        if (coefficient == 0.5) return "FEAA_BOOK_INTL";
        if (coefficient == 0.25) return "FEAA_CHAPTER_INTL";
        if (coefficient == 0.2) return "FEAA_BOOK_NATIONAL";
        return "FEAA_CHAPTER_OR_PROCEEDINGS";
    }

    /**
     * COMISIA 27 2026 five-slot coefficients: international Anexa 1 book 0.5 / chapter 0.3; national
     * recognized-publisher book 0.25 / chapter 0.15 (the CNCSIS register stands in for the CNATDCU
     * "lista A2" until that list is sourced — flagged approximation); anything else, including an ISI
     * Proceedings paper, 0.1.
     */
    private Double coefficientFor2026(String subtype, ScoringPublicationReadModel publication) {
        if (subtype == null) {
            return null;
        }
        return switch (subtype) {
            case "bk" -> isPrestige(publication) ? 0.5 : isNationalRecognized(publication) ? 0.25 : 0.1;
            case "ch" -> isPrestige(publication) ? 0.3 : isNationalRecognized(publication) ? 0.15 : 0.1;
            case "cp" -> 0.1;
            default -> null;
        };
    }

    /** 2026 coefficients overlap the 2016 ones with different meanings (0.25 = national BOOK now), so the
     *  slot label resolves from subtype + coefficient instead of coefficient alone. */
    private static String slotLabel2026(String subtype, double coefficient) {
        if (coefficient == 0.5) return "FEAA_BOOK_INTL";
        if (coefficient == 0.3) return "FEAA_CHAPTER_INTL";
        if (coefficient == 0.25) return "FEAA_BOOK_NATIONAL";
        if (coefficient == 0.15) return "FEAA_CHAPTER_NATIONAL";
        return "FEAA_CHAPTER_OR_PROCEEDINGS";
    }

    private boolean isNationalRecognized(ScoringPublicationReadModel publication) {
        return nationalPublisherLookup.isRecognized(resolvePublisher(publication));
    }
}
