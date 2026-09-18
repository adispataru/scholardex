package ro.uvt.pokedex.core.service.dblp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in pre-flight for a NEW DBLP release: streams the whole dump through the sweep's own reader + XML factory, no
 * database. Run it before shipping a dump to prod — a parser limit that bites at row 89M costs a nine-minute prod run
 * to discover otherwise (2026-09-18).
 *
 * <pre>DBLP_DUMP_IT=~/Downloads/dblp-2026-09-01.xml.gz ./gradlew test --tests '*DblpDumpFullStreamIT*'</pre>
 */
class DblpDumpFullStreamIT {

    @Test
    @EnabledIfEnvironmentVariable(named = "DBLP_DUMP_IT", matches = ".+")
    void theWholeDumpStreamsToItsEnd() throws Exception {
        Path dump = Path.of(System.getenv("DBLP_DUMP_IT").replaceFirst("^~", System.getProperty("user.home")));
        long records = 0;
        try (InputStream file = Files.newInputStream(dump);
             InputStream gzip = new GZIPInputStream(file);
             Reader reader = new DblpDumpConferenceSweepService.EntitySanitizingReader(
                     new InputStreamReader(gzip, StandardCharsets.ISO_8859_1))) {
            XMLStreamReader xml = DblpDumpConferenceSweepService.createXmlInputFactory().createXMLStreamReader(reader);
            int depth = 0;
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (++depth == 2) records++;
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                }
            }
        }
        System.out.println("DBLP dump streamed to the end: records=" + records);
        assertTrue(records > 1_000_000, "a real dump has millions of records, got " + records);
    }
}
