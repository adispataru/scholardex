package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.model.doaj.DoajJournalFact;
import ro.uvt.pokedex.core.model.erih.ErihJournalFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;

import java.util.List;

/**
 * H66B M2 — the source-agnostic normalized forum record every forum source emits. {@link ForumMergeEngine}
 * resolves and merges these uniformly; {@link #idType} selects the source-specific behavior (which forum
 * id-list the {@link #externalId} lands in, the diagnostic reason strings, and the Scopus-only branches:
 * already-folded shortcut, primary-ISSN disambiguation, ambiguity-conflict resolution).
 *
 * <p>{@code issn}/{@code eIssn}/{@code aliasIssns} are raw (un-normalized) source values — the engine
 * normalizes + applies H57 token-hygiene. The Scopus eISSN is SIAM-corrected at construction so the engine
 * sees the corrected token.
 */
public record ForumSourceRecord(
        ForumIdType idType,
        String externalId,
        String name,
        String issn,
        String eIssn,
        List<String> aliasIssns,
        String aggregationType,
        String forumType,
        List<String> asjc
) {

    /** Which forum source minted the record — selects the id-list, reason strings, and Scopus-only branches. */
    public enum ForumIdType {
        SCOPUS("SCOPUS", "scopus-forum-missing-id", "scopus-forum-onboarding", "scopus-forum-ambiguous-candidates"),
        WOS("WOS", "wos-journal-missing-id", "wos-forum-onboarding", "wos-forum-ambiguous-candidates"),
        ERIH("ERIH", "erih-missing-id", "erih-forum-onboarding", "erih-forum-ambiguous-candidates"),
        DOAJ("DOAJ", "doaj-missing-id", "doaj-forum-onboarding", "doaj-forum-ambiguous-candidates"),
        OPENALEX("OPENALEX", "openalex-forum-missing-id", "openalex-forum-onboarding", "openalex-forum-ambiguous-candidates");

        private final String source;
        private final String missingIdReason;
        private final String onboardingReason;
        private final String ambiguousSkipPrefix;

        ForumIdType(String source, String missingIdReason, String onboardingReason, String ambiguousSkipPrefix) {
            this.source = source;
            this.missingIdReason = missingIdReason;
            this.onboardingReason = onboardingReason;
            this.ambiguousSkipPrefix = ambiguousSkipPrefix;
        }

        public String source() {
            return source;
        }

        public String missingIdReason() {
            return missingIdReason;
        }

        public String onboardingReason() {
            return onboardingReason;
        }

        public String ambiguousSkipPrefix() {
            return ambiguousSkipPrefix;
        }
    }

    /**
     * A Scopus forum fact. eISSN is SIAM-corrected here so the engine's normalization sees the corrected
     * token (mirrors the prior {@code correctedScopusEIssn(scopusForum)} call site). No alias ISSNs: a Scopus
     * forum carries only print + electronic ISSN.
     */
    public static ForumSourceRecord ofScopus(ScopusForumFact scopusForum) {
        return new ForumSourceRecord(
                ForumIdType.SCOPUS,
                scopusForum.getSourceId(),
                scopusForum.getPublicationName(),
                scopusForum.getIssn(),
                ForumIdentityNormalization.correctedScopusEIssn(scopusForum),
                List.of(),
                scopusForum.getAggregationType(),
                scopusForum.getForumType(),
                scopusForum.getAsjc()
        );
    }

    /**
     * A WoS journal identity (via its ranking-view DTO). Aggregation type defaults to {@code Journal}
     * (null here → the engine applies the default); WoS carries no Scopus C-scalars (forumType/asjc).
     */
    public static ForumSourceRecord ofWos(WosRankingView rankingView) {
        return new ForumSourceRecord(
                ForumIdType.WOS,
                rankingView.getId(),
                rankingView.getName(),
                rankingView.getIssn(),
                rankingView.getEIssn(),
                rankingView.getAlternativeIssns(),
                null,
                null,
                null
        );
    }

    /**
     * An ERIH+ journal. Aggregation defaults to {@code Journal} (engine applies it); no Scopus C-scalars.
     * The engine matches on ISSN (fan-out tagging the {@code erihIds} FK on every match) or, for an
     * ERIH-only venue with no matching forum, creates one.
     */
    public static ForumSourceRecord ofErih(ErihJournalFact erih) {
        return new ForumSourceRecord(
                ForumIdType.ERIH,
                erih.getId(),
                erih.getTitle(),
                erih.getIssn(),
                erih.getEIssn(),
                List.of(),
                null,
                null,
                null
        );
    }

    /**
     * A DOAJ (open-access) journal. Like ERIH, a create-or-match identity source: matches existing forums by
     * ISSN (tagging the {@code doajIds} FK) or mints one for a DOAJ-only OA journal no curated source had.
     */
    public static ForumSourceRecord ofDoaj(DoajJournalFact doaj) {
        return new ForumSourceRecord(
                ForumIdType.DOAJ,
                doaj.getId(),
                doaj.getTitle(),
                doaj.getIssn(),
                doaj.getEIssn(),
                List.of(),
                null,
                null,
                null
        );
    }

    /**
     * H66B Phase 4a Stage 3 — an OpenAlex host venue (from a publication's primary_location.source).
     * {@code externalId} is the bare OpenAlex venue id (S…); OpenAlex doesn't separate print/electronic ISSN
     * (it carries an {@code issn} list + linking {@code issn_l}), so the first token is the primary and the
     * rest are aliases — the engine folds them all into one normalized token set anyway. The OpenAlex onboarder
     * ISSN-gates this (no-ISSN conference venues are skipped, not minted name-only — that is DBLP's job).
     */
    public static ForumSourceRecord ofOpenAlex(String venueOpenAlexId, String name, List<String> issns) {
        return ofOpenAlex(venueOpenAlexId, name, issns, null);
    }

    public static ForumSourceRecord ofOpenAlex(String venueOpenAlexId, String name, List<String> issns,
                                               String openAlexSourceType) {
        List<String> tokens = issns == null ? List.of() : issns;
        String primary = tokens.isEmpty() ? null : tokens.getFirst();
        List<String> aliases = tokens.size() <= 1 ? List.of() : tokens.subList(1, tokens.size());
        return new ForumSourceRecord(
                ForumIdType.OPENALEX,
                venueOpenAlexId,
                name,
                primary,
                null,
                aliases,
                aggregationTypeForOpenAlexSourceType(openAlexSourceType),
                null,
                null
        );
    }

    /**
     * Map an OpenAlex venue {@code source.type} to our forum {@code aggregationType} vocabulary. Returns null
     * for unknown / non-discriminating types (repository, metadata, other) so the merge engine keeps any
     * existing value or falls back to its default. This is the authoritative venue-kind signal: OpenAlex's
     * work-level type is uniformly "article" for journals and conferences alike.
     */
    static String aggregationTypeForOpenAlexSourceType(String openAlexSourceType) {
        if (openAlexSourceType == null) {
            return null;
        }
        return switch (openAlexSourceType.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "journal" -> "Journal";
            case "conference" -> "Conference Proceeding";
            case "book series" -> "Book Series";
            case "ebook platform" -> "Book";
            default -> null;
        };
    }
}
