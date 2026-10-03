package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.journaldb.JournalDatabaseJournalFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.repository.journaldb.JournalDatabaseJournalFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H142 slice 4 — the journals of the title lists onboard like DOAJ's: an ISSN match tags the forum's
 * {@code journalDatabaseIds}; a journal only the lists know becomes a forum, so it can be named by ISSN.
 */
@ExtendWith(MockitoExtension.class)
class JournalDatabaseOnboardingServiceTest {

    @Mock private WosJournalIdentityRepository journalIdentityRepository;
    @Mock private ScopusForumFactRepository scopusForumFactRepository;
    @Mock private ScholardexForumFactRepository forumRepository;
    @Mock private ScholardexSourceLinkService sourceLinkService;
    @Mock private ScholardexIdentityConflictRepository identityConflictRepository;
    @Mock private JournalDatabaseJournalFactRepository factRepository;

    private JournalDatabaseOnboardingService service() {
        ForumMergeEngine engine = new ForumMergeEngine(
                journalIdentityRepository, scopusForumFactRepository, forumRepository, sourceLinkService,
                identityConflictRepository, new ForumMergeSafetyRule(),
                new ConflictRecorder(identityConflictRepository, sourceLinkService));
        return new JournalDatabaseOnboardingService(factRepository, engine);
    }

    private static JournalDatabaseJournalFact fact(String database, String issn, String eIssn, String title) {
        JournalDatabaseJournalFact f = new JournalDatabaseJournalFact();
        f.setId(database + ":" + issn);
        f.setDatabase(database);
        f.setIssn(issn);
        f.setEIssn(eIssn);
        f.setTitle(title);
        return f;
    }

    @Test
    void tagsTheForumOfAKnownJournalAndCreatesOneForAJournalOnlyTheListsKnow() {
        ScholardexForumFact musicAnalysis = new ScholardexForumFact();
        musicAnalysis.setId("sforum_ma");
        musicAnalysis.setIssn("0262-5245");
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(journalIdentityRepository.findAll()).thenReturn(List.of());
        when(forumRepository.findAll()).thenReturn(List.of(musicAnalysis));
        when(forumRepository.save(any(ScholardexForumFact.class))).thenAnswer(i -> i.getArgument(0));
        when(factRepository.findAll()).thenReturn(List.of(
                fact("JSTOR", "02625245", "14682249", "Music Analysis"),
                fact("RILM", "12219649", null, "Muzica")));

        ImportProcessingResult result = service().onboardJournalDatabases();

        assertEquals(2, result.getProcessedCount());
        assertEquals(1, result.getUpdatedCount());
        assertEquals(1, result.getImportedCount());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ScholardexForumFact>> tagged = ArgumentCaptor.forClass(List.class);
        verify(forumRepository).saveAll(tagged.capture());
        assertEquals(List.of("JSTOR:02625245"), tagged.getValue().getFirst().getJournalDatabaseIds());
        verify(forumRepository).save(argThat(f -> f.getId() != null && f.getId().startsWith("sforum_")
                && f.getJournalDatabaseIds().equals(List.of("RILM:12219649")) && "TITLE_LIST".equals(f.getSource())
                && "Muzica".equals(f.getName())));
    }
}
