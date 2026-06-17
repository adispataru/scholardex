package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.erih.ErihJournalFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.repository.erih.ErihJournalFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H66B M3 — ERIH onboarding as a create-or-match ForumBuilder source. ISSN matching is now check-digit
 * strict (engine normalization), so fixtures use valid ISSNs. Behavior runs end-to-end through
 * {@link ForumMergeEngine#ingestErih}: fan-out tagging on match, create on no-match.
 */
@ExtendWith(MockitoExtension.class)
class ErihOnboardingServiceTest {

    @Mock private WosJournalIdentityRepository journalIdentityRepository;
    @Mock private ScopusForumFactRepository scopusForumFactRepository;
    @Mock private ScholardexForumFactRepository forumRepository;
    @Mock private ScholardexSourceLinkService sourceLinkService;
    @Mock private ScholardexIdentityConflictRepository identityConflictRepository;
    @Mock private ErihJournalFactRepository erihJournalFactRepository;

    private ErihOnboardingService service() {
        ForumMergeEngine engine = new ForumMergeEngine(
                journalIdentityRepository,
                scopusForumFactRepository,
                forumRepository,
                sourceLinkService,
                identityConflictRepository,
                new ForumMergeSafetyRule(),
                new ConflictRecorder(identityConflictRepository, sourceLinkService)
        );
        return new ErihOnboardingService(erihJournalFactRepository, engine);
    }

    private static ScholardexForumFact forum(String id, String issn, String eIssn) {
        ScholardexForumFact f = new ScholardexForumFact();
        f.setId(id);
        f.setIssn(issn);
        f.setEIssn(eIssn);
        return f;
    }

    private static ErihJournalFact erih(String id, String issn, String eIssn) {
        ErihJournalFact e = new ErihJournalFact();
        e.setId(id);
        e.setIssn(issn);
        e.setEIssn(eIssn);
        return e;
    }

    @Test
    @SuppressWarnings("unchecked")
    void tagsIssnMatchedForumsAndCreatesForumForErihOnlyJournal() {
        ScholardexForumFact forumA = forum("sforum_a", "1234-5679", null);
        ScholardexForumFact forumB = forum("sforum_b", null, "2345-6787");
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(journalIdentityRepository.findAll()).thenReturn(List.of());
        when(forumRepository.findAll()).thenReturn(List.of(forumA, forumB));
        when(forumRepository.save(any(ScholardexForumFact.class))).thenAnswer(i -> i.getArgument(0));
        when(erihJournalFactRepository.findAll()).thenReturn(List.of(
                erih("E1", "12345679", null),   // matches forumA print ISSN
                erih("E2", "8765-4326", null),  // valid ISSN, matches nothing -> create
                erih("E3", null, "23456787")    // matches forumB eISSN
        ));

        ImportProcessingResult result = service().onboardErih();

        assertEquals(3, result.getProcessedCount());
        assertEquals(2, result.getUpdatedCount());   // forumA + forumB tagged
        assertEquals(1, result.getImportedCount());  // E2 created an ERIH-only forum
        assertEquals(0, result.getSkippedCount());

        // The two tagged forums are saved in the flush batch.
        ArgumentCaptor<List<ScholardexForumFact>> tagged = ArgumentCaptor.forClass(List.class);
        verify(forumRepository).saveAll(tagged.capture());
        Map<String, ScholardexForumFact> savedTagged = tagged.getValue().stream()
                .collect(Collectors.toMap(ScholardexForumFact::getId, f -> f));
        assertEquals(List.of("E1"), savedTagged.get("sforum_a").getErihIds());
        assertEquals(List.of("E3"), savedTagged.get("sforum_b").getErihIds());

        // The ERIH-only journal minted a new canonical forum carrying its erihId.
        verify(forumRepository).save(org.mockito.ArgumentMatchers.argThat(f ->
                f.getId() != null && f.getId().startsWith("sforum_")
                        && f.getErihIds().equals(List.of("E2"))
                        && "ERIH".equals(f.getSource())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void oneErihJournalMatchingTwoForumsTagsBothWithSameErihId() {
        // The split-journal signal: both forums get E1, so dedup (C1 part 2) can later merge them.
        ScholardexForumFact forumA = forum("sforum_a", "1234-5679", null);
        ScholardexForumFact forumB = forum("sforum_b", null, "2345-6787");
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(journalIdentityRepository.findAll()).thenReturn(List.of());
        when(forumRepository.findAll()).thenReturn(List.of(forumA, forumB));
        when(erihJournalFactRepository.findAll()).thenReturn(List.of(
                erih("E1", "1234-5679", "2345-6787") // print matches A, eISSN matches B
        ));

        service().onboardErih();

        ArgumentCaptor<List<ScholardexForumFact>> captor = ArgumentCaptor.forClass(List.class);
        verify(forumRepository).saveAll(captor.capture());
        Map<String, ScholardexForumFact> saved = captor.getValue().stream()
                .collect(Collectors.toMap(ScholardexForumFact::getId, f -> f));
        assertEquals(List.of("E1"), saved.get("sforum_a").getErihIds());
        assertEquals(List.of("E1"), saved.get("sforum_b").getErihIds());
    }

    @Test
    void erihOnlyJournalCreatesForumAndTagsNoExisting() {
        ScholardexForumFact forumA = forum("sforum_a", "1234-5679", null);
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(journalIdentityRepository.findAll()).thenReturn(List.of());
        when(forumRepository.findAll()).thenReturn(List.of(forumA));
        when(forumRepository.save(any(ScholardexForumFact.class))).thenAnswer(i -> i.getArgument(0));
        when(erihJournalFactRepository.findAll()).thenReturn(List.of(erih("E9", "8765-4326", null)));

        ImportProcessingResult result = service().onboardErih();

        assertEquals(1, result.getImportedCount());
        assertEquals(0, result.getUpdatedCount());
        verify(forumRepository, never()).saveAll(anyList()); // no existing forum tagged
        verify(forumRepository).save(org.mockito.ArgumentMatchers.argThat(f ->
                f.getErihIds().equals(List.of("E9")) && "ERIH".equals(f.getSource())));
    }
}
