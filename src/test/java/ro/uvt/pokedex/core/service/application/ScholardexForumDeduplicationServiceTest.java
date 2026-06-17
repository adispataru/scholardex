package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexForumDeduplicationServiceTest {

    @Mock private ScholardexForumFactRepository forumRepository;
    @Mock private ScholardexSourceLinkRepository sourceLinkRepository;
    @Mock private ScholardexPublicationFactRepository publicationRepository;
    @Mock private ScholardexIdentityConflictRepository identityConflictRepository;

    private ScholardexForumDeduplicationService service() {
        return new ScholardexForumDeduplicationService(
                forumRepository, sourceLinkRepository, publicationRepository, identityConflictRepository,
                new ForumMergeSafetyRule());
    }

    @Test
    void forumsSharingErihIdAreClusteredAndMergedDespiteDisjointIssnsAndNames() {
        // H66 C1 part 2: ERIH onboarding tagged both forums with the same erihId (the split-journal signal);
        // dedup clusters them by the shared erih token and the safe-merge rule allows the merge.
        ScholardexForumFact a = forum("sforum_a", "Revista de Filosofie", "1111-1111", null, List.of(), List.of("jw"));
        a.setErihIds(new ArrayList<>(List.of("509624")));
        ScholardexForumFact b = forum("sforum_b", "Journal of Philosophy", "2222-2222", null, List.of(), List.of("jx"));
        b.setErihIds(new ArrayList<>(List.of("509624")));

        when(forumRepository.findAll()).thenReturn(List.of(a, b));
        when(sourceLinkRepository.findByEntityTypeAndCanonicalEntityId(ScholardexEntityType.FORUM, "sforum_b"))
                .thenReturn(List.of());
        when(publicationRepository.findByForumId("sforum_b")).thenReturn(List.of());

        ImportProcessingResult result = service().deduplicateForums("batch", "corr");

        assertEquals(1, result.getUpdatedCount());
        verify(forumRepository).deleteById("sforum_b"); // sforum_a wins (lexicographically smallest id)
        ArgumentCaptor<ScholardexForumFact> saved = ArgumentCaptor.forClass(ScholardexForumFact.class);
        verify(forumRepository).save(saved.capture());
        assertEquals("sforum_a", saved.getValue().getId());
        assertEquals(List.of("509624"), saved.getValue().getErihIds());
        assertTrue(saved.getValue().getWosForumIds().containsAll(List.of("jw", "jx")));
        verify(identityConflictRepository, never()).save(any());
    }

    private ScholardexForumFact forum(String id, String name, String issn, String eIssn, List<String> scopusIds, List<String> wosIds) {
        ScholardexForumFact f = new ScholardexForumFact();
        f.setId(id);
        f.setName(name);
        f.setIssn(issn);
        f.setEIssn(eIssn);
        f.setScopusForumIds(new ArrayList<>(scopusIds));
        f.setWosForumIds(new ArrayList<>(wosIds));
        return f;
    }

    @Test
    void mergesSamePrimaryIssnPairIntoScopusLinkedWinnerAndRepointsReferences() {
        ScholardexForumFact winner = forum("sforum_w", "Journal of Electroanalytical Chemistry", "1572-6657", null, List.of("9500154039"), List.of("jw"));
        ScholardexForumFact loser = forum("sforum_l", "J ELECTROANAL CHEM", "1572-6657", "1873-2569", List.of(), List.of("jl"));

        ScholardexSourceLink loserWosLink = new ScholardexSourceLink();
        loserWosLink.setEntityType(ScholardexEntityType.FORUM);
        loserWosLink.setSource("WOS");
        loserWosLink.setSourceRecordId("jl");
        loserWosLink.setCanonicalEntityId("sforum_l");

        when(forumRepository.findAll()).thenReturn(List.of(winner, loser));
        when(sourceLinkRepository.findByEntityTypeAndCanonicalEntityId(ScholardexEntityType.FORUM, "sforum_l"))
                .thenReturn(List.of(loserWosLink));
        when(publicationRepository.findByForumId("sforum_l")).thenReturn(List.of());

        ImportProcessingResult result = service().deduplicateForums("batch", "corr");

        assertEquals(1, result.getUpdatedCount());
        verify(forumRepository).deleteById("sforum_l");
        // source link re-pointed loser -> winner
        verify(sourceLinkRepository).save(loserWosLink);
        assertEquals("sforum_w", loserWosLink.getCanonicalEntityId());
        // winner absorbs loser wos id + loser eIssn becomes an alias
        ArgumentCaptor<ScholardexForumFact> saved = ArgumentCaptor.forClass(ScholardexForumFact.class);
        verify(forumRepository).save(saved.capture());
        assertEquals("sforum_w", saved.getValue().getId());
        assertTrue(saved.getValue().getWosForumIds().containsAll(List.of("jw", "jl")));
        assertTrue(saved.getValue().getScopusForumIds().contains("9500154039"));
        assertTrue(saved.getValue().getAliasIssns().contains("1873-2569"));
        verify(identityConflictRepository, never()).save(any());
    }

    @Test
    void mergesAbbreviationMatchAcrossEissnBridgeWithDifferentPrimaryIssn() {
        // full-name forum keyed on print ISSN; abbreviation forum keyed on the electronic ISSN
        ScholardexForumFact winner = forum("sforum_fpt", "Fixed Point Theory and Applications", "1687-1820", "1687-1812", List.of("s2"), List.of("jw"));
        ScholardexForumFact loser = forum("sforum_abbr", "FIXED POINT THEORY A", "1687-1812", null, List.of(), List.of("jl"));

        when(forumRepository.findAll()).thenReturn(List.of(winner, loser));
        when(sourceLinkRepository.findByEntityTypeAndCanonicalEntityId(ScholardexEntityType.FORUM, "sforum_abbr"))
                .thenReturn(List.of());
        when(publicationRepository.findByForumId("sforum_abbr")).thenReturn(List.of());

        ImportProcessingResult result = service().deduplicateForums("batch", "corr");

        assertEquals(1, result.getUpdatedCount());
        verify(forumRepository).deleteById("sforum_abbr");
        verify(forumRepository).save(any(ScholardexForumFact.class));
        verify(identityConflictRepository, never()).save(any());
    }

    @Test
    void quarantinesDifferentJournalsSharingAnEissnInsteadOfMerging() {
        // SIAM J. Computing vs SIAM J. Mathematical Analysis share a (mislabeled) eISSN but are
        // different journals: different primary ISSNs + names not an abbreviation match.
        ScholardexForumFact comput = forum("sforum_a", "SIAM J COMPUT", "0097-5397", "1095-7111", List.of(), List.of("jw"));
        ScholardexForumFact analysis = forum("sforum_b", "SIAM Journal on Mathematical Analysis", "0036-1410", "1095-7111", List.of("26418"), List.of("jx"));

        when(forumRepository.findAll()).thenReturn(List.of(comput, analysis));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.FORUM), eq("SCHOLARDEX"), eq("sforum_a|sforum_b"),
                eq("FORUM_DEDUP_NAME_MISMATCH"), eq("OPEN"))).thenReturn(Optional.empty());

        ImportProcessingResult result = service().deduplicateForums("batch", "corr");

        assertEquals(1, result.getSkippedCount());
        assertEquals(0, result.getUpdatedCount());
        verify(forumRepository, never()).deleteById(any());
        verify(forumRepository, never()).save(any());
        ArgumentCaptor<ScholardexIdentityConflict> conflict = ArgumentCaptor.forClass(ScholardexIdentityConflict.class);
        verify(identityConflictRepository).save(conflict.capture());
        assertEquals(ScholardexEntityType.FORUM, conflict.getValue().getEntityType());
        assertEquals("FORUM_DEDUP_NAME_MISMATCH", conflict.getValue().getReasonCode());
        assertEquals(List.of("sforum_a", "sforum_b"), conflict.getValue().getCandidateCanonicalIds());
    }

    @Test
    void leavesNonCollidingForumsUntouched() {
        ScholardexForumFact a = forum("sforum_x", "Journal of X", "1111-2222", null, List.of("s1"), List.of());
        ScholardexForumFact b = forum("sforum_y", "Journal of Y", "3333-4444", null, List.of("s2"), List.of());

        when(forumRepository.findAll()).thenReturn(List.of(a, b));

        ImportProcessingResult result = service().deduplicateForums("batch", "corr");

        assertEquals(0, result.getProcessedCount());
        verify(forumRepository, never()).deleteById(any());
        verify(forumRepository, never()).save(any());
        verify(identityConflictRepository, never()).save(any());
    }
}
