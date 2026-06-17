package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.doaj.DoajJournalFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.repository.doaj.DoajJournalFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H66B M4-B — DOAJ onboarding as a create-or-match ForumBuilder source (the same engine path as ERIH):
 * ISSN match tags the {@code doajIds} FK; no match mints a DOAJ-only forum.
 */
@ExtendWith(MockitoExtension.class)
class DoajOnboardingServiceTest {

    @Mock private WosJournalIdentityRepository journalIdentityRepository;
    @Mock private ScopusForumFactRepository scopusForumFactRepository;
    @Mock private ScholardexForumFactRepository forumRepository;
    @Mock private ScholardexSourceLinkService sourceLinkService;
    @Mock private ScholardexIdentityConflictRepository identityConflictRepository;
    @Mock private DoajJournalFactRepository doajJournalFactRepository;

    private DoajOnboardingService service() {
        ForumMergeEngine engine = new ForumMergeEngine(
                journalIdentityRepository, scopusForumFactRepository, forumRepository, sourceLinkService,
                identityConflictRepository, new ForumMergeSafetyRule(),
                new ConflictRecorder(identityConflictRepository, sourceLinkService));
        return new DoajOnboardingService(doajJournalFactRepository, engine);
    }

    private static ScholardexForumFact forum(String id, String issn, String eIssn) {
        ScholardexForumFact f = new ScholardexForumFact();
        f.setId(id);
        f.setIssn(issn);
        f.setEIssn(eIssn);
        return f;
    }

    private static DoajJournalFact doaj(String id, String issn, String eIssn, String title) {
        DoajJournalFact d = new DoajJournalFact();
        d.setId(id);
        d.setIssn(issn);
        d.setEIssn(eIssn);
        d.setTitle(title);
        return d;
    }

    @Test
    void tagsIssnMatchedForumAndCreatesForumForDoajOnlyJournal() {
        ScholardexForumFact forumA = forum("sforum_a", "1234-5679", null);
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(journalIdentityRepository.findAll()).thenReturn(List.of());
        when(forumRepository.findAll()).thenReturn(List.of(forumA));
        when(forumRepository.save(any(ScholardexForumFact.class))).thenAnswer(i -> i.getArgument(0));
        when(doajJournalFactRepository.findAll()).thenReturn(List.of(
                doaj("D1", "12345679", null, "Matched OA Journal"),  // matches forumA
                doaj("D2", "8765-4326", null, "DOAJ-only OA Journal") // no match -> create
        ));

        ImportProcessingResult result = service().onboardDoaj();

        assertEquals(2, result.getProcessedCount());
        assertEquals(1, result.getUpdatedCount());  // forumA tagged
        assertEquals(1, result.getImportedCount()); // D2 created

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ScholardexForumFact>> tagged = ArgumentCaptor.forClass(List.class);
        verify(forumRepository).saveAll(tagged.capture());
        assertEquals(List.of("D1"), tagged.getValue().stream()
                .filter(f -> "sforum_a".equals(f.getId())).findFirst().orElseThrow().getDoajIds());
        verify(forumRepository).save(argThat(f ->
                f.getId() != null && f.getId().startsWith("sforum_")
                        && f.getDoajIds().equals(List.of("D2")) && "DOAJ".equals(f.getSource())));
    }

    @Test
    void doajOnlyJournalCreatesForumAndTagsNoExisting() {
        ScholardexForumFact forumA = forum("sforum_a", "1234-5679", null);
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(journalIdentityRepository.findAll()).thenReturn(List.of());
        when(forumRepository.findAll()).thenReturn(List.of(forumA));
        when(forumRepository.save(any(ScholardexForumFact.class))).thenAnswer(i -> i.getArgument(0));
        when(doajJournalFactRepository.findAll()).thenReturn(List.of(doaj("D9", "8765-4326", null, "Only OA")));

        ImportProcessingResult result = service().onboardDoaj();

        assertEquals(1, result.getImportedCount());
        assertEquals(0, result.getUpdatedCount());
        verify(forumRepository, never()).saveAll(anyList());
        verify(forumRepository).save(argThat(f -> f.getDoajIds().equals(List.of("D9")) && "DOAJ".equals(f.getSource())));
    }
}
