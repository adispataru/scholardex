package ro.uvt.pokedex.core.service.importing.scopus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ro.uvt.pokedex.core.derivation.CanonicalDerivationIntegrationTestBase;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof (real Mongo) of the 2026-07-13 author-works incident fix: a publication payload whose
 * {@code author_afids} catalog is misaligned (Scopus sometimes emits within-author ';' separators) used to skip
 * the WHOLE author catalog — the publication canon then referenced fallback author ids whose author docs never
 * existed (author profile 404s). With the degrade-gracefully fix, the same durable import event now yields real
 * canonical author docs, and a batch replay of the incremental pipeline heals the previously-dangling state.
 */
class ScopusAuthorWorksDanglingAuthorHealingIntegrationTest extends CanonicalDerivationIntegrationTestBase {

    @Autowired private ScopusImportEventRepository importEventRepository;
    @Autowired private ScopusFactBuilderService factBuilderService;
    @Autowired private ScholardexAuthorCanonicalizationService authorCanonicalizationService;
    @Autowired private ScholardexPublicationCanonicalizationService publicationCanonicalizationService;
    @Autowired private ro.uvt.pokedex.core.service.application.ScholardexEdgeReconciliationService edgeReconciliationService;
    @Autowired private ScholardexPublicationFactRepository publicationFactRepository;
    @Autowired private ScholardexAuthorFactRepository authorFactRepository;
    @Autowired private ScholardexAuthorshipFactRepository authorshipFactRepository;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String BATCH = "scheduler-publication-task-heal-attempt-1";
    private static final String EID = "2-s2.0-heal1";

    @BeforeEach
    void wipe() {
        mongoTemplate.getDb().drop();
    }

    private void seedMisalignedEvent() throws Exception {
        ScopusImportEvent event = new ScopusImportEvent();
        event.setEntityType(ScopusImportEntityType.PUBLICATION);
        event.setSource("SCOPUS_PYTHON_AUTHOR_WORKS");
        event.setSourceRecordId(EID);
        event.setBatchId(BATCH);
        event.setCorrelationId(BATCH);
        event.setPayloadHash("hash-heal-1");
        event.setPayload(mapper.writeValueAsString(Map.ofEntries(
                Map.entry("eid", EID),
                Map.entry("title", "Healing works paper"),
                Map.entry("subtype", "ar"),
                Map.entry("creator", "Alice A."),
                Map.entry("author_ids", "1001;1002"),
                Map.entry("author_names", "Alice A.;Bob B."),
                Map.entry("author_afids", "af1;af2;af3"), // 3 groups for 2 authors — the incident shape
                Map.entry("author_count", "2"),
                Map.entry("source_id", "src-f1"),
                Map.entry("publicationName", "Healing Journal"),
                Map.entry("aggregationType", "Journal"),
                Map.entry("coverDate", "2024-05-01")
        )));
        importEventRepository.save(event);
    }

    private void runIncrementalPipeline() {
        CanonicalBuildOptions options = new CanonicalBuildOptions(null, null, false, BATCH, null, true, true);
        factBuilderService.buildFactsFromImportEvents(BATCH);
        authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(options);
        publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(options);
        edgeReconciliationService.reconcileEdges(BATCH);
    }

    @Test
    void misalignedAfidsPayloadYieldsRealAuthorsNotDanglingFallbacks() throws Exception {
        seedMisalignedEvent();

        runIncrementalPipeline();

        List<ScholardexPublicationFact> pubs = publicationFactRepository.findAll().stream()
                .filter(p -> EID.equals(p.getEid())).toList();
        assertThat(pubs).hasSize(1);
        ScholardexPublicationFact pub = pubs.getFirst();
        assertThat(pub.getAuthorIds()).hasSize(2);
        for (String authorId : pub.getAuthorIds()) {
            assertThat(authorFactRepository.findById(authorId))
                    .as("author doc for %s referenced by pub.authorIds", authorId)
                    .isPresent();
        }
        // Names made it through even though the afids catalog was unusable.
        assertThat(authorFactRepository.findAllById(pub.getAuthorIds()))
                .extracting(a -> a.getDisplayName())
                .containsExactlyInAnyOrder("Alice A.", "Bob B.");
    }

    @Test
    void batchReplayHealsThePreExistingDanglingState() throws Exception {
        seedMisalignedEvent();

        // Reproduce the incident's end state: pub + edges reference fallback author ids, no author docs
        // (what the pre-fix pipeline produced from this exact event).
        runIncrementalPipelineWithoutAuthors();
        ScholardexPublicationFact damaged = publicationFactRepository.findAll().stream()
                .filter(p -> EID.equals(p.getEid())).findFirst().orElseThrow();
        assertThat(damaged.getAuthorIds()).isNotEmpty();
        for (String authorId : damaged.getAuthorIds()) {
            assertThat(authorFactRepository.findById(authorId)).as("pre-heal dangling %s", authorId).isEmpty();
        }

        // The repair: replay the SAME durable batch through the fixed pipeline (the /scopus/replayBatch flow).
        runIncrementalPipeline();

        ScholardexPublicationFact healed = publicationFactRepository.findAll().stream()
                .filter(p -> EID.equals(p.getEid())).findFirst().orElseThrow();
        assertThat(healed.getAuthorIds()).hasSize(2);
        for (String authorId : healed.getAuthorIds()) {
            assertThat(authorFactRepository.findById(authorId)).as("post-heal %s", authorId).isPresent();
        }
        // The fallback edges are gone; only edges for the real authors remain.
        assertThat(authorshipFactRepository.findByPublicationId(healed.getId()))
                .allMatch(e -> healed.getAuthorIds().contains(e.getAuthorId()));
    }

    /** The pre-fix behavior: publication canon runs with NO author facts available (authors were skipped). */
    private void runIncrementalPipelineWithoutAuthors() {
        CanonicalBuildOptions options = new CanonicalBuildOptions(null, null, false, BATCH, null, true, true);
        factBuilderService.buildFactsFromImportEvents(BATCH);
        // Simulate the old full-skip: drop the scopus author facts + canonical authors the fixed builder now creates.
        mongoTemplate.getDb().getCollection("scopus.author_facts").drop();
        mongoTemplate.getDb().getCollection("scholardex.author_facts").drop();
        mongoTemplate.getDb().getCollection("scholardex.source_links")
                .deleteMany(new org.bson.Document("entityType", "AUTHOR"));
        publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(options);
        edgeReconciliationService.reconcileEdges(BATCH);
    }
}
