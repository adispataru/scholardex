package ro.uvt.pokedex.core.derivation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationMergeDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationAuthorshipDecisionRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationMergeDecisionRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.service.application.PublicationMergeService;
import ro.uvt.pokedex.core.service.importing.scopus.OpenAlexCanonicalizationService;
import ro.uvt.pokedex.core.service.importing.scopus.PublicationMergeAliasRegistry;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H84 S1 — real-Mongo end-to-end for the publication merge executor, modeled on Florin's mOSAIC pair
 * (Scopus record with eid + no DOI vs OpenAlex record with different coverDate/creator formats and no DOI —
 * no identity key can auto-merge them). Covers the live merge with edge dedupe, the rebuild re-apply, and
 * the incremental-sync resurrection guard.
 */
class PublicationMergeDerivationIntegrationTest extends CanonicalDerivationIntegrationTestBase {

    private static final String SURVIVOR = "spub_scopus_mosaic";
    private static final String DUPLICATE = "spub_openalex_mosaic";
    private static final String WORK_ID = "W1480837697";

    @Autowired private PublicationMergeService mergeService;
    @Autowired private OpenAlexCanonicalizationService openAlexCanonicalizationService;
    @Autowired private ScholardexPublicationFactRepository publicationFactRepository;
    @Autowired private ScholardexCitationFactRepository citationFactRepository;
    @Autowired private ScholardexAuthorshipFactRepository authorshipFactRepository;
    @Autowired private PublicationAuthorshipDecisionRepository authorshipDecisionRepository;
    @Autowired private ScholardexSourceLinkRepository sourceLinkRepository;
    @Autowired private PublicationMergeDecisionRepository mergeDecisionRepository;
    @Autowired private OpenAlexPublicationFactRepository openAlexPublicationFactRepository;

    @BeforeEach
    void wipe() {
        mongoTemplate.getDb().drop();
        PublicationMergeAliasRegistry.clear();
    }

    @AfterEach
    void clearRegistry() {
        PublicationMergeAliasRegistry.clear();
    }

    @Test
    void mergeCollapsesThePairSurvivesRebuildReplayAndBlocksIncrementalResurrection() {
        seedPair();

        // --- live merge ---
        PublicationMergeService.MergeApplyResult result =
                mergeService.directMerge(SURVIVOR, DUPLICATE, "admin@test", "mOSAIC duplicate (Florin)");

        assertThat(result.outcome()).isEqualTo(PublicationMergeService.MergeOutcome.MERGED);
        assertThat(publicationFactRepository.findById(DUPLICATE)).isEmpty();

        ScholardexPublicationFact survivor = publicationFactRepository.findById(SURVIVOR).orElseThrow();
        assertThat(survivor.getCitedByCount()).isEqualTo(163); // max of 163 / 161
        assertThat(survivor.getEid()).isEqualTo("2-s2.0-83155184718");

        // Citation union deduplicated: the shared citer collapses to one edge, the unique citer is re-keyed.
        List<ScholardexCitationFact> citations = citationFactRepository.findByCitedPublicationId(SURVIVOR);
        assertThat(citations).extracting(ScholardexCitationFact::getCitingPublicationId)
                .containsExactlyInAnyOrder("spub_citer_shared", "spub_citer_unique");
        assertThat(citationFactRepository.findByCitedPublicationId(DUPLICATE)).isEmpty();

        // Authorship: OpenAlex edge moved (no collision), same-source collision dropped.
        List<ScholardexAuthorshipFact> authorship = authorshipFactRepository.findByPublicationId(SURVIVOR);
        assertThat(authorship).hasSize(2); // SCOPUS + moved OPENALEX edge for the same author
        assertThat(authorshipFactRepository.findByPublicationId(DUPLICATE)).isEmpty();

        // The researcher's confirm decision on the duplicate is dropped (survivor already confirmed).
        assertThat(authorshipDecisionRepository.findByPublicationIdIn(List.of(DUPLICATE))).isEmpty();
        assertThat(authorshipDecisionRepository.findByUserEmailAndPublicationId("florin@test", SURVIVOR)).isPresent();

        // The OpenAlex work now belongs to the survivor.
        assertThat(sourceLinkRepository.findByEntityTypeAndCanonicalEntityId(ScholardexEntityType.PUBLICATION, SURVIVOR))
                .anyMatch(l -> WORK_ID.equals(l.getSourceRecordId()));

        PublicationMergeDecision decision = mergeDecisionRepository.findAll().getFirst();
        assertThat(decision.getStatus()).isEqualTo(PublicationMergeDecision.Status.APPROVED);
        assertThat(decision.getLastAppliedAt()).isNotNull();
        assertThat(decision.getDuplicate().getSourceRecordRefs()).contains("OPENALEX:" + WORK_ID);

        // --- rebuild re-apply: canon replay re-mints the duplicate from source; the pass re-merges it ---
        ScholardexPublicationFact resurrected = new ScholardexPublicationFact();
        resurrected.setId(DUPLICATE);
        resurrected.setTitle("An analysis of mOSAIC ontology for Cloud resources annotation");
        resurrected.setSource("OPENALEX");
        publicationFactRepository.save(resurrected);
        citation("spub_citer_unique", DUPLICATE); // re-keying it must dedupe against the survivor's edge

        PublicationMergeService.ReapplySummary summary = mergeService.reapplyApproved();

        assertThat(summary.merged()).isEqualTo(1);
        assertThat(publicationFactRepository.findById(DUPLICATE)).isEmpty();
        assertThat(citationFactRepository.findByCitedPublicationId(SURVIVOR)).hasSize(2); // still deduplicated

        // --- incremental resurrection guard: a fresh OpenAlex replay must NOT re-mint the duplicate ---
        OpenAlexPublicationFact work = new OpenAlexPublicationFact();
        work.setSourceRecordId(WORK_ID);
        work.setTitle("An analysis of mOSAIC ontology for Cloud resources annotation");
        work.setCoverDate("2011-01-01");
        work.setCreator("Francesco Moscato");
        work.setCitedByCount(170);
        openAlexPublicationFactRepository.save(work);

        openAlexCanonicalizationService.rebuildCanonicalFacts();

        assertThat(publicationFactRepository.findById(DUPLICATE)).isEmpty();
        assertThat(publicationFactRepository.count()).isEqualTo(3); // survivor + the two citer stubs
        assertThat(publicationFactRepository.findById(SURVIVOR).orElseThrow().getCitedByCount())
                .isEqualTo(170); // OpenAlex's broader count bumped the survivor, not a re-minted duplicate
    }

