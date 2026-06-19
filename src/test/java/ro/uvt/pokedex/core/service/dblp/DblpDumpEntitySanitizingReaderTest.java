package ro.uvt.pokedex.core.service.dblp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;

/** The line-oriented entity sanitizer that replaced the hand-rolled char-buffer state machine. */
class DblpDumpEntitySanitizingReaderTest {

    @Test
    void replacesKnownNamedEntitiesWithSafeEquivalents() throws IOException {
        assertThat(sanitize("Schr&ouml;dinger and M&uuml;ller")).isEqualTo("Schrodinger and Muller");
    }

    @Test
    void preservesNumericCharacterReferencesForTheParser() throws IOException {
        // &#252; is valid XML the parser resolves itself — the sanitizer must NOT touch it.
        assertThat(sanitize("caf&#233; &#xe9;")).isEqualTo("caf&#233; &#xe9;");
    }

    @Test
    void replacesStrayAmpersandsThatAreNotEntities() throws IOException {
        assertThat(sanitize("Theory & Practice").replace(" ", "")).doesNotContain("&");
    }

    @Test
    void tolerantlyReplacesUnknownEntitiesRatherThanFailing() throws IOException {
        // an entity not in the map degrades to a space — never an exception on a multi-GB stream
        assertThat(sanitize("a&notARealEntity;b")).doesNotContain("&");
    }

    @Test
    void mergesAnEntitySplitAcrossAChunkBoundary() throws IOException {
        // pad so "&uuml;" straddles the reader's internal 8192-char chunk boundary; carry must stitch it back
        String pad = "x".repeat(8190);
        assertThat(sanitize(pad + "&uuml;END")).isEqualTo(pad + "uEND");
    }

    @Test
    void passesPlainMarkupThroughUnchanged() throws IOException {
        String xml = "<title>Online resource coalition</title>";
        assertThat(sanitize(xml)).isEqualTo(xml);
    }

    /** Reads the whole sanitized stream, stripping the single trailing newline the line-joiner appends. */
    private String sanitize(String input) throws IOException {
        try (var reader = new DblpDumpConferenceSweepService.EntitySanitizingReader(new StringReader(input))) {
            StringBuilder out = new StringBuilder();
            char[] buf = new char[7]; // deliberately tiny to exercise the read-loop / boundary handling
            int n;
            while ((n = reader.read(buf, 0, buf.length)) != -1) {
                out.append(buf, 0, n);
            }
            if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
                out.setLength(out.length() - 1);
            }
            return out.toString();
        }
    }
}
