package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationDblpEvidenceRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
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
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class DblpPublicationEnrichmentService {

    private static final String LN_PREFIX = "Lecture Notes in ";
    private static final String LN_ON_PREFIX = "Lecture Notes on ";
    private static final Set<String> RECORD_ELEMENTS = Set.of(
            "article", "inproceedings", "proceedings", "book", "incollection", "phdthesis", "mastersthesis", "www"
    );
    private static final long STREAM_PROGRESS_INTERVAL = 250_000L;
    private static final Set<String> LN_ELIGIBLE_SUBTYPES = Set.of("ch", "cp");
    private static final Pattern NAMED_ENTITY_PATTERN = Pattern.compile("&([A-Za-z][A-Za-z0-9]+);");
    private static final Pattern STRAY_AMPERSAND_PATTERN = Pattern.compile("&(?!(#\\d+;|#x[0-9A-Fa-f]+;|[A-Za-z][A-Za-z0-9]+;))");
    private static final int SANITIZER_READ_AHEAD = 8192;
    private static final int SANITIZER_MAX_CARRY = 64;
    private static final Map<String, String> XML_ENTITY_REPLACEMENTS = createEntityReplacements();
    private static final List<String> RELAXED_XML_LIMIT_PROPERTIES = List.of(
            "jdk.xml.maxGeneralEntitySizeLimit",
            "jdk.xml.totalEntitySizeLimit",
            "jdk.xml.entityExpansionLimit"
    );

    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final ScholardexPublicationDblpEvidenceRepository evidenceRepository;

    @Value("${general.init.dblp.file:}")
    private String dblpFilePath;

    @Value("${general.init.dblp.version:}")
    private String dblpVersion;

    public DblpEnrichmentRunSummary runConfiguredEnrichment() {
        String configuredPath = CanonicalizationSupport.normalizeBlank(dblpFilePath);
        if (configuredPath == null) {
            throw new IllegalStateException("DBLP enrichment dump path is not configured. Set general.init.dblp.file.");
        }
        Path path = Path.of(configuredPath);
        if (!Files.exists(path) || !Files.isReadable(path)) {
            throw new IllegalStateException("DBLP enrichment dump path is missing or unreadable: " + configuredPath);
        }
        String version = Optional.ofNullable(CanonicalizationSupport.normalizeBlank(dblpVersion))
                .orElse(path.getFileName().toString());
        return runEnrichment(path, version);
    }

    DblpEnrichmentRunSummary runEnrichment(Path dumpPath, String dumpVersion) {
        log.info("DBLP enrichment started: dumpPath={} dumpVersion={}", dumpPath, dumpVersion);
        List<CandidatePublication> candidates = loadCandidates();
        CandidateIndex candidateIndex = indexCandidates(candidates);
        log.info("DBLP candidate index built: candidates={} doiKeys={} titleYearKeys={}",
                candidateIndex.candidates().size(),
                candidateIndex.byDoi().size(),
                candidateIndex.byTitleYear().size());
        if (candidateIndex.candidates().isEmpty()) {
            log.warn("DBLP enrichment found zero candidates. Check subtype/forum filters and source forum availability.");
            return new DblpEnrichmentRunSummary(
                    dumpPath.toString(),
                    dumpVersion,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0
            );
        }

        Map<String, AcceptedMatch> acceptedMatches = new LinkedHashMap<>();
        Set<String> conflictedPublicationIds = new LinkedHashSet<>();
        long recordsScanned = 0L;

        XMLInputFactory xmlInputFactory = createXmlInputFactory();

        try (InputStream fileInput = Files.newInputStream(dumpPath);
             InputStream gzipInput = new GZIPInputStream(fileInput);
             Reader sanitizedReader = new NamedEntitySanitizingReader(new InputStreamReader(gzipInput, StandardCharsets.UTF_8))) {
            XMLStreamReader reader = xmlInputFactory.createXMLStreamReader(sanitizedReader);
            while (reader.hasNext()) {
                int event = reader.next();
                if (event != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                String localName = reader.getLocalName();
                if (!RECORD_ELEMENTS.contains(localName)) {
                    continue;
                }
                recordsScanned++;
                if (recordsScanned % STREAM_PROGRESS_INTERVAL == 0) {
                    log.info("DBLP stream progress: scanned={} matchedSoFar={} conflictsSoFar={}",
                            recordsScanned,
                            acceptedMatches.size(),
                            conflictedPublicationIds.size());
                }
                DblpRecord record = readRecord(reader, localName);
                processRecord(record, candidateIndex, acceptedMatches, conflictedPublicationIds);
            }
            reader.close();
        } catch (IOException | XMLStreamException ex) {
            throw new IllegalStateException("Failed to stream DBLP dump " + dumpPath + ": " + ex.getMessage(), ex);
        }

        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        for (AcceptedMatch match : acceptedMatches.values()) {
            if (conflictedPublicationIds.contains(match.publicationId())) {
                continue;
            }
            PersistenceOutcome outcome = persistMatch(match, dumpVersion);
            if (outcome == PersistenceOutcome.INSERTED) {
                inserted++;
            } else if (outcome == PersistenceOutcome.UPDATED) {
                updated++;
            } else {
                skipped++;
            }
        }

        int conflicts = conflictedPublicationIds.size();
        int unmatched = Math.max(0, candidateIndex.candidates().size() - acceptedMatches.size() - conflicts);
        log.info("DBLP enrichment finished: scanned={} inserted={} updated={} skipped={} unmatched={} conflicts={}",
                recordsScanned, inserted, updated, skipped, unmatched, conflicts);
        return new DblpEnrichmentRunSummary(
                dumpPath.toString(),
                dumpVersion,
                candidateIndex.candidates().size(),
                recordsScanned,
                inserted,
                updated,
                skipped,
                unmatched,
                conflicts,
                0
        );
    }

    private List<CandidatePublication> loadCandidates() {
        List<ScholardexPublicationFact> publications = publicationFactRepository.findAll();
        Set<String> forumIds = new LinkedHashSet<>();
        for (ScholardexPublicationFact publication : publications) {
            String forumId = CanonicalizationSupport.normalizeBlank(publication.getForumId());
            if (forumId != null) {
                forumIds.add(forumId);
            }
        }

        Map<String, Forum> forumById = new HashMap<>();
        for (Forum forum : scholardexProjectionReadService.findForumsByIdIn(forumIds)) {
            forumById.put(forum.getId(), forum);
        }

        List<CandidatePublication> candidates = new ArrayList<>();
        int eligibleSubtypePublications = 0;
        int missingForum = 0;
        int nonLectureNotes = 0;
        int missingYear = 0;
        for (ScholardexPublicationFact publication : publications) {
            String subtype = CanonicalizationSupport.normalizeBlank(publication.getSubtype());
            if (subtype == null || !LN_ELIGIBLE_SUBTYPES.contains(subtype.toLowerCase(Locale.ROOT))) {
                continue;
            }
            eligibleSubtypePublications++;
            String forumId = CanonicalizationSupport.normalizeBlank(publication.getForumId());
            Forum forum = forumId == null ? null : forumById.get(forumId);
            String forumName = forum == null ? null : forum.getPublicationName();
            if (forumName == null) {
                missingForum++;
                continue;
            }
            if (!isLectureNotesForum(forumName)) {
                nonLectureNotes++;
                continue;
            }
            Integer publicationYear = parseYear(publication.getCoverDate());
            if (publicationYear == null) {
                missingYear++;
            }
            candidates.add(new CandidatePublication(
                    publication.getId(),
                    publication.getDoiNormalized(),
                    CanonicalizationSupport.normalizeBlank(publication.getTitleNormalized()),
                    publicationYear
            ));
        }
        log.info("DBLP candidate load: totalPublications={} eligibleSubtypePublications={} forumsLoaded={} missingForum={} nonLectureNotes={} candidates={} missingYear={}",
                publications.size(),
                eligibleSubtypePublications,
                forumById.size(),
                missingForum,
                nonLectureNotes,
                candidates.size(),
                missingYear);
        return candidates;
    }

    private CandidateIndex indexCandidates(List<CandidatePublication> candidates) {
        Map<String, List<CandidatePublication>> byDoi = new LinkedHashMap<>();
        Map<String, List<CandidatePublication>> byTitleYear = new LinkedHashMap<>();
        for (CandidatePublication candidate : candidates) {
            if (candidate.doiNormalized() != null) {
                byDoi.computeIfAbsent(candidate.doiNormalized(), ignored -> new ArrayList<>()).add(candidate);
            }
            String titleYearKey = candidate.titleYearKey();
            if (titleYearKey != null) {
                byTitleYear.computeIfAbsent(titleYearKey, ignored -> new ArrayList<>()).add(candidate);
            }
        }
        return new CandidateIndex(candidates, byDoi, byTitleYear);
    }

    private void processRecord(
            DblpRecord record,
            CandidateIndex candidateIndex,
            Map<String, AcceptedMatch> acceptedMatches,
            Set<String> conflictedPublicationIds
    ) {
        MatchResolution resolution = resolveRecord(record, candidateIndex);
        if (resolution.candidates().isEmpty()) {
            return;
        }
        if (resolution.candidates().size() > 1) {
            resolution.candidates().forEach(candidate -> {
                conflictedPublicationIds.add(candidate.publicationId());
                acceptedMatches.remove(candidate.publicationId());
            });
            return;
        }
        CandidatePublication candidate = resolution.candidates().getFirst();
        if (conflictedPublicationIds.contains(candidate.publicationId())) {
            return;
        }

        AcceptedMatch current = acceptedMatches.get(candidate.publicationId());
        AcceptedMatch incoming = new AcceptedMatch(candidate.publicationId(), resolution.matchMethod(), record);
        if (current == null) {
            acceptedMatches.put(candidate.publicationId(), incoming);
            return;
        }
        if (current.record().dblpKey().equals(record.dblpKey())) {
            if (current.matchMethod() == MatchMethod.TITLE_YEAR_EXACT && resolution.matchMethod() == MatchMethod.DOI_EXACT) {
                acceptedMatches.put(candidate.publicationId(), incoming);
            }
            return;
        }
        conflictedPublicationIds.add(candidate.publicationId());
        acceptedMatches.remove(candidate.publicationId());
    }

    private MatchResolution resolveRecord(DblpRecord record, CandidateIndex candidateIndex) {
        if (record.doiNormalized() != null) {
            List<CandidatePublication> doiCandidates = candidateIndex.byDoi().get(record.doiNormalized());
            if (doiCandidates != null && !doiCandidates.isEmpty()) {
                return new MatchResolution(doiCandidates, MatchMethod.DOI_EXACT);
            }
        }
        String titleYearKey = record.titleYearKey();
        if (titleYearKey == null) {
            return MatchResolution.unmatched();
        }
        List<CandidatePublication> titleCandidates = candidateIndex.byTitleYear().get(titleYearKey);
        if (titleCandidates == null || titleCandidates.isEmpty()) {
            return MatchResolution.unmatched();
        }
        return new MatchResolution(titleCandidates, MatchMethod.TITLE_YEAR_EXACT);
    }

    private PersistenceOutcome persistMatch(AcceptedMatch match, String dumpVersion) {
        Instant now = Instant.now();
        ScholardexPublicationDblpEvidence incoming = toEvidence(match, dumpVersion, now);
        Optional<ScholardexPublicationDblpEvidence> existing = evidenceRepository.findByPublicationId(match.publicationId());
        if (existing.isEmpty()) {
            incoming.setCreatedAt(now);
            incoming.setUpdatedAt(now);
            evidenceRepository.save(incoming);
            return PersistenceOutcome.INSERTED;
        }

        ScholardexPublicationDblpEvidence stored = existing.get();
        if (samePayload(stored, incoming)) {
            return PersistenceOutcome.SKIPPED;
        }

        incoming.setId(stored.getId());
        incoming.setCreatedAt(stored.getCreatedAt() == null ? now : stored.getCreatedAt());
        incoming.setUpdatedAt(now);
        evidenceRepository.save(incoming);
        return PersistenceOutcome.UPDATED;
    }

    private ScholardexPublicationDblpEvidence toEvidence(AcceptedMatch match, String dumpVersion, Instant now) {
        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId(match.publicationId());
        evidence.setDblpKey(match.record().dblpKey());
        evidence.setDumpVersion(dumpVersion);
        evidence.setMatchMethod(match.matchMethod().name());
        evidence.setDoi(match.record().doiNormalized());
        evidence.setTitle(match.record().title());
        evidence.setYear(match.record().year());
        evidence.setBooktitle(match.record().booktitle());
        evidence.setSeries(match.record().series());
        evidence.setConferenceName(match.record().booktitle());
        evidence.setSourceUrl(match.record().sourceUrl());
        evidence.setEe(match.record().ee());
        evidence.setUpdatedAt(now);
        return evidence;
    }

    private boolean samePayload(ScholardexPublicationDblpEvidence stored, ScholardexPublicationDblpEvidence incoming) {
        return eq(stored.getPublicationId(), incoming.getPublicationId())
                && eq(stored.getDblpKey(), incoming.getDblpKey())
                && eq(stored.getDumpVersion(), incoming.getDumpVersion())
                && eq(stored.getMatchMethod(), incoming.getMatchMethod())
                && eq(stored.getDoi(), incoming.getDoi())
                && eq(stored.getTitle(), incoming.getTitle())
                && eq(stored.getYear(), incoming.getYear())
                && eq(stored.getBooktitle(), incoming.getBooktitle())
                && eq(stored.getSeries(), incoming.getSeries())
                && eq(stored.getConferenceName(), incoming.getConferenceName())
                && eq(stored.getSourceUrl(), incoming.getSourceUrl())
                && eq(stored.getEe(), incoming.getEe());
    }

    private boolean eq(Object left, Object right) {
        return java.util.Objects.equals(left, right);
    }

    private DblpRecord readRecord(XMLStreamReader reader, String recordType) throws XMLStreamException {
        String dblpKey = CanonicalizationSupport.normalizeBlank(reader.getAttributeValue(null, "key"));
        String title = null;
        Integer year = null;
        String doiNormalized = null;
        String booktitle = null;
        String series = null;
        String ee = null;
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
                    case "series" -> series = value;
                    case "doi" -> doiNormalized = ScholardexPublicationCanonicalizationService.normalizeDoi(value);
                    case "ee" -> {
                        if (ee == null) {
                            ee = value;
                        }
                        if (doiNormalized == null) {
                            doiNormalized = ScholardexPublicationCanonicalizationService.normalizeDoi(value);
                        }
                    }
                    default -> {
                        // ignore the rest in v1
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && recordType.equals(reader.getLocalName())) {
                break;
            }
        }
        return new DblpRecord(
                dblpKey,
                title,
                ScholardexPublicationCanonicalizationService.normalizeTitle(title),
                year,
                doiNormalized,
                booktitle,
                series,
                buildSourceUrl(dblpKey),
                ee
        );
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

    private boolean isLectureNotesForum(String forumName) {
        return forumName != null && (forumName.contains(LN_PREFIX) || forumName.contains(LN_ON_PREFIX));
    }

    private Integer parseYear(String rawYear) {
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
        if (digits.length() >= 4) {
            return Integer.parseInt(digits.substring(0, 4));
        }
        return null;
    }

    private String buildSourceUrl(String dblpKey) {
        if (dblpKey == null) {
            return null;
        }
        return "https://dblp.org/rec/" + dblpKey + ".html";
    }

    enum MatchMethod {
        DOI_EXACT,
        TITLE_YEAR_EXACT
    }

    enum PersistenceOutcome {
        INSERTED,
        UPDATED,
        SKIPPED
    }

    record CandidatePublication(String publicationId, String doiNormalized, String titleNormalized, Integer year) {
        String titleYearKey() {
            if (titleNormalized == null || year == null) {
                return null;
            }
            return titleNormalized + "|" + year;
        }
    }

    record CandidateIndex(
            List<CandidatePublication> candidates,
            Map<String, List<CandidatePublication>> byDoi,
            Map<String, List<CandidatePublication>> byTitleYear
    ) {
    }

    record DblpRecord(
            String dblpKey,
            String title,
            String titleNormalized,
            Integer year,
            String doiNormalized,
            String booktitle,
            String series,
            String sourceUrl,
            String ee
    ) {
        String titleYearKey() {
            if (titleNormalized == null || year == null) {
                return null;
            }
            return titleNormalized + "|" + year;
        }
    }

    record MatchResolution(List<CandidatePublication> candidates, MatchMethod matchMethod) {
        static MatchResolution unmatched() {
            return new MatchResolution(List.of(), null);
        }
    }

    record AcceptedMatch(String publicationId, MatchMethod matchMethod, DblpRecord record) {
    }

    public record DblpEnrichmentRunSummary(
            String dumpPath,
            String dumpVersion,
            int candidatesConsidered,
            long recordsScanned,
            int matched,
            int updated,
            int skipped,
            int unmatched,
            int conflicts,
            int errors
    ) {
        public String formatForMessage() {
            return "dblp enrichment completed from " + dumpPath
                    + " (version=" + dumpVersion + ")"
                    + ". candidates=" + candidatesConsidered
                    + ", scanned=" + recordsScanned
                    + ", matched=" + matched
                    + ", updated=" + updated
                    + ", skipped=" + skipped
                    + ", unmatched=" + unmatched
                    + ", conflicts=" + conflicts
                    + ", errors=" + errors;
        }
    }

    private static Map<String, String> createEntityReplacements() {
        Map<String, String> values = new HashMap<>();
        values.put("amp", "&amp;");
        values.put("lt", "&lt;");
        values.put("gt", "&gt;");
        values.put("quot", "&quot;");
        values.put("apos", "&apos;");
        values.put("nbsp", " ");
        values.put("reg", "");
        values.put("copy", "");
        values.put("trade", "");
        values.put("uuml", "u");
        values.put("Uuml", "U");
        values.put("ouml", "o");
        values.put("Ouml", "O");
        values.put("auml", "a");
        values.put("Auml", "A");
        values.put("euml", "e");
        values.put("Euml", "E");
        values.put("iuml", "i");
        values.put("Iuml", "I");
        values.put("yuml", "y");
        values.put("Yuml", "Y");
        values.put("aring", "a");
        values.put("Aring", "A");
        values.put("oslash", "o");
        values.put("Oslash", "O");
        values.put("aelig", "ae");
        values.put("AElig", "AE");
        values.put("ccedil", "c");
        values.put("Ccedil", "C");
        values.put("ntilde", "n");
        values.put("Ntilde", "N");
        values.put("szlig", "ss");
        values.put("agrave", "a");
        values.put("Agrave", "A");
        values.put("egrave", "e");
        values.put("Egrave", "E");
        values.put("igrave", "i");
        values.put("Igrave", "I");
        values.put("ograve", "o");
        values.put("Ograve", "O");
        values.put("ugrave", "u");
        values.put("Ugrave", "U");
        values.put("aacute", "a");
        values.put("Aacute", "A");
        values.put("eacute", "e");
        values.put("Eacute", "E");
        values.put("iacute", "i");
        values.put("Iacute", "I");
        values.put("oacute", "o");
        values.put("Oacute", "O");
        values.put("uacute", "u");
        values.put("Uacute", "U");
        values.put("yacute", "y");
        values.put("Yacute", "Y");
        values.put("acirc", "a");
        values.put("Acirc", "A");
        values.put("ecirc", "e");
        values.put("Ecirc", "E");
        values.put("icirc", "i");
        values.put("Icirc", "I");
        values.put("ocirc", "o");
        values.put("Ocirc", "O");
        values.put("ucirc", "u");
        values.put("Ucirc", "U");
        values.put("atilde", "a");
        values.put("Atilde", "A");
        values.put("otilde", "o");
        values.put("Otilde", "O");
        values.put("eth", "d");
        values.put("ETH", "D");
        values.put("thorn", "th");
        values.put("THORN", "Th");
        return values;
    }

    static String sanitizeNamedEntities(String input) {
        if (input == null || input.indexOf('&') < 0) {
            return input;
        }
        String sanitized = STRAY_AMPERSAND_PATTERN.matcher(input).replaceAll(" ");
        Matcher matcher = NAMED_ENTITY_PATTERN.matcher(sanitized);
        StringBuffer buffer = new StringBuffer(sanitized.length());
        while (matcher.find()) {
            String entity = matcher.group(1);
            String replacement = XML_ENTITY_REPLACEMENTS.get(entity);
            if (replacement == null) {
                replacement = " ";
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    static final class NamedEntitySanitizingReader extends Reader {
        private final Reader delegate;
        private final char[] readBuffer = new char[SANITIZER_READ_AHEAD];
        private final StringBuilder carry = new StringBuilder();
        private String output = "";
        private int outputIndex = 0;
        private boolean eof;

        NamedEntitySanitizingReader(Reader delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            int written = 0;
            while (written < len) {
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

            carry.append(readBuffer, 0, read);
            int safeLength = computeSafeLength(carry);
            if (safeLength <= 0) {
                return fillOutput();
            }
            String chunk = carry.substring(0, safeLength);
            carry.delete(0, safeLength);
            output = sanitizeNamedEntities(chunk);
            outputIndex = 0;
            return !output.isEmpty() || fillOutput();
        }

        private int computeSafeLength(StringBuilder value) {
            int length = value.length();
            int lastAmpersand = value.lastIndexOf("&");
            if (lastAmpersand < 0) {
                return length;
            }
            int tailLength = length - lastAmpersand;
            if (tailLength > SANITIZER_MAX_CARRY) {
                return length;
            }
            boolean hasSemicolon = false;
            for (int i = lastAmpersand; i < length; i++) {
                if (value.charAt(i) == ';') {
                    hasSemicolon = true;
                    break;
                }
            }
            return hasSemicolon ? length : lastAmpersand;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private XMLInputFactory createXmlInputFactory() {
        XMLInputFactory xmlInputFactory = XMLInputFactory.newFactory();
        xmlInputFactory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        xmlInputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xmlInputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        for (String property : RELAXED_XML_LIMIT_PROPERTIES) {
            try {
                xmlInputFactory.setProperty(property, 0);
                log.debug("DBLP XML parser property set: {}=0", property);
            } catch (IllegalArgumentException ex) {
                log.debug("DBLP XML parser property unsupported: {}", property);
            }
        }
        return xmlInputFactory;
    }
}
