package ro.uvt.pokedex.core.derivation;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.service.derivation.CanonicalGraphBuilder;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H75 — pure invariant tests for the V2 publication build (no Mongo, no V1): DOI-keyed dedup with OpenAlex field
 * precedence, Scopus enrichment, monotonic-max citations, and the H66B Decision-0 container-DOI blocklist.
 */
class PublicationBuilderV2Test {

    private final CanonicalGraphBuilder builder = new CanonicalGraphBuilder();

    @Test
    void sharedDoiMergesWithOpenAlexPrecedenceAndScopusEnrichment() {
        ScopusPublicationFact scopus = scopusPub("2-s2.0-A", "10.1/shared", "Scopus Title", 10);
        OpenAlexPublicationFact openAlex = openAlexPub("W1", "10.1/shared", "OpenAlex Title", 99);

        CanonicalGraphBuilder.PublicationBuildResult result =
                builder.buildPublications(List.of(scopus), List.of(openAlex), CanonicalGraphBuilder.PubResolvers.empty());

        // One canonical pub (DOI-keyed): OpenAlex title/source authoritative, Scopus eid enriched, citations max.
        assertThat(result.facts()).hasSize(1);
        ScholardexPublicationFact pub = result.facts().getFirst();
        assertThat(pub.getId()).startsWith("spub_");
        assertThat(pub.getTitle()).isEqualTo("OpenAlex Title");
        assertThat(pub.getSource()).isEqualTo("OPENALEX");
        assertThat(pub.getEid()).isEqualTo("2-s2.0-A");
        assertThat(pub.getCitedByCount()).isEqualTo(99);
        // One PUBLICATION source-link per source pub, both pointing at the same canonical id.
        assertThat(result.sourceLinks()).hasSize(2);
        assertThat(result.sourceLinks()).allMatch(l -> l.canonicalEntityId().equals(pub.getId()));
    }

    @Test
    void containerDoiWithDistinctTitlesIsBlocklistedAndDoesNotMerge() {
        // Same DOI, two clearly different papers (book chapters) -> >1 title cluster -> blocklisted -> keyed by eid.
        ScopusPublicationFact a = scopusPub("2-s2.0-B", "10.1/container", "Graph Algorithms for Sparse Networks", 1);
        ScopusPublicationFact b = scopusPub("2-s2.0-C", "10.1/container", "Medieval Poetry and Its Forms", 2);

        CanonicalGraphBuilder.PublicationBuildResult result = builder.buildPublications(List.of(a, b), List.of(), CanonicalGraphBuilder.PubResolvers.empty());

        assertThat(result.facts()).hasSize(2);
        Map<String, ScholardexPublicationFact> byEid = result.facts().stream()
                .collect(Collectors.toMap(ScholardexPublicationFact::getEid, Function.identity()));
        assertThat(byEid).containsKeys("2-s2.0-B", "2-s2.0-C");
        assertThat(byEid.get("2-s2.0-B").getId()).isNotEqualTo(byEid.get("2-s2.0-C").getId());
    }

    @Test
    void sameDoiSameTitleIsOnePaperNotBlocklisted() {
        // Same DOI, same paper across two Scopus variants (e.g. online-first vs print) -> one canonical pub.
        ScopusPublicationFact a = scopusPub("2-s2.0-D", "10.1/same", "Distributed Consensus Protocols", 5);
        ScopusPublicationFact b = scopusPub("2-s2.0-E", "10.1/same", "Distributed Consensus Protocols", 6);

        CanonicalGraphBuilder.PublicationBuildResult result = builder.buildPublications(List.of(a, b), List.of(), CanonicalGraphBuilder.PubResolvers.empty());

        assertThat(result.facts()).hasSize(1);
        assertThat(result.facts().getFirst().getCitedByCount()).isEqualTo(6); // monotonic max
    }

    private ScopusPublicationFact scopusPub(String eid, String doi, String title, int cited) {
        ScopusPublicationFact f = new ScopusPublicationFact();
        f.setEid(eid);
        f.setDoi(doi);
        f.setTitle(title);
        f.setCitedByCount(cited);
        f.setSource("SCOPUS");
        f.setSourceRecordId(eid);
        return f;
    }

    private OpenAlexPublicationFact openAlexPub(String workId, String doi, String title, int cited) {
        OpenAlexPublicationFact f = new OpenAlexPublicationFact();
        f.setSourceRecordId(workId);
        f.setOpenalexWorkId(workId);
        f.setDoi(doi);
        f.setTitle(title);
        f.setCitedByCount(cited);
        f.setType("article");
        return f;
    }
}
