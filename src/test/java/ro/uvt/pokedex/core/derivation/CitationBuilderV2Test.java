package ro.uvt.pokedex.core.derivation;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.service.derivation.CanonicalGraphBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H75 — pure invariant tests for the V2 citation build: internal-only edges (both endpoints held) from OpenAlex
 * referencedWorks + Scopus citation facts, deduped by {cited, citing}, external references skipped.
 */
class CitationBuilderV2Test {

    private final CanonicalGraphBuilder builder = new CanonicalGraphBuilder();

    @Test
    void buildsInternalOpenAlexAndScopusCitationsAndSkipsExternal() {
        OpenAlexPublicationFact citing = openAlexPub("W1", "10.1/citing");
        citing.getReferencedWorks().add("W2");           // held -> internal edge
        citing.getReferencedWorks().add("W_external");   // not held -> skipped
        OpenAlexPublicationFact cited = openAlexPub("W2", "10.1/cited");

        CanonicalGraphBuilder.PublicationBuildResult pubs =
                builder.buildPublications(List.of(), List.of(citing, cited), CanonicalGraphBuilder.PubResolvers.empty());
        String citingId = pubs.facts().stream().filter(p -> "10.1/citing".equals(p.getDoiNormalized()))
                .findFirst().orElseThrow().getId();
        String citedId = pubs.facts().stream().filter(p -> "10.1/cited".equals(p.getDoiNormalized()))
                .findFirst().orElseThrow().getId();

        List<ScholardexCitationFact> edges = builder.buildCitations(List.of(), List.of(citing, cited), List.of());

        assertThat(edges).hasSize(1);
        assertThat(edges.getFirst().getCitedPublicationId()).isEqualTo(citedId);
        assertThat(edges.getFirst().getCitingPublicationId()).isEqualTo(citingId);
        assertThat(edges.getFirst().getSource()).isEqualTo("OPENALEX");
    }

    @Test
    void scopusCitationResolvesViaEidAndDedupsAgainstOpenAlex() {
        // Same pub pair cited from both OpenAlex (referencedWorks) and Scopus (eids) -> one edge (natural key {cited,citing}).
        OpenAlexPublicationFact oCiting = openAlexPub("W1", "10.1/c");
        oCiting.getReferencedWorks().add("W2");
        OpenAlexPublicationFact oCited = openAlexPub("W2", "10.1/d");
        ScopusPublicationFact sCiting = scopusPub("2-s2.0-C", "10.1/c");
        ScopusPublicationFact sCited = scopusPub("2-s2.0-D", "10.1/d");
        ScopusCitationFact sc = new ScopusCitationFact();
        sc.setCitingEid("2-s2.0-C");
        sc.setCitedEid("2-s2.0-D");

        List<ScholardexCitationFact> edges = builder.buildCitations(
                List.of(sCiting, sCited), List.of(oCiting, oCited), List.of(sc));

        // OpenAlex and Scopus describe the same edge; deduped to one.
        assertThat(edges).hasSize(1);
    }

    private OpenAlexPublicationFact openAlexPub(String workId, String doi) {
        OpenAlexPublicationFact f = new OpenAlexPublicationFact();
        f.setSourceRecordId(workId);
        f.setOpenalexWorkId(workId);
        f.setDoi(doi);
        f.setTitle("T " + workId);
        return f;
    }

    private ScopusPublicationFact scopusPub(String eid, String doi) {
        ScopusPublicationFact f = new ScopusPublicationFact();
        f.setEid(eid);
        f.setDoi(doi);
        f.setTitle("T " + eid);
        f.setSource("SCOPUS");
        f.setSourceRecordId(eid);
        return f;
    }
}
