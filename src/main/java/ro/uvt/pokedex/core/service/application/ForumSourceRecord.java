package ro.uvt.pokedex.core.service.application;

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
        WOS("WOS", "wos-journal-missing-id", "wos-forum-onboarding", "wos-forum-ambiguous-candidates");

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
}
