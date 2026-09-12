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

    @Test
    void referenceTitleEdgesResolveTheCitedSideByDoiOrCanonicalId() {
        // H106 S5: a cited work with no Scopus EID (arXiv, OpenAlex-only) is keyed "doi:<doi>" in the
        // Scopus citation fact; a DOI-less one by its canonical id. The citing side is a Scopus document.
        OpenAlexPublicationFact arxiv = openAlexPub("W9", "https://doi.org/10.48550/arXiv.0905.4601");
        ScopusPublicationFact citing = scopusPub("2-s2.0-X", "10.3233/web-190396");
        ScopusCitationFact byDoi = new ScopusCitationFact();
        byDoi.setCitedEid("doi:10.48550/arxiv.0905.4601");
        byDoi.setCitingEid("2-s2.0-X");

        List<ScholardexCitationFact> edges = builder.buildCitations(List.of(citing), List.of(arxiv), List.of(byDoi));
        assertThat(edges).hasSize(1);
        String arxivCanonicalId = edges.get(0).getCitedPublicationId();
        assertThat(arxivCanonicalId).startsWith("spub_");
        assertThat(edges.get(0).getCitingPublicationId()).isNotEqualTo(arxivCanonicalId);

        ScopusCitationFact byCanonical = new ScopusCitationFact();
        byCanonical.setCitedEid(arxivCanonicalId);
        byCanonical.setCitingEid("2-s2.0-X");
        assertThat(builder.buildCitations(List.of(citing), List.of(arxiv), List.of(byCanonical)))
                .extracting(ScholardexCitationFact::getCitedPublicationId).containsExactly(arxivCanonicalId);

        ScopusCitationFact unknown = new ScopusCitationFact();
        unknown.setCitedEid("doi:10.9999/not-in-corpus");
        unknown.setCitingEid("2-s2.0-X");
        assertThat(builder.buildCitations(List.of(citing), List.of(arxiv), List.of(unknown))).isEmpty();
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
