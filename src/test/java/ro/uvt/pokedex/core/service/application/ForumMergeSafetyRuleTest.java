package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForumMergeSafetyRuleTest {

    private final ForumMergeSafetyRule rule = new ForumMergeSafetyRule();

    @Test
    void sharedPrimaryIssnIsSafe() {
        assertTrue(rule.isSafeToMerge("0036-1410", "SIAM J MATH ANAL", "00361410", "SIAM Journal on Mathematical Analysis"));
    }

    @Test
    void differentPrimaryIssnWithUnrelatedNamesIsUnsafe() {
        // The real cross-journal bridge: Stored Products Research vs Steroid Biochemistry.
        assertFalse(rule.isSafeToMerge("0022-474X", "J STORED PROD RES", "0960-0760", "Journal of Steroid Biochemistry"));
    }

    @Test
    void differentPrimaryIssnWithAbbreviationNameIsSafe() {
        // Same journal, abbreviated vs full name -> still mergeable despite different print ISSNs.
        assertTrue(rule.isSafeToMerge("1434-193X", "EUR J ORG CHEM", "0075-4617", "European Journal of Organic Chemistry"));
    }

    @Test
    void missingPrimaryOnEitherSideAllowsMerge() {
        // eISSN-only identity: cannot prove two distinct journals -> permit (preserves legit eISSN merges).
        assertTrue(rule.isSafeToMerge(null, "Some Journal", "0960-0760", "Some Journal"));
        assertTrue(rule.isSafeToMerge("0022-474X", "A", null, "B"));
        assertTrue(rule.isSafeToMerge(null, "A", null, "B"));
    }

    @Test
    void candidateOverloadDelegatesToPairwiseRule() {
        ScholardexForumFact candidate = new ScholardexForumFact();
        candidate.setIssn("0960-0760");
        candidate.setName("Journal of Steroid Biochemistry");
        assertFalse(rule.isSafeToMerge("0022-474X", "J STORED PROD RES", candidate));
    }

    @Test
    void clusterSharingOnePrimaryIsSafe() {
        assertTrue(rule.isSafeToMergeCluster(List.of(
                forum("0036-1410", "SIAM J Math Anal"),
                forum("00361410", "SIAM Journal on Mathematical Analysis"))));
    }

    @Test
    void clusterWithDifferentPrimariesAndUnrelatedNamesIsUnsafe() {
        assertFalse(rule.isSafeToMergeCluster(List.of(
                forum("0022-474X", "J Stored Prod Res"),
                forum("0960-0760", "Journal of Steroid Biochemistry"))));
    }

    private static ScholardexForumFact forum(String issn, String name) {
        ScholardexForumFact f = new ScholardexForumFact();
        f.setIssn(issn);
        f.setName(name);
        return f;
    }
}
