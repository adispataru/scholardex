package ro.uvt.pokedex.core.derivation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationVenueClaim;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationVenueClaimRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationDblpEvidenceRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.PublicationVenueClaimService;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H93 S1 — real-Mongo end-to-end for the venue-claim executor, modeled on florin.fortis's EuroMLSys ask
 * (the workshop-of arc) plus the claim-beats-DBLP and rebuild-durability guarantees.
 */
class VenueClaimDerivationIntegrationTest extends CanonicalDerivationIntegrationTestBase {

    private static final String PUB = "spub_claim_target";
    private static final String SERIES_FORUM = "sforum_ccis_series";
    private static final String EUROSYS_FORUM = "sforum_eurosys_stream";
    private static final String JOURNAL_FORUM = "sforum_plain_journal";

    @Autowired private PublicationVenueClaimService claimService;
    @Autowired private PublicationVenueClaimRepository claimRepository;
    @Autowired private ScholardexPublicationFactRepository publicationFactRepository;
    @Autowired private ScholardexForumFactRepository forumFactRepository;
    @Autowired private ScholardexPublicationDblpEvidenceRepository evidenceRepository;

    @BeforeEach
    void wipe() {
        mongoTemplate.getDb().drop();
        seed();
    }

    private void seed() {
        ScholardexForumFact series = new ScholardexForumFact();
        series.setId(SERIES_FORUM);
        series.setName("Communications in Computer and Information Science");
        series.setAggregationType("Book Series");
        forumFactRepository.save(series);

        ScholardexForumFact eurosys = new ScholardexForumFact();
        eurosys.setId(EUROSYS_FORUM);
        eurosys.setName("EUROSYS");
        eurosys.setAggregationType("Conference Proceeding");
        eurosys.setDblpIds(List.of("conf/eurosys"));
        forumFactRepository.save(eurosys);

        ScholardexForumFact journal = new ScholardexForumFact();
        journal.setId(JOURNAL_FORUM);
        journal.setName("Plain Journal of Things");
        journal.setAggregationType("Journal");
        forumFactRepository.save(journal);

        ScholardexPublicationFact pub = new ScholardexPublicationFact();
        pub.setId(PUB);
        pub.setTitle("Hybrid Task Scheduling for Constrained Systems");
        pub.setTitleNormalized("hybrid task scheduling for constrained systems");
        pub.setDoi("https://doi.org/10.1145/3721146.9999999");
        pub.setDoiNormalized("10.1145/3721146.9999999");
        pub.setCoverDate("2025-04-01");
        pub.setForumId(SERIES_FORUM);
        publicationFactRepository.save(pub);
    }

    @Test
    void workshopClaimStampsTheForumWritesXAtYEvidenceAndRejectRevertsExactly() {
        // --- the EuroMLSys arc: claim EUROSYS as workshop-of, via the one-step admin path ---
        PublicationVenueClaimService.ClaimApplyResult result = claimService.directClaim(
                PUB, EUROSYS_FORUM, true, "EuroMLSys", "admin@test", "workshop of EuroSys (Florin)");

        assertThat(result.outcome()).isEqualTo(PublicationVenueClaimService.ClaimOutcome.APPLIED);
        ScholardexPublicationFact pub = publicationFactRepository.findById(PUB).orElseThrow();
        assertThat(pub.getForumId()).isEqualTo(EUROSYS_FORUM);
        assertThat(pub.getOriginalForumId())
                .as("the displaced venue must survive for the H85/H92 fallbacks")
                .isEqualTo(SERIES_FORUM);

        ScholardexPublicationDblpEvidence evidence = evidenceRepository.findByPublicationId(PUB).orElseThrow();
        assertThat(evidence.getSeries()).isEqualTo("conf/eurosys");
        assertThat(evidence.getConferenceName())
                .as("the X@Y marker is what fires the scorer's workshop ladder")
                .isEqualTo("EuroMLSys@EUROSYS");
        assertThat(evidence.getMatchMethod()).isEqualTo("researcher-claim");

        // --- re-apply is idempotent and must not clobber the revert target ---
        PublicationVenueClaim claim = claimRepository.findByPublicationId(PUB).orElseThrow();
        assertThat(claim.getDisplaced().getForumId()).isEqualTo(SERIES_FORUM);
        assertThat(claim.getDisplaced().isEvidenceExisted()).isFalse();
        claimService.reapplyApproved();
        claim = claimRepository.findByPublicationId(PUB).orElseThrow();
        assertThat(claim.getDisplaced().getForumId())
                .as("displaced is captured ONCE — a re-apply must not overwrite it with the claim's own values")
                .isEqualTo(SERIES_FORUM);

        // --- rejection reverts exactly: forum back, claim-created evidence row gone ---
        claimService.reject(claim.getId(), "admin@test", "wrong venue after all");
        pub = publicationFactRepository.findById(PUB).orElseThrow();
        assertThat(pub.getForumId()).isEqualTo(SERIES_FORUM);
        assertThat(evidenceRepository.findByPublicationId(PUB))
                .as("the claim created this row, so rejection removes it entirely")
                .isEmpty();
    }

