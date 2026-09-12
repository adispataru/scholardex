package ro.uvt.pokedex.core.derivation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationFactRepository;
import ro.uvt.pokedex.core.service.importing.scopus.CanonicalBuildOptions;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationService;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H106 S5 — the INCREMENTAL canon path (what the scheduler's per-batch rebuild runs) resolves a Scopus
 * citation fact whose cited side is keyed by DOI or canonical id (a work with no Scopus EID found by the
 * reverse-title search) to the same canonical edge an EID-keyed fact would produce.
 */
class ReferenceTitleCitationCanonicalizationIntegrationTest extends CanonicalDerivationIntegrationTestBase {

    private static final String BATCH = "h106-reftitle-batch";

    @Autowired private ScholardexCitationCanonicalizationService citationCanonicalizationService;
    @Autowired private ScholardexPublicationFactRepository publicationFactRepository;
    @Autowired private ScopusCitationFactRepository scopusCitationFactRepository;
    @Autowired private ScholardexCitationFactRepository scholardexCitationFactRepository;

    @BeforeEach
    void wipe() {
        mongoTemplate.getDb().drop();
        publicationFactRepository.saveAll(List.of(
                canonicalPub("spub_arxiv", null, "10.48550/arxiv.0905.4601", "Considerations on Construction Ontologies"),
                canonicalPub("spub_citing", "2-s2.0-x", "10.3233/web-190396", "Sentiment analysis of Kazakh text"),
                canonicalPub("spub_report", null, null, "A user-defined technical report title")));
    }

    @Test
    void doiKeyedAndCanonicalKeyedCitedSidesResolveToCanonicalEdges() {
        scopusCitationFactRepository.saveAll(List.of(
                scopusCitation("doi:10.48550/arxiv.0905.4601", "2-s2.0-x"),
                scopusCitation("spub_report", "2-s2.0-x"),
                scopusCitation("doi:10.9999/not-in-corpus", "2-s2.0-x")));

        citationCanonicalizationService.rebuildCanonicalCitationFactsFromScopusFacts(
                new CanonicalBuildOptions(null, null, false, BATCH, null, false, false));

        List<ScholardexCitationFact> edges = scholardexCitationFactRepository.findAll();
        assertThat(edges).extracting(ScholardexCitationFact::getCitedPublicationId, ScholardexCitationFact::getCitingPublicationId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("spub_arxiv", "spub_citing"),
                        org.assertj.core.groups.Tuple.tuple("spub_report", "spub_citing"));
        assertThat(edges).allSatisfy(e -> assertThat(e.getSource()).isEqualTo("SCOPUS_PYTHON_REFTITLE_EDGE"));
    }

    private static ScholardexPublicationFact canonicalPub(String id, String eid, String doiNormalized, String title) {
        ScholardexPublicationFact p = new ScholardexPublicationFact();
        p.setId(id);
        p.setEid(eid);
        p.setDoi(doiNormalized);
        p.setDoiNormalized(doiNormalized);
        p.setTitle(title);
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return p;
    }

    private static ScopusCitationFact scopusCitation(String citedKey, String citingEid) {
        ScopusCitationFact f = new ScopusCitationFact();
        f.setCitedEid(citedKey);
        f.setCitingEid(citingEid);
        f.setSource("SCOPUS_PYTHON_REFTITLE_EDGE");
        f.setSourceRecordId(citedKey + "->" + citingEid);
        f.setSourceBatchId(BATCH);
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        return f;
    }
}
