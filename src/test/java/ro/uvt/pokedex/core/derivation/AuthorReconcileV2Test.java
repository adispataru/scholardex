package ro.uvt.pokedex.core.derivation;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.service.derivation.CanonicalGraphBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H71 — pure tests for the STRONG-tier cross-source author reconcile: an OpenAlex-internal split (two OpenAlex
 * author-ids for one person, no ORCID) collapses when the two carry the same surname + a shared affiliation +
 * a shared co-author and never co-appear on a paper; the same-paper hard block prevents a merge; reconcile is OFF
 * by default; and the surname/given-name helpers fold diacritics and treat initials as compatible.
 */
class AuthorReconcileV2Test {

    private final CanonicalGraphBuilder builder = new CanonicalGraphBuilder();

    // enabled, not dry-run; small thresholds irrelevant here (block size 2 -> rare -> floor 1).
    private static final CanonicalGraphBuilder.AuthorReconcileSettings ON =
            new CanonicalGraphBuilder.AuthorReconcileSettings(true, false, 300, 40, 2, 20);

    @Test
    void openAlexInternalSplitMergesViaAffiliationAndCoauthor() {
        // "Daniel Vizman" (A1) on P1 and "D. Vizman" (A2) on P2 — different OpenAlex ids, no ORCID, no shared DOI,
        // so neither id keys nor the positional bridge fire. Both share affiliation ROR R1 and co-author "Alice" (AX).
        OpenAlexPublicationFact p1 = oaPub("W1", "10.1/p1", ref("Daniel Vizman", "A1", "R1"), ref("Alice Smith", "AX", "R1"));
        OpenAlexPublicationFact p2 = oaPub("W2", "10.1/p2", ref("D. Vizman", "A2", "R1"), ref("Alice Smith", "AX", "R1"));

        // Without reconcile: 3 authors (A1, A2, AX). With reconcile: A1+A2 collapse -> 2.
        assertThat(builder.buildAuthors(List.of(), List.of(), List.of(p1, p2)).authors()).hasSize(3);

        List<?> merged = builder.buildAuthors(List.of(), List.of(), List.of(p1, p2), ON).authors();
        assertThat(merged).hasSize(2);
    }

    @Test
    void samePaperHardBlockPreventsMerge() {
        // Both "Vizman" records co-appear on ONE paper -> they are different people -> never merge, even though they
        // share an affiliation and a co-author.
        OpenAlexPublicationFact p1 = oaPub("W1", "10.1/p1",
                ref("Daniel Vizman", "A1", "R1"), ref("D. Vizman", "A2", "R1"), ref("Alice Smith", "AX", "R1"));

        assertThat(builder.buildAuthors(List.of(), List.of(), List.of(p1), ON).authors()).hasSize(3);
    }

    @Test
    void disabledByDefaultDoesNotMerge() {
        OpenAlexPublicationFact p1 = oaPub("W1", "10.1/p1", ref("Daniel Vizman", "A1", "R1"), ref("Alice Smith", "AX", "R1"));
        OpenAlexPublicationFact p2 = oaPub("W2", "10.1/p2", ref("D. Vizman", "A2", "R1"), ref("Alice Smith", "AX", "R1"));
        // The 3-arg overload defaults to AuthorReconcileSettings.disabled().
        assertThat(builder.buildAuthors(List.of(), List.of(), List.of(p1, p2)).authors()).hasSize(3);
    }

    @Test
    void requiresSharedAffiliation() {
        // Same surname + shared co-author but DIFFERENT affiliations (R1 vs R2) -> not STRONG -> no merge.
        OpenAlexPublicationFact p1 = oaPub("W1", "10.1/p1", ref("Daniel Vizman", "A1", "R1"), ref("Alice Smith", "AX", "R1"));
        OpenAlexPublicationFact p2 = oaPub("W2", "10.1/p2", ref("D. Vizman", "A2", "R2"), ref("Alice Smith", "AX", "R2"));
        assertThat(builder.buildAuthors(List.of(), List.of(), List.of(p1, p2), ON).authors()).hasSize(3);
    }