    @Test
    void verifiedNoOpWhenTheDuplicateIsAlreadyGone() {
        seedPair();
        mergeService.directMerge(SURVIVOR, DUPLICATE, "admin@test", null);

        PublicationMergeDecision decision = mergeDecisionRepository.findAll().getFirst();
        Instant firstApplied = decision.getLastAppliedAt();
        PublicationMergeService.MergeApplyResult again = mergeService.apply(decision, true);

        assertThat(again.outcome()).isEqualTo(PublicationMergeService.MergeOutcome.ALREADY_MERGED);
        assertThat(mergeDecisionRepository.findAll().getFirst().getLastAppliedAt()).isAfterOrEqualTo(firstApplied);
    }

    /* ------------------------------------------------------------------ */

    private void seedPair() {
        ScholardexPublicationFact survivor = new ScholardexPublicationFact();
        survivor.setId(SURVIVOR);
        survivor.setEid("2-s2.0-83155184718");
        survivor.setTitle("An analysis of mOSAIC ontology for cloud resources annotation");
        survivor.setTitleNormalized("an analysis of mosaic ontology for cloud resources annotation");
        survivor.setCoverDate("2011-12-14");
        survivor.setCreator("Moscato F.");
        survivor.setSource("SCOPUS_PYTHON_AUTHOR_WORKS");
        survivor.setCitedByCount(163);
        publicationFactRepository.save(survivor);

        ScholardexPublicationFact duplicate = new ScholardexPublicationFact();
        duplicate.setId(DUPLICATE);
        duplicate.setTitle("An analysis of mOSAIC ontology for Cloud resources annotation");
        duplicate.setTitleNormalized("an analysis of mosaic ontology for cloud resources annotation");
        duplicate.setCoverDate("2011-01-01");
        duplicate.setCreator("Francesco Moscato");
        duplicate.setSource("OPENALEX");
        duplicate.setCitedByCount(161);
        publicationFactRepository.save(duplicate);

        stubPublication("spub_citer_shared");
        stubPublication("spub_citer_unique");
        citation("spub_citer_shared", SURVIVOR);
        citation("spub_citer_shared", DUPLICATE);
        citation("spub_citer_unique", DUPLICATE);

        authorship(SURVIVOR, "sauth_moscato", "SCOPUS");
        authorship(DUPLICATE, "sauth_moscato", "OPENALEX");

        decision("florin@test", SURVIVOR);
        decision("florin@test", DUPLICATE);

        ScholardexSourceLink link = new ScholardexSourceLink();
        link.setEntityType(ScholardexEntityType.PUBLICATION);
        link.setSource("OPENALEX");
        link.setSourceRecordId(WORK_ID);
        link.setCanonicalEntityId(DUPLICATE);
        sourceLinkRepository.save(link);
    }

    private void stubPublication(String id) {
        ScholardexPublicationFact stub = new ScholardexPublicationFact();
        stub.setId(id);
        stub.setTitle(id);
        publicationFactRepository.save(stub);
    }

    private void citation(String citing, String cited) {
        ScholardexCitationFact edge = new ScholardexCitationFact();
        edge.setId("edge_" + citing + "_" + cited);
        edge.setCitingPublicationId(citing);
        edge.setCitedPublicationId(cited);
        citationFactRepository.save(edge);
    }

    private void authorship(String publicationId, String authorId, String source) {
        ScholardexAuthorshipFact edge = new ScholardexAuthorshipFact();
        edge.setPublicationId(publicationId);
        edge.setAuthorId(authorId);
        edge.setSource(source);
        authorshipFactRepository.save(edge);
    }

    private void decision(String userEmail, String publicationId) {
        PublicationAuthorshipDecision userDecision = new PublicationAuthorshipDecision();
        userDecision.setUserEmail(userEmail);
        userDecision.setPublicationId(publicationId);
        userDecision.setStatus(PublicationAuthorshipDecision.Status.CONFIRMED);
        authorshipDecisionRepository.save(userDecision);
    }
}
