package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredatoryVenueServiceTest {

    @TempDir
    Path tmp;

    private PredatoryVenueService service(List<String> publisherLines, List<String> journalLines) throws IOException {
        Path pub = tmp.resolve("pub.csv");
        Path jrn = tmp.resolve("jrn.csv");
        Files.write(pub, publisherLines);
        Files.write(jrn, journalLines);
        PredatoryVenueService s = new PredatoryVenueService();
        s.loadLists(pub.toString(), jrn.toString());
        return s;
    }

    @Test
    void matchesExactPublisherOrJournalNameOnly() throws IOException {
        PredatoryVenueService s = service(
                List.of("url,name,abbr", "http://x/,Hikari Ltd.,", "http://y/,OMICS Publishing Group,"),
                List.of("url,name,abbr", "http://z/,International Journal of Computer Science Issues,"));

        assertTrue(s.isPredatory("Applied Mathematical Sciences", "Hikari Ltd."));          // publisher exact
        assertTrue(s.isPredatory("International Journal of Computer Science Issues", null)); // journal exact
        assertFalse(s.isPredatory("IEEE Transactions on Computers", "IEEE"));                // not listed
        // EXACT match only: a near/superset name must NOT trigger (substring matching would have).
        assertFalse(s.isPredatory(null, "Hikari Ltd Books"));
        assertFalse(s.isPredatory("International Journal of Computer Science", null));
    }

    @Test
    void allowlistExemptsContestedVenues() throws IOException {
        PredatoryVenueService s = service(
                List.of("url,name,abbr", "http://x/,Impact Journals LLC,"),
                List.of("url,name,abbr", "http://z/,Oncotarget,"));

        // Both the journal and its publisher are on the loaded lists, but the allowlist exempts them.
        assertFalse(s.isPredatory("Oncotarget", "Impact Journals LLC"));
    }
}
