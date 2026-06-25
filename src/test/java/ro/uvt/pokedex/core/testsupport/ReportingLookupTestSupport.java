package ro.uvt.pokedex.core.testsupport;

import org.mockito.Mockito;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.service.reporting.ReportingLookupPort;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;

/**
 * Test support for the forum scoring path.
 *
 * <p>H66 B3: scoring resolves rankings through {@link ReportingLookupPort#getRankingsByForum} (the live
 * Postgres facade now reads the forum-keyed projection views by stored FK, then falls back to the legacy
 * fuzzy ISSN/name resolution). Mockito does not run interface default methods, so a plain {@code mock} of
 * {@link ReportingLookupPort} returns an empty list for {@code getRankingsByForum} and the scorer sees no
 * ranking. This helper installs a lenient stub that mirrors the interface default — delegating to the
 * test's existing {@code getRankingsByIssn} stubs (issn, then e-issn) — so unit tests keep stubbing by
 * ISSN while exercising the production {@code getRankingsByForum} entry point.</p>
 */
public final class ReportingLookupTestSupport {
    private ReportingLookupTestSupport() {
    }

    public static void delegateForumLookupToIssn(ReportingLookupPort lookupPort) {
        Mockito.lenient().when(lookupPort.getRankingsByForum(any()))
                .thenAnswer(invocation -> resolveByIssn(lookupPort, invocation.getArgument(0)));
        // H69 thread (6): WoS forum scoring now reads through the scoped getForumRankings(forum, years, categories)
        // entry point. Mockito doesn't run the interface default (which would delegate to getRankingsByForum), so
        // stub it to the same ISSN delegation. The scoped years/categories are irrelevant to these unit tests'
        // ISSN-keyed stubs, so we match any() and return the full ISSN-resolved rankings.
        Mockito.lenient().when(lookupPort.getForumRankings(any(), any(), any()))
                .thenAnswer(invocation -> resolveByIssn(lookupPort, invocation.getArgument(0)));
    }

    private static List<WoSRanking> resolveByIssn(ReportingLookupPort lookupPort, ScholardexForumView forum) {
        if (forum == null) {
            return List.of();
        }
        // Mirror the interface default but skip blank ISSNs so we don't invoke the stubbed
        // getRankingsByIssn with a null/blank arg (which a strict mock flags as a stubbing problem);
        // production getRankingsByIssn returns empty for blank input anyway.
        List<WoSRanking> rankings = lookupByIssn(lookupPort, forum.getIssn());
        if (rankings.isEmpty()) {
            rankings = lookupByIssn(lookupPort, forum.getEIssn());
        }
        return rankings;
    }

    private static List<WoSRanking> lookupByIssn(ReportingLookupPort lookupPort, String issn) {
        if (issn == null || issn.isBlank()) {
            return List.of();
        }
        List<WoSRanking> rankings = lookupPort.getRankingsByIssn(issn);
        return rankings == null ? List.of() : rankings;
    }
}
