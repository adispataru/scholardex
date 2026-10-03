package ro.uvt.pokedex.core.service.importing.journaldb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import ro.uvt.pokedex.core.model.journaldb.JournalDatabaseJournalFact;
import ro.uvt.pokedex.core.repository.journaldb.JournalDatabaseJournalFactRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H142 slice 4 — the title lists under data/journal-databases/<DATABASE>/ become one fact per journal and database. */
class JournalDatabaseDataServiceTest {

    @TempDir
    Path root;

    private final JournalDatabaseJournalFactRepository repository = mock(JournalDatabaseJournalFactRepository.class);
    private final JournalDatabaseDataService service = new JournalDatabaseDataService(repository);

    private void file(String database, String name, String content) throws IOException {
        Path dir = Files.createDirectories(root.resolve(database));
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, JournalDatabaseJournalFact> saved(int calls) {
        ArgumentCaptor<Iterable<JournalDatabaseJournalFact>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository, times(calls)).saveAll(captor.capture());
        List<JournalDatabaseJournalFact> all = new ArrayList<>();
        captor.getAllValues().forEach(it -> it.forEach(all::add));
        return all.stream().collect(Collectors.toMap(JournalDatabaseJournalFact::getId, f -> f));
    }

    @Test
    void eachDatabaseFolderBecomesItsJournalsAndAJournalOnTwoListsIsOne() throws IOException {
        file("EBSCO", "mah-coverage.txt", "Title\tISSN\tIndexing and Abstracting Start\nAmerican Music\t0734-4392\t1983\n"
                + "Muzica\t1221-9649\t2004\n");
        file("EBSCO", "hus-coverage.txt", "Title\tISSN\teISSN\tIndexing and Abstracting Stop\nAmerican Music\t0734-4392\t1945-2349\t\n"
                + "Without ISSN\t\t\t\n");
        file("JSTOR", "jstor-archive.txt", "publication_title\tprint_identifier\tonline_identifier\nAmerican Music\t0734-4392\t1945-2349\n");
        file("WEB_OF_SCIENCE", "mjl.csv", "Title,ISSN\nX,1234-5679\n");
        Files.writeString(root.resolve("EBSCO").resolve(".DS_Store"), "junk");

        JournalDatabaseDataService.ImportSummary summary = service.importFromFolder(root.toString(), "batch-1");

        Map<String, JournalDatabaseJournalFact> facts = saved(2);
        assertEquals(3, facts.size(), "two EBSCO journals, one JSTOR journal");
        JournalDatabaseJournalFact americanMusic = facts.get("EBSCO:07344392");
        assertEquals("EBSCO", americanMusic.getDatabase());
        assertEquals("07344392", americanMusic.getIssn());
        assertEquals("19452349", americanMusic.getEIssn(), "one list gives the online ISSN too");
        assertEquals(1983, americanMusic.getCoverageFrom());
        assertEquals(null, americanMusic.getCoverageTo(), "still covered");
        assertEquals(List.of("hus-coverage.txt", "mah-coverage.txt"), americanMusic.getFiles());
        assertEquals("batch-1", americanMusic.getSourceBatchId());
        assertTrue(facts.containsKey("JSTOR:07344392"));
        verify(repository).deleteByDatabase("EBSCO");
        verify(repository).deleteByDatabase("JSTOR");
        assertEquals(4, summary.result().getProcessedCount());
        assertEquals(3, summary.result().getImportedCount());
        assertEquals(1, summary.result().getSkippedCount(), "the row without an ISSN");
        assertTrue(summary.message().contains("WEB_OF_SCIENCE: not a database of the standards, ignored"), summary.message());
        assertTrue(summary.message().contains("EBSCO: 2 journals from 2 list(s)"), summary.message());
    }

    @Test
    void aDatabaseWithAFileThatDoesNotReadKeepsItsPreviousJournals() throws IOException {
        file("RILM", "rilm-journals.txt", "Coverage Policy|Source Type|ISSN|EISSN|Publication Name\nCore|Journal|0027-4631||Notes\n");
        file("RILM", "rilm-nonmusic.txt", "a list saved as a web page without its table\n");
        when(repository.countByDatabase("RILM")).thenReturn(2400L);

        JournalDatabaseDataService.ImportSummary summary = service.importFromFolder(root.toString(), "batch-2");

        verify(repository, never()).deleteByDatabase(anyString());
        verify(repository, never()).saveAll(any());
        assertEquals(1, summary.result().getErrorCount());
        assertTrue(summary.message().contains("RILM: kept the previous 2400 journals (unreadable: rilm-nonmusic.txt)"),
                summary.message());
    }

    @Test
    void withoutTheFolderNothingChanges() {
        JournalDatabaseDataService.ImportSummary summary = service.importFromFolder(root.resolve("missing").toString(), "b");

        verify(repository, never()).deleteByDatabase(anyString());
        assertEquals(1, summary.result().getErrorCount());
        assertTrue(summary.message().contains("nothing changed"));
    }

    @Test
    void anEmptyFolderChangesNothingEither() {
        JournalDatabaseDataService.ImportSummary summary = service.importFromFolder(root.toString(), "b");

        verify(repository, never()).deleteByDatabase(anyString());
        assertTrue(summary.message().startsWith("no list under "), summary.message());
    }
}