    @Test
    void claimBeatsDblpAndSurvivesARebuildStyleReplayIncludingReMinting() {
        // The machine knew a conference (dump-matched evidence) — the human says it is actually a journal.
        ScholardexPublicationDblpEvidence dblp = new ScholardexPublicationDblpEvidence();
        dblp.setPublicationId(PUB);
        dblp.setSeries("conf/atal");
        dblp.setConferenceName("AAMAS");
        dblp.setMatchMethod("dump-doi");
        dblp.setCreatedAt(Instant.now());
        evidenceRepository.save(dblp);

        claimService.directClaim(PUB, JOURNAL_FORUM, false, null, "admin@test", "actually a journal");

        ScholardexPublicationFact pub = publicationFactRepository.findById(PUB).orElseThrow();
        assertThat(pub.getForumId()).isEqualTo(JOURNAL_FORUM);
        ScholardexPublicationDblpEvidence evidence = evidenceRepository.findByPublicationId(PUB).orElseThrow();
        assertThat(evidence.getSeries())
                .as("a DBLP series left in place would let rebuildFromEvidence re-stamp the machine's forum")
                .isNull();
        PublicationVenueClaim claim = claimRepository.findByPublicationId(PUB).orElseThrow();
        assertThat(claim.getDisplaced().getEvidenceSeries()).isEqualTo("conf/atal");

        // --- rebuild-style replay: the canonical layer re-minted the publication under a NEW id ---
        publicationFactRepository.deleteById(PUB);
        ScholardexPublicationFact reminted = new ScholardexPublicationFact();
        reminted.setId("spub_reminted_new_id");
        reminted.setTitle("Hybrid Task Scheduling for Constrained Systems");
        reminted.setTitleNormalized("hybrid task scheduling for constrained systems");
        reminted.setDoi("https://doi.org/10.1145/3721146.9999999");
        reminted.setDoiNormalized("10.1145/3721146.9999999");
        reminted.setCoverDate("2025-04-01");
        reminted.setForumId(SERIES_FORUM); // the replay re-derived the machine's venue
        publicationFactRepository.save(reminted);

        PublicationVenueClaimService.ReapplySummary summary = claimService.reapplyApproved();

        assertThat(summary.applied()).isEqualTo(1);
        claim = claimRepository.findByPublicationId("spub_reminted_new_id").orElseThrow();
        assertThat(claim.getPublicationId())
                .as("the claim re-anchors on the re-minted id through its DOI")
                .isEqualTo("spub_reminted_new_id");
        assertThat(publicationFactRepository.findById("spub_reminted_new_id").orElseThrow().getForumId())
                .as("the human decision writes last — the replayed machine venue is overwritten again")
                .isEqualTo(JOURNAL_FORUM);
    }

    @Test
    void rejectDoesNotUnstampAForumALaterProcessMovedElsewhere() {
        claimService.directClaim(PUB, EUROSYS_FORUM, true, "EuroMLSys", "admin@test", null);
        // Something newer (a merge, a re-sync) moved the publication to a different forum since.
        ScholardexPublicationFact pub = publicationFactRepository.findById(PUB).orElseThrow();
        pub.setForumId(JOURNAL_FORUM);
        publicationFactRepository.save(pub);

        PublicationVenueClaim claim = claimRepository.findByPublicationId(PUB).orElseThrow();
        claimService.reject(claim.getId(), "admin@test", null);

        assertThat(publicationFactRepository.findById(PUB).orElseThrow().getForumId())
                .as("revert must not destroy newer information — only un-stamp what is still the claim's")
                .isEqualTo(JOURNAL_FORUM);
    }
}
