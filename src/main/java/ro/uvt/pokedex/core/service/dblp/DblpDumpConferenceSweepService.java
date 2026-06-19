package ro.uvt.pokedex.core.service.dblp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * H66B Phase 4b — the corpus-matched DBLP dump sweep: the rate-limit-free BULK path. It builds the hidden-conference
 * candidate set ({@link DblpConferenceCandidateDetector}) into in-memory DOI/title indexes, streams the DBLP dump
 * <b>once</b>, and on a match routes it through {@link DblpConferenceResolveService#applyMatch} — the same outputs the
 * per-paper API path produces (scorer-compatible evidence + conf/X forum + forumId). Nothing about the dump is stored;
 * only our matched papers' conference entities are kept.
 *
 * <p>This is the retired streamer's proven match mechanism (XML reader + entity sanitizer recovered verbatim), fixed:
 * an explicit once-per-dump admin sweep — not a per-run cost — that now also mints conference forums. The per-paper
 * API stays for on-demand syncs and papers DBLP added since the dump.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DblpDumpConferenceSweepService {

    private static final Set<String> RECORD_ELEMENTS = Set.of(
            "article", "inproceedings", "proceedings", "book", "incollection", "phdthesis", "mastersthesis", "www");
    private static final long STREAM_PROGRESS_INTERVAL = 250_000L;
    private static final Pattern NAMED_ENTITY_PATTERN = Pattern.compile("&([A-Za-z][A-Za-z0-9]+);");
    private static final Pattern STRAY_AMPERSAND_PATTERN =
            Pattern.compile("&(?!(#\\d+;|#x[0-9A-Fa-f]+;|[A-Za-z][A-Za-z0-9]+;))");
    private static final int SANITIZER_READ_AHEAD = 8192;
    private static final int SANITIZER_MAX_CARRY = 64;
    private static final Map<String, String> XML_ENTITY_REPLACEMENTS = createEntityReplacements();
    private static final List<String> RELAXED_XML_LIMIT_PROPERTIES = List.of(
            "jdk.xml.maxGeneralEntitySizeLimit", "jdk.xml.totalEntitySizeLimit", "jdk.xml.entityExpansionLimit");

    private final DblpConferenceCandidateDetector candidateDetector;
    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final DblpConferenceResolveService resolveService;

    @Value("${dblp.dump.file:}")
    private String dumpFilePath;

    @Value("${dblp.dump.version:}")
    private String dumpVersion;

    /** Stream the configured DBLP dump once and resolve every hidden-conference candidate it matches. */
    public ImportProcessingResult sweep() {
        ImportProcessingResult result = new ImportProcessingResult(20);
        if (dumpFilePath == null || dumpFilePath.isBlank()) {
            log.warn("DBLP dump sweep skipped: dblp.dump.file is not configured");
            return result;
        }
        Path dumpPath = Path.of(dumpFilePath);
        if (!Files.exists(dumpPath)) {
            log.warn("DBLP dump sweep skipped: dump file not found at {}", dumpPath);
            return result;
        }
        try (InputStream fileInput = Files.newInputStream(dumpPath);
             InputStream gzipInput = new GZIPInputStream(fileInput);
             Reader sanitizedReader = new NamedEntitySanitizingReader(new InputStreamReader(gzipInput, StandardCharsets.UTF_8))) {
            XMLStreamReader reader = createXmlInputFactory().createXMLStreamReader(sanitizedReader);
            runSweep(reader, result);
            reader.close();
        } catch (IOException | XMLStreamException ex) {
            throw new IllegalStateException("Failed to stream DBLP dump " + dumpPath + ": " + ex.getMessage(), ex);
        }
        return result;
    }

    /** Core streaming + matching (package-private for testing with a hand-built reader). */
    void runSweep(XMLStreamReader reader, ImportProcessingResult result) throws XMLStreamException {
        List<ScholardexPublicationFact> candidates =
                candidateDetector.detect(publicationFactRepository.findAll());
        Map<String, ScholardexPublicationFact> byDoi = new HashMap<>();
        Map<String, ScholardexPublicationFact> byTitleYear = new HashMap<>();
        for (ScholardexPublicationFact pub : candidates) {
            if (pub.getDoiNormalized() != null) {
                byDoi.putIfAbsent(pub.getDoiNormalized(), pub);
            }
            String tyk = titleYearKey(pub.getTitleNormalized(), parseYear(pub.getCoverDate()));
            if (tyk != null) {
                byTitleYear.putIfAbsent(tyk, pub);
            }
        }
        log.info("DBLP dump sweep: candidates={} byDoi={} byTitleYear={}", candidates.size(), byDoi.size(), byTitleYear.size());
        if (byDoi.isEmpty() && byTitleYear.isEmpty()) {
            return;
        }

        Set<String> resolvedPubIds = new HashSet<>();
        long scanned = 0L;
        while (reader.hasNext()) {
            if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            String localName = reader.getLocalName();
            if (!RECORD_ELEMENTS.contains(localName)) {
                continue;
            }
            scanned++;
            if (scanned % STREAM_PROGRESS_INTERVAL == 0) {
                log.info("DBLP dump sweep progress: scanned={} resolved={}", scanned, resolvedPubIds.size());
            }
            DblpRecord record = readRecord(reader, localName);
            if (DblpClient.conferenceStreamKey(record.dblpKey()) == null) {
                continue; // not a conference record — skip (journals/books)
            }
            String method = "dump-doi";
            ScholardexPublicationFact match = record.doiNormalized() == null ? null : byDoi.get(record.doiNormalized());
            if (match == null) {
                String tyk = titleYearKey(record.titleNormalized(), record.year());
                match = tyk == null ? null : byTitleYear.get(tyk);
                method = "dump-title";
            }
            if (match == null || !resolvedPubIds.add(match.getId())) {
                continue; // no match, or this pub already resolved earlier in the stream
            }
            result.markProcessed();
            if (resolveService.applyMatch(match, record.dblpKey(), record.booktitle(),
                    record.doiNormalized(), record.title(), record.year(), method, dumpVersion)) {
                result.markImported();
            } else {
                result.markSkipped("dblp-dump-not-a-conference pub=" + match.getId());
            }
        }
        log.info("DBLP dump sweep complete: scanned={} resolved={}", scanned, result.getImportedCount());
    }

    private static String titleYearKey(String titleNormalized, Integer year) {
        if (titleNormalized == null || titleNormalized.isBlank() || year == null) {
            return null;
        }
        return titleNormalized + "|" + year;
    }

    // ----- recovered DBLP dump reading machinery (verbatim from the retired streamer, pre-3b396b1) -----

    private record DblpRecord(String dblpKey, String title, String titleNormalized, Integer year,
                             String doiNormalized, String booktitle) {
    }

    private DblpRecord readRecord(XMLStreamReader reader, String recordType) throws XMLStreamException {
        String dblpKey = CanonicalizationSupport.normalizeBlank(reader.getAttributeValue(null, "key"));
        String title = null;
        Integer year = null;
        String doiNormalized = null;
        String booktitle = null;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String localName = reader.getLocalName();
                String value = CanonicalizationSupport.normalizeBlank(readElementText(reader));
                if (value == null) {
                    continue;
                }
                switch (localName) {
                    case "title" -> title = value;
                    case "year" -> year = parseYear(value);
                    case "booktitle" -> booktitle = value;
                    case "doi" -> doiNormalized = ScholardexPublicationCanonicalizationService.normalizeDoi(value);
                    case "ee" -> {
                        if (doiNormalized == null) {
                            doiNormalized = ScholardexPublicationCanonicalizationService.normalizeDoi(value);
                        }
                    }
                    default -> { /* ignore the rest */ }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && recordType.equals(reader.getLocalName())) {
                break;
            }
        }
        return new DblpRecord(dblpKey, title,
                ScholardexPublicationCanonicalizationService.normalizeTitle(title), year, doiNormalized, booktitle);
    }

    private String readElementText(XMLStreamReader reader) throws XMLStreamException {
        StringBuilder text = new StringBuilder();
        int depth = 1;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                text.append(reader.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
        }
        return text.toString();
    }

    private static Integer parseYear(String rawYear) {
        String normalized = CanonicalizationSupport.normalizeBlank(rawYear);
        if (normalized == null) {
            return null;
        }
        if (normalized.length() >= 4) {
            String prefix = normalized.substring(0, 4);
            if (prefix.chars().allMatch(Character::isDigit)) {
                return Integer.parseInt(prefix);
            }
        }
        String digits = normalized.replaceAll("[^0-9]", "");
        return digits.length() >= 4 ? Integer.parseInt(digits.substring(0, 4)) : null;
    }

    private XMLInputFactory createXmlInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        for (String property : RELAXED_XML_LIMIT_PROPERTIES) {
            try {
                factory.setProperty(property, 0);
            } catch (IllegalArgumentException ex) {
                log.debug("DBLP XML parser property unsupported: {}", property);
            }
        }
        return factory;
    }

    static String sanitizeNamedEntities(String input) {
        if (input == null || input.indexOf('&') < 0) {
            return input;
        }
        String sanitized = STRAY_AMPERSAND_PATTERN.matcher(input).replaceAll(" ");
        Matcher matcher = NAMED_ENTITY_PATTERN.matcher(sanitized);
        StringBuffer buffer = new StringBuffer(sanitized.length());
        while (matcher.find()) {
            String replacement = XML_ENTITY_REPLACEMENTS.getOrDefault(matcher.group(1), " ");
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static Map<String, String> createEntityReplacements() {
        Map<String, String> v = new HashMap<>();
        v.put("amp", "&amp;");
        v.put("lt", "&lt;");
        v.put("gt", "&gt;");
        v.put("quot", "&quot;");
        v.put("apos", "&apos;");
        v.put("nbsp", " ");
        v.put("reg", "");
        v.put("copy", "");
        v.put("trade", "");
        for (String[] pair : new String[][]{
                {"uuml", "u"}, {"Uuml", "U"}, {"ouml", "o"}, {"Ouml", "O"}, {"auml", "a"}, {"Auml", "A"},
                {"euml", "e"}, {"Euml", "E"}, {"iuml", "i"}, {"Iuml", "I"}, {"yuml", "y"}, {"Yuml", "Y"},
                {"aring", "a"}, {"Aring", "A"}, {"oslash", "o"}, {"Oslash", "O"}, {"aelig", "ae"}, {"AElig", "AE"},
                {"ccedil", "c"}, {"Ccedil", "C"}, {"ntilde", "n"}, {"Ntilde", "N"}, {"szlig", "ss"},
                {"agrave", "a"}, {"Agrave", "A"}, {"egrave", "e"}, {"Egrave", "E"}, {"igrave", "i"}, {"Igrave", "I"},
                {"ograve", "o"}, {"Ograve", "O"}, {"ugrave", "u"}, {"Ugrave", "U"},
                {"aacute", "a"}, {"Aacute", "A"}, {"eacute", "e"}, {"Eacute", "E"}, {"iacute", "i"}, {"Iacute", "I"},
                {"oacute", "o"}, {"Oacute", "O"}, {"uacute", "u"}, {"Uacute", "U"}, {"yacute", "y"}, {"Yacute", "Y"},
                {"acirc", "a"}, {"Acirc", "A"}, {"ecirc", "e"}, {"Ecirc", "E"}, {"icirc", "i"}, {"Icirc", "I"},
                {"ocirc", "o"}, {"Ocirc", "O"}, {"ucirc", "u"}, {"Ucirc", "U"},
                {"atilde", "a"}, {"Atilde", "A"}, {"otilde", "o"}, {"Otilde", "O"},
                {"eth", "d"}, {"ETH", "D"}, {"thorn", "th"}, {"THORN", "Th"}}) {
            v.put(pair[0], pair[1]);
        }
        return v;
    }

    static final class NamedEntitySanitizingReader extends Reader {
        private final Reader delegate;
        private final char[] readBuffer = new char[SANITIZER_READ_AHEAD];
        private final StringBuilder carry = new StringBuilder();
        private String output = "";
        private int outputIndex = 0;
        private boolean eof;
        private int consecutiveZeroReads;

        NamedEntitySanitizingReader(Reader delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            int written = 0;
            int loopGuard = 0;
            int maxLoops = Math.max(64, len * 8);
            while (written < len) {
                if (loopGuard++ > maxLoops) {
                    throw new IOException("NamedEntitySanitizingReader exceeded read loop guard");
                }
                if (outputIndex < output.length()) {
                    int chunk = Math.min(len - written, output.length() - outputIndex);
                    output.getChars(outputIndex, outputIndex + chunk, cbuf, off + written);
                    outputIndex += chunk;
                    written += chunk;
                    continue;
                }
                if (!fillOutput()) {
                    return written == 0 ? -1 : written;
                }
            }
            return written;
        }

        private boolean fillOutput() throws IOException {
            int loopGuard = 0;
            int maxLoops = Math.max(256, readBuffer.length * 2);
            while (true) {
                if (loopGuard++ > maxLoops) {
                    throw new IOException("NamedEntitySanitizingReader exceeded fill loop guard");
                }
                if (eof && carry.isEmpty()) {
                    return false;
                }
                int read = delegate.read(readBuffer);
                if (read == -1) {
                    eof = true;
                    if (carry.isEmpty()) {
                        return false;
                    }
                    output = sanitizeNamedEntities(carry.toString());
                    carry.setLength(0);
                    outputIndex = 0;
                    return !output.isEmpty();
                }
                if (read == 0) {
                    if (++consecutiveZeroReads >= 2) {
                        return false;
                    }
                    continue;
                }
                consecutiveZeroReads = 0;
                carry.append(readBuffer, 0, read);
                int safeLength = computeSafeLength(carry);
                if (safeLength <= 0) {
                    continue;
                }
                String chunk = carry.substring(0, safeLength);
                carry.delete(0, safeLength);
                output = sanitizeNamedEntities(chunk);
                outputIndex = 0;
                if (!output.isEmpty()) {
                    return true;
                }
            }
        }

        private int computeSafeLength(StringBuilder value) {
            int length = value.length();
            int lastAmpersand = value.lastIndexOf("&");
            if (lastAmpersand < 0) {
                return length;
            }
            if (length - lastAmpersand > SANITIZER_MAX_CARRY) {
                return length;
            }
            for (int i = lastAmpersand; i < length; i++) {
                if (value.charAt(i) == ';') {
                    return length;
                }
            }
            return lastAmpersand;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
