package ro.uvt.pokedex.core.derivation;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.service.derivation.CanonicalGraphBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H75 — pure invariant tests for the V2 author build (core identity): the positional bridge folds a shared-paper
 * Scopus AU-ID into the OpenAlex-keyed author (the S2.2 inversion), a Scopus-only author stays Scopus-keyed, and
 * pub.authorIds resolves to canonical authors in OpenAlex order.
 */
class AuthorBuilderV2Test {

    private final CanonicalGraphBuilder builder = new CanonicalGraphBuilder();

    @Test
    void sharedPaperFoldsScopusAuIdIntoOpenAlexKeyedAuthor() {
        ScopusAuthorFact au1 = scopusAuthor("57000000001", "Frincu, Marc");
        ScopusAuthorFact au2 = scopusAuthor("57000000002", "Popescu, Ion"); // Scopus-only, no OpenAlex twin

        ScopusPublicationFact scopusPub = new ScopusPublicationFact();
        scopusPub.setEid("2-s2.0-X");
        scopusPub.setDoi("10.1/shared");
        scopusPub.setTitle("Shared Paper");
        scopusPub.setSource("SCOPUS");
        scopusPub.setSourceRecordId("2-s2.0-X");
        scopusPub.setAuthors(new java.util.ArrayList<>(List.of("57000000001")));

        OpenAlexPublicationFact openAlexPub = new OpenAlexPublicationFact();
        openAlexPub.setSourceRecordId("W1");
        openAlexPub.setDoi("10.1/shared");
        openAlexPub.setTitle("Shared Paper");
        OpenAlexPublicationFact.AuthorRef ref = new OpenAlexPublicationFact.AuthorRef();
        ref.setDisplayName("Marc Frincu");
        ref.setOrcid("0000-0003-1034-8409");
        ref.setOpenAlexAuthorId("A5000000001");
        openAlexPub.setAuthorships(List.of(ref));

        CanonicalGraphBuilder.AuthorBuildResult result =
                builder.buildAuthors(List.of(au1, au2), List.of(scopusPub), List.of(openAlexPub));

        // Two canonical authors: the merged (OpenAlex-keyed) one + the Scopus-only one.
        assertThat(result.authors()).hasSize(2);

        ScholardexAuthorFact merged = result.authors().stream()
                .filter(a -> a.getScopusAuthorIds().contains("57000000001")).findFirst().orElseThrow();
        // The AU-ID folded into the OpenAlex author: same fact carries the ORCID + OpenAlex id (no Scopus-keyed twin).
        assertThat(merged.getOrcidIds()).contains("0000-0003-1034-8409");
        assertThat(merged.getOpenAlexAuthorIds()).contains("A5000000001");
        String scopusKeyedId = "sauth_" + ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport
                .shortHash("scopus|" + "57000000001");
        assertThat(merged.getId()).isNotEqualTo(scopusKeyedId); // OpenAlex-keyed, not Scopus-keyed

        ScholardexAuthorFact scopusOnly = result.authors().stream()
                .filter(a -> a.getScopusAuthorIds().contains("57000000002")).findFirst().orElseThrow();
        assertThat(scopusOnly.getId()).isEqualTo(
                "sauth_" + ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport
                        .shortHash("scopus|" + "57000000002"));

        // pub.authorIds for the shared pub resolves to the merged author (OpenAlex order).
        assertThat(result.pubAuthorIds()).hasSize(1);
        assertThat(result.pubAuthorIds().values().iterator().next()).containsExactly(merged.getId());

        // AUTHOR source-links map each AU-ID to its canonical author.
        assertThat(result.sourceLinks())
                .anyMatch(l -> "57000000001".equals(l.sourceRecordId()) && merged.getId().equals(l.canonicalEntityId()));
    }

    private ScopusAuthorFact scopusAuthor(String authorId, String name) {
        ScopusAuthorFact f = new ScopusAuthorFact();
        f.setAuthorId(authorId);
        f.setName(name);
        f.setSource("SCOPUS");
        f.setSourceRecordId(authorId);
        return f;
    }
}
