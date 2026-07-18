package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ro.uvt.pokedex.core.derivation.CanonicalDerivationIntegrationTestBase;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof (real Mongo) of the 2026-07-17 partial-write hardening: after a canonicalization run,
 * every author id referenced by a canonical pub's {@code authorIds} has a persisted author doc — and a
 * re-run HEALS pre-existing damage (a phantom authorship edge + ghost synced-researcher reference) instead
 * of faithfully re-creating it, which is what the pre-fix pipeline did.
 */
class OpenAlexPartialWriteHealingIntegrationTest extends CanonicalDerivationIntegrationTestBase {

    @Autowired private OpenAlexPublicationFactRepository openAlexPublicationFactRepository;
    @Autowired private ScholardexPublicationFactRepository publicationFactRepository;
    @Autowired private ScholardexAuthorFactRepository authorFactRepository;
    @Autowired private ScholardexAuthorshipFactRepository authorshipFactRepository;
    @Autowired private OpenAlexCanonicalizationService canonicalizationService;

    private static final String DOI = "10.1000/partial-write-healing";

    @BeforeEach
    void wipe() {
        mongoTemplate.getDb().drop();
    }

    private OpenAlexPublicationFact sourceFact(String ghostSyncedId) {
        OpenAlexPublicationFact fact = new OpenAlexPublicationFact();
        fact.setSourceRecordId("W-heal-1");
        fact.setOpenalexWorkId("W-heal-1");
        fact.setDoi(DOI);
        fact.setTitle("Healing test paper");
        fact.setCoverDate("2020-01-01");
        OpenAlexPublicationFact.AuthorRef a1 = new OpenAlexPublicationFact.AuthorRef();
        a1.setPosition(0);
        a1.setDisplayName("First Author");
        a1.setOpenAlexAuthorId("A-heal-1");
        OpenAlexPublicationFact.AuthorRef a2 = new OpenAlexPublicationFact.AuthorRef();
        a2.setPosition(1);
        a2.setDisplayName("Second Author");
        a2.setOpenAlexAuthorId("A-heal-2");
        fact.setAuthorships(List.of(a1, a2));
        fact.setAuthorCount(2);
        if (ghostSyncedId != null) {
            OpenAlexPublicationFact.SyncedResearcher ghost = new OpenAlexPublicationFact.SyncedResearcher();
            ghost.setCanonicalAuthorId(ghostSyncedId);
            ghost.setOrcid("0000-0000-0000-0001"); // resolves to nothing — a true ghost
            fact.setSyncedResearchers(new java.util.ArrayList<>(List.of(ghost)));
        }
        return fact;
    }

    @Test
    void everyReferencedAuthorIdHasAPersistedDocAndGhostsAreDropped() {
        openAlexPublicationFactRepository.save(sourceFact("sauth_ghost_from_stale_profile"));

        canonicalizationService.rebuildCanonicalFacts();

        List<ScholardexPublicationFact> pubs = publicationFactRepository.findAllByDoiNormalized(DOI);
        assertThat(pubs).hasSize(1);
        ScholardexPublicationFact pub = pubs.getFirst();
        // The invariant the crash violated: no referenced author id without a persisted doc.
        assertThat(pub.getAuthorIds()).hasSize(2);
        for (String authorId : pub.getAuthorIds()) {
            assertThat(authorFactRepository.findById(authorId))
                    .as("author doc for %s referenced by pub.authorIds", authorId)
                    .isPresent();
        }
        // The ghost synced-researcher reference produced neither an authorIds entry nor an edge.
        assertThat(pub.getAuthorIds()).doesNotContain("sauth_ghost_from_stale_profile");
        assertThat(authorshipFactRepository.findByPublicationId(pub.getId()))
                .noneMatch(e -> "sauth_ghost_from_stale_profile".equals(e.getAuthorId()));
    }

    @Test
    void reRunHealsAPhantomEdgeAndPhantomAuthorIdFromACrashedRun() {
        openAlexPublicationFactRepository.save(sourceFact(null));
        canonicalizationService.rebuildCanonicalFacts();
        ScholardexPublicationFact pub = publicationFactRepository.findAllByDoiNormalized(DOI).getFirst();

        // Simulate the crashed run's damage: an OPENALEX edge + authorIds entry for an author doc never written.
        ScholardexAuthorshipFact phantom = new ScholardexAuthorshipFact();
        phantom.setPublicationId(pub.getId());
        phantom.setAuthorId("sauth_phantom_never_written");
        phantom.setSource("OPENALEX");
        authorshipFactRepository.save(phantom);
        pub.getAuthorIds().add("sauth_phantom_never_written");
        publicationFactRepository.save(pub);

        // Re-sync path (non-bulk, per-call upserts) — the bulk rebuild asserts a wiped edge collection and
        // is only valid inside the full-rebuild order, so a heal-in-place must go through resolve().
        canonicalizationService.resolve(List.of("W-heal-1"));

        ScholardexPublicationFact healed = publicationFactRepository.findAllByDoiNormalized(DOI).getFirst();
        assertThat(healed.getAuthorIds()).hasSize(2).doesNotContain("sauth_phantom_never_written");
        assertThat(authorshipFactRepository.findByPublicationId(healed.getId()))
                .noneMatch(e -> "sauth_phantom_never_written".equals(e.getAuthorId()));
    }
}
