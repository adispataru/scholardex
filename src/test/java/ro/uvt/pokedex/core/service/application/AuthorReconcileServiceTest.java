package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorReconcileServiceTest {

    @Mock private ScholardexAuthorFactRepository authorRepository;
    @Mock private ScholardexAuthorshipFactRepository authorshipRepository;
    @Mock private ScholardexPublicationAuthorAffiliationFactRepository pubAuthorAffiliationRepository;
    @Mock private ScholardexAuthorAffiliationFactRepository authorAffiliationRepository;
    @Mock private ScholardexPublicationFactRepository publicationRepository;
    @Mock private ScholardexSourceLinkRepository sourceLinkRepository;
    @Mock private UserRepository userRepository;
    @Mock private OpenAlexPublicationFactRepository openAlexPublicationFactRepository;
    @Mock private ScholardexIdentityConflictRepository identityConflictRepository;

    @InjectMocks private AuthorReconcileService service;

    @Test
    void namesCompatibleAcceptsOrderSwapInitialsAndDiacritics() {
        assertTrue(service.namesCompatible("Spataru, Adrian", "Adrian Spătaru"));   // order swap + diacritic
        assertTrue(service.namesCompatible("Spataru, A.", "Adrian Spataru"));        // initial vs full
        assertTrue(service.namesCompatible("Frîncu, Marc Eduard", "Marc Frîncu"));    // extra middle name
    }

    @Test
    void namesIncompatibleWhenGivenNamesClearlyDiffer() {
        assertFalse(service.namesCompatible("Spataru, Adrian", "Spataru, Sergiu V."));
        assertFalse(service.namesCompatible("Smith, John", "Smith, Jane"));
    }

    @Test
    void orcidPassMergesANameCompatibleCluster() {
        ScholardexAuthorFact scopus = author("sauth_scopus", "Spataru, Adrian", "0000-0002-0702-6276");
        scopus.setScopusAuthorIds(new ArrayList<>(List.of("55637349100")));
        ScholardexAuthorFact openAlex = author("sauth_openalex", "Adrian Spătaru", "0000-0002-0702-6276"); // no scopus ids
        when(authorRepository.findAll()).thenReturn(List.of(scopus, openAlex));

        service.reconcileByOrcid("batch", "corr");

        // Winner is the established Scopus author; the OpenAlex twin is deleted and its ORCID folded in.
        verify(authorRepository).deleteById("sauth_openalex");
        verify(authorRepository).save(argThat(a -> "sauth_scopus".equals(a.getId())
                && a.getOrcidIds().contains("0000-0002-0702-6276")));
        verify(identityConflictRepository, never()).save(any());
    }

    @Test
    void orcidPassQuarantinesAClusterWithIncompatibleNames() {
        ScholardexAuthorFact a = author("sauth_a", "Spataru, Adrian", "0000-0002-0702-6276");
        ScholardexAuthorFact b = author("sauth_b", "Spataru, Sergiu V.", "0000-0002-0702-6276");
        when(authorRepository.findAll()).thenReturn(List.of(a, b));

        service.reconcileByOrcid("batch", "corr");

        verify(authorRepository, never()).deleteById(anyString());
        verify(identityConflictRepository).save(argThat(c ->
                c.getEntityType() == ScholardexEntityType.AUTHOR
                        && "AUTHOR_SHARED_ORCID_NAME_MISMATCH".equals(c.getReasonCode())
                        && c.getCandidateCanonicalIds().contains("sauth_a")
                        && c.getCandidateCanonicalIds().contains("sauth_b")));
    }

    @Test
    void mergeRepointsAuthorshipEdgeFromLoserToWinner() {
        ScholardexAuthorFact winner = author("sauth_win", "Doe, Jane", "0000-0001-0000-0000");
        winner.setScopusAuthorIds(new ArrayList<>(List.of("111")));
        ScholardexAuthorFact loser = author("sauth_lose", "Jane Doe", "0000-0001-0000-0000");
        ScholardexAuthorshipFact edge = new ScholardexAuthorshipFact();
        edge.setId("edge1");
        edge.setPublicationId("spub_1");
        edge.setAuthorId("sauth_lose");
        edge.setSource("OPENALEX");
        when(authorRepository.findAll()).thenReturn(List.of(winner, loser));
        when(authorshipRepository.findByAuthorIdIn(List.of("sauth_lose"))).thenReturn(List.of(edge));
        when(authorshipRepository.findByPublicationIdAndAuthorIdAndSource("spub_1", "sauth_win", "OPENALEX"))
                .thenReturn(Optional.empty());

        service.reconcileByOrcid("batch", "corr");

        // No collision → the edge is re-pointed to the winner (not deleted).
        verify(authorshipRepository).save(argThat(e -> "edge1".equals(e.getId()) && "sauth_win".equals(e.getAuthorId())));
        verify(authorshipRepository, never()).deleteById("edge1");
    }

    @Test
    void mergeDropsAColllidingAuthorshipEdgeAndKeepsCorrespondingFlag() {
        ScholardexAuthorFact winner = author("sauth_win", "Doe, Jane", "0000-0001-0000-0000");
        winner.setScopusAuthorIds(new ArrayList<>(List.of("111")));
        ScholardexAuthorFact loser = author("sauth_lose", "Jane Doe", "0000-0001-0000-0000");
        ScholardexAuthorshipFact loserEdge = new ScholardexAuthorshipFact();
        loserEdge.setId("edge_loser");
        loserEdge.setPublicationId("spub_1");
        loserEdge.setAuthorId("sauth_lose");
        loserEdge.setSource("OPENALEX");
        loserEdge.setCorresponding(Boolean.TRUE);
        ScholardexAuthorshipFact winnerEdge = new ScholardexAuthorshipFact();
        winnerEdge.setId("edge_winner");
        winnerEdge.setPublicationId("spub_1");
        winnerEdge.setAuthorId("sauth_win");
        winnerEdge.setSource("OPENALEX");
        when(authorRepository.findAll()).thenReturn(List.of(winner, loser));
        when(authorshipRepository.findByAuthorIdIn(List.of("sauth_lose"))).thenReturn(List.of(loserEdge));
        when(authorshipRepository.findByPublicationIdAndAuthorIdAndSource("spub_1", "sauth_win", "OPENALEX"))
                .thenReturn(Optional.of(winnerEdge));

        service.reconcileByOrcid("batch", "corr");

        // Collision → drop the loser edge, OR its corresponding flag onto the surviving winner edge.
        verify(authorshipRepository).deleteById("edge_loser");
        verify(authorshipRepository).save(argThat(e -> "edge_winner".equals(e.getId()) && Boolean.TRUE.equals(e.getCorresponding())));
    }

    @Test
    void fuzzyStrongCoauthorOverlapIsReportedInDryRun() {
        // 'a' and 'b' (same name) share 3 co-authors and no publication => STRONG => reported, not merged.
        ScholardexAuthorFact a = sameName("sauth_a");
        ScholardexAuthorFact b = sameName("sauth_b");
        setFuzzy(false, 3);
        when(authorRepository.findAll()).thenReturn(List.of(a, b));
        when(publicationRepository.findByAuthorIdsContains("sauth_a")).thenReturn(List.of(pub("pA", "sauth_a", "c1", "c2", "c3")));
        when(publicationRepository.findByAuthorIdsContains("sauth_b")).thenReturn(List.of(pub("pB", "sauth_b", "c1", "c2", "c3")));

        service.reconcileByName("batch", "corr");

        verify(authorRepository, never()).deleteById(anyString());
        verify(identityConflictRepository).save(argThat(c -> "AUTHOR_FUZZY_MERGE_CANDIDATE".equals(c.getReasonCode())
                && c.getCandidateCanonicalIds().contains("sauth_a") && c.getCandidateCanonicalIds().contains("sauth_b")));
    }

    @Test
    void fuzzyStrongCoauthorOverlapMergesWhenApplied() {
        ScholardexAuthorFact a = sameName("sauth_a");
        a.setScopusAuthorIds(new ArrayList<>(List.of("111")));
        ScholardexAuthorFact b = sameName("sauth_b");
        setFuzzy(true, 3);
        when(authorRepository.findAll()).thenReturn(List.of(a, b));
        when(publicationRepository.findByAuthorIdsContains("sauth_a")).thenReturn(List.of(pub("pA", "sauth_a", "c1", "c2", "c3")));
        when(publicationRepository.findByAuthorIdsContains("sauth_b")).thenReturn(List.of(pub("pB", "sauth_b", "c1", "c2", "c3")));

        service.reconcileByName("batch", "corr");

        verify(authorRepository).deleteById("sauth_b"); // 'a' wins (has a scopus id)
        verify(identityConflictRepository, never()).save(any());
    }

    @Test
    void fuzzyRejectsZeroCoauthorOverlapEvenWhenApplied() {
        // Same name, same institution would not matter — zero shared co-authors => different people => dropped.
        ScholardexAuthorFact a = sameName("sauth_a");
        ScholardexAuthorFact b = sameName("sauth_b");
        setFuzzy(true, 3);
        when(authorRepository.findAll()).thenReturn(List.of(a, b));
        when(publicationRepository.findByAuthorIdsContains("sauth_a")).thenReturn(List.of(pub("pA", "sauth_a", "x1")));
        when(publicationRepository.findByAuthorIdsContains("sauth_b")).thenReturn(List.of(pub("pB", "sauth_b", "y1")));

        service.reconcileByName("batch", "corr");

        verify(authorRepository, never()).deleteById(anyString());
        verify(identityConflictRepository, never()).save(any());
    }

    @Test
    void fuzzyMediumCoauthorOverlapIsQueuedForReviewNotMerged() {
        // 1 shared co-author (< threshold 3) => MEDIUM => review conflict, never auto-merged even with apply=true.
        ScholardexAuthorFact a = sameName("sauth_a");
        ScholardexAuthorFact b = sameName("sauth_b");
        setFuzzy(true, 3);
        when(authorRepository.findAll()).thenReturn(List.of(a, b));
        when(publicationRepository.findByAuthorIdsContains("sauth_a")).thenReturn(List.of(pub("pA", "sauth_a", "c1", "x2")));
        when(publicationRepository.findByAuthorIdsContains("sauth_b")).thenReturn(List.of(pub("pB", "sauth_b", "c1", "y2")));

        service.reconcileByName("batch", "corr");

        verify(authorRepository, never()).deleteById(anyString());
        verify(identityConflictRepository).save(argThat(c -> "AUTHOR_FUZZY_MERGE_REVIEW".equals(c.getReasonCode())));
    }

    @Test
    void fuzzyHardBlocksWhenSameNameAuthorsCoAppearOnAPublication() {
        ScholardexAuthorFact a = sameName("sauth_a");
        ScholardexAuthorFact b = sameName("sauth_b");
        ScholardexPublicationFact shared = pub("spub_shared", "sauth_a", "sauth_b", "c1", "c2", "c3");
        setFuzzy(true, 3);
        when(authorRepository.findAll()).thenReturn(List.of(a, b));
        when(publicationRepository.findByAuthorIdsContains("sauth_a")).thenReturn(List.of(shared));
        when(publicationRepository.findByAuthorIdsContains("sauth_b")).thenReturn(List.of(shared));

        service.reconcileByName("batch", "corr");

        // Co-appear on the same paper => provably different people => never merged, even with high overlap.
        verify(authorRepository, never()).deleteById(anyString());
        verify(identityConflictRepository, never()).save(any());
    }

    // ── H72 slice 2: over-split (name + verified affiliation + ≥1 co-author) ──────────────────────────────

    @Test
    void overSplitMergesSameNameSharedAffiliationAndOneCoauthorEvenWhenTheyCoAppear() {
        // The Megan/Cotăescu case: same person, two AU-IDs, share UVT + co-authors, and CO-APPEAR on a paper
        // (Scopus listed them twice). The fuzzy pass hard-blocks this; the over-split pass must still merge.
        ScholardexAuthorFact a = sameName("sauth_a");
        a.setScopusAuthorIds(new ArrayList<>(List.of("111")));
        a.setAffiliationIds(new ArrayList<>(List.of("saff_uvt")));
        ScholardexAuthorFact b = sameName("sauth_b");
        b.setAffiliationIds(new ArrayList<>(List.of("saff_uvt")));
        setOverSplit(true);
        when(authorRepository.findAll()).thenReturn(List.of(a, b));
        ScholardexPublicationFact shared = pub("spub_shared", "sauth_a", "sauth_b", "c1");
        when(publicationRepository.findByAuthorIdsContains("sauth_a")).thenReturn(List.of(shared));
        when(publicationRepository.findByAuthorIdsContains("sauth_b")).thenReturn(List.of(shared));

        service.reconcileByNameAndAffiliation("batch", "corr");

        verify(authorRepository).deleteById("sauth_b"); // 'a' wins (has a scopus id); merged despite co-appearance
    }

    @Test
    void overSplitReportsCandidateInDryRun() {
        ScholardexAuthorFact a = sameName("sauth_a");
        a.setAffiliationIds(new ArrayList<>(List.of("saff_uvt")));
        ScholardexAuthorFact b = sameName("sauth_b");
        b.setAffiliationIds(new ArrayList<>(List.of("saff_uvt")));
        setOverSplit(false);
        when(authorRepository.findAll()).thenReturn(List.of(a, b));
        when(publicationRepository.findByAuthorIdsContains("sauth_a")).thenReturn(List.of(pub("pA", "sauth_a", "c1")));
        when(publicationRepository.findByAuthorIdsContains("sauth_b")).thenReturn(List.of(pub("pB", "sauth_b", "c1")));

        service.reconcileByNameAndAffiliation("batch", "corr");

        verify(authorRepository, never()).deleteById(anyString());
        verify(identityConflictRepository).save(argThat(c -> "AUTHOR_OVERSPLIT_MERGE_CANDIDATE".equals(c.getReasonCode())
                && c.getCandidateCanonicalIds().contains("sauth_a") && c.getCandidateCanonicalIds().contains("sauth_b")));
    }

    @Test
    void overSplitExcludesSharedAffiliationWithZeroSharedCoauthors() {
        // The "Chen, Xi ×7 at one institution" trap: same name + same affiliation but ZERO collaboration => different
        // people => not merged, not even reported.
        ScholardexAuthorFact a = sameName("sauth_a");
        a.setAffiliationIds(new ArrayList<>(List.of("saff_uvt")));
        ScholardexAuthorFact b = sameName("sauth_b");
        b.setAffiliationIds(new ArrayList<>(List.of("saff_uvt")));
        setOverSplit(true);
        when(authorRepository.findAll()).thenReturn(List.of(a, b));
        when(publicationRepository.findByAuthorIdsContains("sauth_a")).thenReturn(List.of(pub("pA", "sauth_a", "x1")));
        when(publicationRepository.findByAuthorIdsContains("sauth_b")).thenReturn(List.of(pub("pB", "sauth_b", "y1")));

        service.reconcileByNameAndAffiliation("batch", "corr");

        verify(authorRepository, never()).deleteById(anyString());
        verify(identityConflictRepository, never()).save(any());
    }

    @Test
    void overSplitRequiresASharedAffiliation() {
        // Same name + a shared co-author but DIFFERENT affiliations => not the over-split signature => not merged.
        ScholardexAuthorFact a = sameName("sauth_a");
        a.setAffiliationIds(new ArrayList<>(List.of("saff_x")));
        ScholardexAuthorFact b = sameName("sauth_b");
        b.setAffiliationIds(new ArrayList<>(List.of("saff_y")));
        setOverSplit(true);
        when(authorRepository.findAll()).thenReturn(List.of(a, b));
        when(publicationRepository.findByAuthorIdsContains("sauth_a")).thenReturn(List.of(pub("pA", "sauth_a", "c1")));
        when(publicationRepository.findByAuthorIdsContains("sauth_b")).thenReturn(List.of(pub("pB", "sauth_b", "c1")));

        service.reconcileByNameAndAffiliation("batch", "corr");

        verify(authorRepository, never()).deleteById(anyString());
        verify(identityConflictRepository, never()).save(any());
    }

    private void setOverSplit(boolean apply) {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "affiliationApply", apply);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxClusterSize", 50);
    }

    private void setFuzzy(boolean apply, int threshold) {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "fuzzyApply", apply);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "coauthorStrongThreshold", threshold);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxClusterSize", 50);
    }

    private ScholardexPublicationFact pub(String id, String... authorIds) {
        ScholardexPublicationFact p = new ScholardexPublicationFact();
        p.setId(id);
        p.setAuthorIds(new ArrayList<>(List.of(authorIds)));
        return p;
    }

    private ScholardexAuthorFact sameName(String id) {
        return author(id, "Dragan, Ioan", null);
    }

    private ScholardexAuthorFact author(String id, String displayName, String orcid) {
        ScholardexAuthorFact a = new ScholardexAuthorFact();
        a.setId(id);
        a.setDisplayName(displayName);
        a.setOrcidIds(new ArrayList<>(orcid == null ? List.of() : List.of(orcid)));
        a.setOpenAlexAuthorIds(new ArrayList<>());
        a.setScopusAuthorIds(new ArrayList<>());
        return a;
    }
}