    @Test
    void transitiveSamePaperChainIsBrokenNotMerged() {
        // P1 has TWO distinct "John Smith" (A1, A3) co-authoring with Zed (AZ) -> A1,A3 are different people (hard
        // block). A2 ("John Smith") shares affiliation R1 + co-author AZ with both but co-appears with neither, so
        // A1–A2 and A2–A3 are both candidates — naively chaining A1 and A3 (same paper) into one component. The
        // cannot-link guard must apply ONE merge and skip the union that would connect A1 and A3.
        OpenAlexPublicationFact p1 = oaPub("W1", "10.1/p1",
                ref("John Smith", "A1", "R1"), ref("John Smith", "A3", "R1"), ref("Zed Ziegler", "AZ", "R1"));
        OpenAlexPublicationFact p2 = oaPub("W2", "10.1/p2",
                ref("John Smith", "A2", "R1"), ref("Zed Ziegler", "AZ", "R1"));

        // No throw; one Smith pair merges, the conflicting third stays separate -> 4 nodes (A1,A2,A3,AZ) -> 3 authors.
        List<?> authors = builder.buildAuthors(List.of(), List.of(), List.of(p1, p2), ON).authors();
        assertThat(authors).hasSize(3);
    }

    @Test
    void deterministicAcrossRuns() {
        OpenAlexPublicationFact p1 = oaPub("W1", "10.1/p1", ref("Daniel Vizman", "A1", "R1"), ref("Alice Smith", "AX", "R1"));
        OpenAlexPublicationFact p2 = oaPub("W2", "10.1/p2", ref("D. Vizman", "A2", "R1"), ref("Alice Smith", "AX", "R1"));
        List<String> ids1 = builder.buildAuthors(List.of(), List.of(), List.of(p1, p2), ON).authors()
                .stream().map(a -> a.getId()).sorted().toList();
        List<String> ids2 = builder.buildAuthors(List.of(), List.of(), List.of(p1, p2), ON).authors()
                .stream().map(a -> a.getId()).sorted().toList();
        assertThat(ids1).isEqualTo(ids2);
    }

    @Test
    void surnameAndGivenHelpers() {
        assertThat(CanonicalGraphBuilder.surnameKey("Johnson, James E.")).isEqualTo("johnson");
        assertThat(CanonicalGraphBuilder.surnameKey("Daniel Vizman")).isEqualTo("vizman");
        assertThat(CanonicalGraphBuilder.surnameKey("Sorel Mureşan")).isEqualTo("muresan"); // diacritics folded
        assertThat(CanonicalGraphBuilder.givenNameCompatible("Daniel Vizman", "D. Vizman")).isTrue();
        assertThat(CanonicalGraphBuilder.givenNameCompatible("Daniel Vizman", "Dorin Vizman")).isFalse();
        assertThat(CanonicalGraphBuilder.givenNameCompatible("Vizman", "D. Vizman")).isTrue(); // one has no given
    }

    private static OpenAlexPublicationFact.AuthorRef ref(String name, String openAlexId, String ror) {
        OpenAlexPublicationFact.AuthorRef r = new OpenAlexPublicationFact.AuthorRef();
        r.setDisplayName(name);
        r.setOpenAlexAuthorId(openAlexId);
        r.setInstitutionRors(new java.util.ArrayList<>(List.of(ror)));
        return r;
    }

    private static OpenAlexPublicationFact oaPub(String workId, String doi, OpenAlexPublicationFact.AuthorRef... refs) {
        OpenAlexPublicationFact f = new OpenAlexPublicationFact();
        f.setSourceRecordId(workId);
        f.setOpenalexWorkId(workId);
        f.setDoi(doi);
        f.setTitle("T " + workId);
        f.setType("article");
        f.setAuthorships(new java.util.ArrayList<>(List.of(refs)));
        return f;
    }
}
