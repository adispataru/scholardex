package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface ReportingLookupPort {
    ScholardexForumView getForum(String forumId);

    List<WoSRanking> getRankingsByIssn(String issn);

    /**
     * Resolves WoS rankings for a forum the way the rest of the app does (see
     * {@code WosForumResolutionService}): by ISSN candidates and then by normalized journal name.
     * The default here is ISSN-only (issn, then e-issn) for back-compat; the Postgres facade overrides
     * it to add the name fallback so scoring resolves the same journal the {@code /forums/{id}} view
     * shows AIS for (forums with a missing/mismatched ISSN but a matching name).
     */
    default List<WoSRanking> getRankingsByForum(ScholardexForumView forum) {
        if (forum == null) {
            return List.of();
        }
        List<WoSRanking> rankings = getRankingsByIssn(forum.getIssn());
        if (rankings.isEmpty()) {
            rankings = getRankingsByIssn(forum.getEIssn());
        }
        return rankings;
    }

    /**
     * H69 thread (6): WoS rankings for a forum scoped to the given publication {@code years} (and, optionally, WoS
     * category names) — the targeted {@code (forum_id, year[, category])} read that replaces loading a forum's entire
     * multi-year ranking blob and filtering year-by-year in the scorer. Null/empty {@code years} or {@code categories}
     * leaves that dimension unscoped.
     *
     * <p>The default delegates to {@link #getRankingsByForum} (load-all) so non-Postgres impls keep working unchanged;
     * the Postgres facade overrides it with the scoped SQL read, falling back to the same forum/ISSN/name resolution
     * when the forum-keyed views have nothing for those years. Because the scoped result is always a subset of what
     * {@link #getRankingsByForum} returns, callers that keep their in-memory year/category guards are score-identical
     * regardless of which path serves them.</p>
     */
    default List<WoSRanking> getForumRankings(
            ScholardexForumView forum, Collection<Integer> years, Collection<String> categories) {
        return getRankingsByForum(forum);
    }

    /**
     * H66B M7: the book registry entry (scholardex.book_facts) for a book-typed publication's bookId, or null.
     * Book scoring reads its publisher (Anexa-1 prestige). Default null for impls that don't serve books.
     */
    default ScholardexBookFact getBook(String bookId) {
        return null;
    }

    List<CoreConferenceRanking> getConferenceRankings(String acronym);

    List<CoreConferenceRanking> getConferenceRankingsByNormalizedTitle(String normalizedTitle);

    int getTopRankings(String categoryIndex, Integer year);

    /** IF-side twin of {@link #getTopRankings}: Q1-by-JIF journal count for the top-20% (A*) boundary. */
    int getTopRankingsIf(String categoryIndex, Integer year);

    /**
     * H60: the distinct ranking list-years present in the DB (the JCR/WoS metric years), ascending. The
     * journal-independent universe that {@code ScoreYearRangeSpec.LatestNRankings} selects its latest-n from (capped
     * at the run's referenceYear by the caller). Default empty; the Postgres facade overrides with the real query.
     */
    default java.util.List<Integer> getDistinctRankingYears() {
        return java.util.List.of();
    }

    Set<String> getUniversityAuthorIds();

    /**
     * H52 slice 8a: the latest year for which the WoS / Scopus / CORE reference data
     * is considered authoritative. Replaces the {@code Integer LAST_YEAR = 2023}
     * interface constant that pre-v1 was hanging off {@link ScoringService}. Callers
     * cap forward-dated lookups to this year so an out-of-window publication doesn't
     * produce a nonsense {@code Q?} ranking against missing data.
     *
     * <p>Default implementation returns {@code 2023} — same value as the removed
     * interface constant. A live implementation should query the actual rankings
     * collections; bumping a property is the migration path until that lands.</p>
     */
    default int maxAvailableYear() {
        return 2023;
    }

    /**
     * Whether the forum is indexed in Scopus (a {@code database='SCOPUS'} row in the forum membership view). Used by
     * the CS-journal C-tier fallback so an OpenAlex-sourced paper (which has no Scopus {@code eid}) in a Scopus
     * journal still scores. Default false; the Postgres facade overrides with the real membership query.
     */
    default boolean isForumInScopus(String forumId) {
        return false;
    }

    /**
     * H79 — whether the forum is a fee-conditioned (gold-OA) journal: a {@code database='DOAJ'} membership row with
     * {@code apc=true}. DOAJ lists only fully-OA journals, so this means publication is conditioned on paying an APC
     * (e.g. MDPI, IEEE Access). The 2026 Informatică indicators gate this out of the perspective-b/c threshold points.
     * Default false; the Postgres facade overrides with the real membership query.
     */
    default boolean isFeeJournal(String forumId) {
        return false;
    }

    /**
     * The indexing databases the forum has an active {@code member=true} row for in the forum membership
     * view (e.g. SCOPUS, DOAJ, ERIH, SCIE/SSCI/AHCI/ESCI, OPENALEX). Used by the FSP Psihologie I2/I6
     * "indexed in at least two recognized BDI" fallback. Default empty; the Postgres facade overrides
     * with the real membership query — and the @Primary {@code ReportingLookupFacade} must delegate
     * explicitly (a missing delegation silently serves this empty default).
     */
    default java.util.Set<String> getForumIndexingDatabases(String forumId) {
        return java.util.Set.of();
    }

    /**
     * Whether the forum was indexed in WoS ESCI (Emerging Sources Citation Index, no JIF quartile) as of the given
     * publication year. Year-true with carry-forward: the latest recorded year is used when {@code year} is more
     * recent than the data. Default false; the Postgres facade overrides with the real membership query.
     */
    default boolean isForumInEsci(String forumId, int year) {
        return false;
    }

    /**
     * Whether the forum was indexed in WoS AHCI (Arts &amp; Humanities Citation Index, no JIF quartile) as of the
     * given publication year — year-true with carry-forward, as {@link #isForumInEsci}. Default false.
     */
    default boolean isForumInAhci(String forumId, int year) {
        return false;
    }

    /**
     * Whether the forum was indexed in WoS SCIE (Science Citation Index Expanded) as of the given publication
     * year — year-true with carry-forward, as {@link #isForumInEsci}. Default false. Used by the PD/TE
     * eligibility strategy, whose standard accepts SCIE/SSCI/AHCI membership only (ESCI excluded).
     */
    default boolean isForumInScie(String forumId, int year) {
        return false;
    }

    /**
     * Whether the forum was indexed in WoS SSCI (Social Sciences Citation Index) as of the given publication
     * year — year-true with carry-forward, as {@link #isForumInEsci}. Default false.
     */
    default boolean isForumInSsci(String forumId, int year) {
        return false;
    }
}
