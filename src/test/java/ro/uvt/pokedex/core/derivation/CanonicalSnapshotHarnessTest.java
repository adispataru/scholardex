package ro.uvt.pokedex.core.derivation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.scopus.CanonicalBuildOptions;
import ro.uvt.pokedex.core.service.importing.scopus.OpenAlexCanonicalizationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H75 Stage 0 — calibrates the {@link CanonicalSnapshot} differential harness: run V1's canon twice on an identical
 * fixture (each from a clean DB) and assert the snapshots are byte-identical. This proves the harness's volatile-field
 * exclusion is complete — if a run-varying field leaked through, the diff would be non-empty and name it. Once V2
 * exists, the same snapshot/diff is used to assert V2 == V1 (modulo encoded S2.2 deltas).
 */
class CanonicalSnapshotHarnessTest extends CanonicalDerivationIntegrationTestBase {

    @Autowired private OpenAlexPublicationFactRepository openAlexPublicationFactRepository;
    @Autowired private ScopusPublicationFactRepository scopusPublicationFactRepository;
    @Autowired private ScopusAuthorFactRepository scopusAuthorFactRepository;
    @Autowired private OpenAlexCanonicalizationService openAlexCanonicalizationService;
    @Autowired private ScholardexAuthorCanonicalizationService authorCanonicalizationService;
    @Autowired private ScholardexPublicationCanonicalizationService publicationCanonicalizationService;

    @Test
    void v1CanonIsDeterministicAcrossTwoCleanRuns() {
        Map<String, Map<String, String>> first = seedAndRunFromCleanDb();
        Map<String, Map<String, String>> second = seedAndRunFromCleanDb();

        List<String> diff = CanonicalSnapshot.diff(first, second);
        assertThat(diff)
                .as("V1 canon must be deterministic across runs; a non-empty diff means the harness is missing a "
                        + "volatile field (or V1 is non-deterministic):\n%s", String.join("\n", diff))
                .isEmpty();

        // Sanity: the fixture actually populated several collections (the harness isn't trivially comparing empties).
        Map<String, Integer> counts = CanonicalSnapshot.counts(first);
        assertThat(counts.get("scholardex.publication_facts")).isEqualTo(1);
        assertThat(counts.get("scholardex.author_facts")).isEqualTo(1);
        assertThat(counts.get("scholardex.authorship_facts")).isGreaterThanOrEqualTo(1);
        assertThat(counts.get("scholardex.author_affiliation_facts")).isGreaterThanOrEqualTo(1);
        assertThat(counts.get("scholardex.source_links")).isGreaterThanOrEqualTo(1);
    }

    private Map<String, Map<String, String>> seedAndRunFromCleanDb() {
        mongoTemplate.getDb().drop();
        seedFixture();
        // V1 canon subset in runFull order (forums/citations/full-reconcile excluded — not needed to calibrate the harness).
        openAlexCanonicalizationService.rebuildCanonicalFacts();
        authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(CanonicalBuildOptions.defaults());
        publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(CanonicalBuildOptions.defaults());
        return CanonicalSnapshot.snapshot(mongoTemplate);
    }

    private void seedFixture() {
        String doi = "10.1000/harness-fixture";

        OpenAlexPublicationFact work = new OpenAlexPublicationFact();
        work.setSourceRecordId("W-H75-1");
        work.setOpenalexWorkId("W-H75-1");
        work.setDoi(doi);
        work.setTitle("Harness fixture title");
        work.setCoverDate("2021-05-01");
        work.setCreator("Marc Frincu");
        work.setCitedByCount(7);
        work.setSourceBatchId("fixed-batch");
        work.setSourceCorrelationId("fixed-corr");
        OpenAlexPublicationFact.AuthorRef ref = new OpenAlexPublicationFact.AuthorRef();
        ref.setDisplayName("Marc Frincu");
        ref.setOrcid("0000-0003-1034-8409");
        ref.setOpenAlexAuthorId("A5000000001");
        ref.setCorresponding(true);
        ref.getInstitutionRors().add("0583a0t97");
        work.setAuthorships(List.of(ref));
        openAlexPublicationFactRepository.save(work);

        ScopusPublicationFact pub = new ScopusPublicationFact();
        pub.setEid("2-s2.0-h75");
        pub.setDoi(doi);
        pub.setTitle("Scopus harness title");
        pub.setSource("SCOPUS");
        pub.setSourceRecordId("2-s2.0-h75");
        pub.setCoverDate("2021-05-01");
        pub.setCitedByCount(3);
        pub.setAuthors(new java.util.ArrayList<>(List.of("57000000001")));
        scopusPublicationFactRepository.save(pub);

        ScopusAuthorFact author = new ScopusAuthorFact();
        author.setAuthorId("57000000001");
        author.setName("Frincu, Marc");
        author.setSource("SCOPUS");
        author.setSourceRecordId("57000000001");
        scopusAuthorFactRepository.save(author);
    }
}
