package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForumIdentityNormalizationTest {

    @Test
    void normalizeIssn_validBecomesHyphenated_invalidOrMalformedBecomesNull() {
        assertEquals("0317-8471", ForumIdentityNormalization.normalizeIssn("0317-8471")); // valid, kept
        assertEquals("0317-8471", ForumIdentityNormalization.normalizeIssn("03178471"));  // compact -> hyphenated
        assertEquals("0317-8471", ForumIdentityNormalization.normalizeIssn(" 0317-8471 ")); // trimmed
        assertNull(ForumIdentityNormalization.normalizeIssn("0317-8470"));  // bad check digit -> null
        assertNull(ForumIdentityNormalization.normalizeIssn("1234-567"));   // wrong length -> null
        assertNull(ForumIdentityNormalization.normalizeIssn(""));
        assertNull(ForumIdentityNormalization.normalizeIssn(null));
    }

    @Test
    void normalizeName_stripsDiacriticsPunctuationCaseAndCollapsesSpace() {
        assertEquals("revista de filosofie", ForumIdentityNormalization.normalizeName("  Revistă  de   Filosofie!  "));
        assertEquals("journal of x y", ForumIdentityNormalization.normalizeName("Journal of X & Y"));
        assertNull(ForumIdentityNormalization.normalizeName("  --  "));
        assertNull(ForumIdentityNormalization.normalizeName(null));
    }

    @Test
    void normalizeToken_lowercasesTrims_blankBecomesEmptyString() {
        assertEquals("journal", ForumIdentityNormalization.normalizeToken("  Journal "));
        assertEquals("", ForumIdentityNormalization.normalizeToken(null));
        assertEquals("", ForumIdentityNormalization.normalizeToken("   "));
    }

    @Test
    void correctedScopusEIssn_appliesSiamMislabelCorrection() {
        ScopusForumFact siam = new ScopusForumFact();
        siam.setIssn("0036-1410");      // SIAM J. Mathematical Analysis print
        siam.setEIssn("1095-7111");     // mislabeled with SIAM J. Computing's eISSN
        assertEquals("1095-7154", ForumIdentityNormalization.correctedScopusEIssn(siam));

        ScopusForumFact other = new ScopusForumFact();
        other.setIssn("0317-8471");
        other.setEIssn("2049-3630");
        assertEquals("2049-3630", ForumIdentityNormalization.correctedScopusEIssn(other)); // untouched
    }

    @Test
    void matchesIssn_scopusAndScholardex_acrossIssnEissnAlias() {
        ScopusForumFact scopus = new ScopusForumFact();
        scopus.setIssn("0317-8471");
        assertTrue(ForumIdentityNormalization.matchesIssn(scopus, Set.of("0317-8471")));
        assertFalse(ForumIdentityNormalization.matchesIssn(scopus, Set.of("2049-3630")));

        ScholardexForumFact forum = new ScholardexForumFact();
        forum.setEIssn("2049-3630");
        forum.setAliasIssns(List.of("0317-8471"));
        assertTrue(ForumIdentityNormalization.matchesIssn(forum, Set.of("2049-3630"))); // via eIssn
        assertTrue(ForumIdentityNormalization.matchesIssn(forum, Set.of("0317-8471"))); // via alias
        assertFalse(ForumIdentityNormalization.matchesIssn(forum, Set.of()));
    }

    @Test
    void tokenExtractionAndKeys() {
        ScholardexForumFact forum = new ScholardexForumFact();
        forum.setIssn("0317-8471");
        forum.setEIssn("2049-3630");
        forum.setName("Journal of X");
        forum.setAggregationType("Journal");
        assertEquals(List.of("0317-8471", "2049-3630"), ForumIdentityNormalization.issnTokensOf(forum));
        assertEquals("journal of x|journal", ForumIdentityNormalization.nameAggKeyOf(forum));

        ScopusForumFact scopus = new ScopusForumFact();
        scopus.setIssn("0317-8471");
        scopus.setPublicationName("Journal of X");
        scopus.setAggregationType("Journal");
        assertEquals(List.of("0317-8471"), ForumIdentityNormalization.scopusIssnTokens(scopus));
        assertEquals("journal of x|journal", ForumIdentityNormalization.scopusNameAggKey(scopus));
    }
}
