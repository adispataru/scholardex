package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.registry.RegistryChange;
import ro.uvt.pokedex.core.model.registry.RegistryStatus;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;
import ro.uvt.pokedex.core.service.reporting.ArtisticEventRankRegistrar;
import ro.uvt.pokedex.core.service.reporting.ArtisticEventRankSupport;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * H142 slice 3 — fills the experts' queue from an institutional CNFIS table of artistic creation (Anexa 6.1): only the
 * column that identifies the event is read — nothing about people — and each cell is cut down to the event's name
 * (dates, editions, links and bare places dropped). Names the registry knows (ranked, rejected or already waiting)
 * are left alone; the others become proposals of the chosen domain, for its experts to rank.
 */
@Service
@RequiredArgsConstructor
public class ArtisticEventSeedService {

    private static final Logger log = LoggerFactory.getLogger(ArtisticEventSeedService.class);

    private static final Pattern URL = Pattern.compile("(?i)(https?://|www\\.)\\S+");
    /** A word: letters and digits, with inner hyphens and dots kept ("Enescu-Bartók", "Tg.") — the dot only before a letter. */
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:[-'’.][\\p{L}\\p{N}]+)*");
    /** Between two words, what ends a name: a list separator, a dash, a colon or an opening parenthesis. */
    private static final Pattern SEPARATOR = Pattern.compile("[,;|(\\[:]|[-–—]");
    /** A sentence end; not after an abbreviation of up to three letters ("Tg. Mureș", "Dir. …"). */
    private static final Pattern SENTENCE_END = Pattern.compile("\\.\\s");
    /** Words after which no name goes on: editions, the people and the circumstances of a performance. */
    private static final Set<String> STOP_WORDS = Set.of("editia", "editie", "ed", "edition", "online", "prestatie", "organizat",
            "organizata", "organizatori", "dirijor", "dir", "solist", "alaturi", "impreuna", "sustinut", "sustinuta", "participare",
            "jud", "judetul", "ora");
    /** Two words that start a circumstance rather than the name ("… în cadrul", "… cu concertul"). */
    private static final Set<String> STOP_PAIRS = Set.of("in cadrul", "din cadrul", "cu concertul", "cu lucrarea", "in calitate",
            "cu studentii", "cu ansamblul");
    /** Where the name of an event that ends in its kind ("HOT AIR FESTIVAL") starts: after one of these words. */
    private static final Set<String> LEFT_BOUNDS = Set.of("la", "cadrul", "cu", "at", "in", "of", "from", "si", "and", "festivalului",
            "festivalul");
    private static final Set<String> CONNECTORS = Set.of("in", "la", "de", "din", "cu", "a", "al", "ale", "si", "pe", "the", "of",
            "and", "at", "for", "pentru", "un", "o", "und", "der", "die", "das");
    /** Festivals and contests, named after their kind ("Festivalul …", "Concursul …"). */
    private static final Set<String> FESTIVAL_HEADS = Set.of("festivalul", "festivalului", "festivalurile", "concursul",
            "concursului", "competitia", "competitiei", "festivalul-concurs", "festivalului-concurs");
    /** Recurring series, named after their kind. */
    private static final Set<String> SERIES_HEADS = Set.of("stagiunea", "stagiunii", "stagiune", "gala", "galei", "bienala",
            "bienalei", "turneul", "turneului", "turneu", "zilele", "serile", "saptamana", "saptamanii");
    /** Host institutions, named after their kind — only when written with a capital ("Opera Română", not "opera La Traviata"). */
    private static final Set<String> HOST_HEADS = Set.of("filarmonica", "filarmonicii", "opera", "operei", "teatrul",
            "teatrului", "ateneul", "ateneului", "orchestra", "orchestrei", "musikverein", "konzerthaus");
    private static final Map<String, String> NOMINATIVE = Map.ofEntries(Map.entry("festivalului", "Festivalul"),
            Map.entry("festivalului-concurs", "Festivalul-concurs"), Map.entry("concursului", "Concursul"),
            Map.entry("competitiei", "Competiția"), Map.entry("stagiunii", "Stagiunea"), Map.entry("galei", "Gala"),
            Map.entry("bienalei", "Bienala"), Map.entry("turneului", "Turneul"), Map.entry("saptamanii", "Săptămâna"),
            Map.entry("filarmonicii", "Filarmonica"), Map.entry("operei", "Opera"), Map.entry("teatrului", "Teatrul"),
            Map.entry("ateneului", "Ateneul"), Map.entry("orchestrei", "Orchestra"));
    private static final int MAX_WORDS = 14;
    private static final int MIN_LENGTH = 6;

    private final ArtisticEventRepository eventRepository;
    private final ArtisticEventRankRegistrar registrar;

    public record SeedReport(int cells, int created, int known, int skipped, List<String> createdNames) {
    }

    public SeedReport seedFromAnexa61(InputStream xlsx, String fileName, String domain, String adminEmail) throws IOException {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain");
        }
        List<String> cells = eventCells(xlsx);
        Set<String> known = new HashSet<>();
        for (ArtisticEvent e : eventRepository.findAll()) {
            known.addAll(RegistryReviewService.keysOf(e));
        }
        Map<String, String> fresh = new LinkedHashMap<>();
        int skipped = 0, knownCount = 0;
        for (String cell : cells) {
            Optional<String> name = eventName(cell);
            if (name.isEmpty()) {
                skipped++;
                continue;
            }
            String key = ArtisticEventRankSupport.normalize(name.get());
            if (known.contains(key) || ArtisticEventRankSupport.rankOf(name.get()).isPresent()) {
                knownCount++;
                continue;
            }
            fresh.putIfAbsent(key, name.get());
        }
        Instant now = Instant.now();
        String source = "Anexa 6.1: " + (fileName == null || fileName.isBlank() ? "tabel instituțional" : fileName.trim());
        for (String name : fresh.values()) {
            ArtisticEvent proposal = new ArtisticEvent();
            proposal.setName(name);
            proposal.setDomainId(domain.trim());
            proposal.setStatus(RegistryStatus.PROPOSED);
            proposal.setSource(source);
            proposal.setProposedAt(now);
            RegistryChange change = new RegistryChange();
            change.setAt(now);
            change.setBy(adminEmail);
            change.setAction("PROPOSED");
            change.setNote(source);
            proposal.getHistory().add(change);
            eventRepository.save(proposal);
        }
        if (!fresh.isEmpty()) {
            registrar.refresh();
        }
        log.info("Artistic event proposals from {} ({}): {} cells, {} new, {} known, {} not a name",
                source, domain, cells.size(), fresh.size(), knownCount, skipped);
        return new SeedReport(cells.size(), fresh.size(), knownCount, skipped, List.copyOf(fresh.values()));
    }

    /** The cells under the header that names the event ("Date de identificare ale evenimentului…"). */
    static List<String> eventCells(InputStream xlsx) throws IOException {
        List<String> out = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(xlsx)) {
            for (Sheet sheet : workbook) {
                int headerRow = -1, column = -1;
                for (int r = sheet.getFirstRowNum(); r <= Math.min(sheet.getLastRowNum(), 20) && column < 0; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    for (Cell cell : row) {
                        String text = formatter.formatCellValue(cell).toLowerCase(Locale.ROOT);
                        if (text.contains("identificare") && text.contains("evenimentului")) {
                            headerRow = r;
                            column = cell.getColumnIndex();
                            break;
                        }
                    }
                }
                if (column < 0) continue;
                for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    String text = formatter.formatCellValue(row.getCell(column)).trim();
                    if (!text.isEmpty()) out.add(text);
                }
            }
        }
        return out;
    }

    /**
     * The name of the event a cell identifies, or empty when it names none. The cell is read for the words that name an
     * event — a festival or a contest first, then a series (a season, a gala, a tour), then a host institution written
     * with a capital — and the name runs from that word to the first separator, date or edition ("Festivalul Internațional
     * Meridian, ediția a XIX-a, 3-10 nov. 2024, …"); a name that ends in its kind ("HOT AIR FESTIVAL", "Goppisberg
     * Musikfestival und Akademie") is the stretch around it. A name that holds nothing but its kind and generic words
     * ("Festivalul Internațional") names no event.
     */
    static Optional<String> eventName(String cell) {
        if (cell == null) return Optional.empty();
        Anchor best = null;
        for (String rawLine : Normalizer.normalize(cell, Normalizer.Form.NFC).split("\\R")) {
            String line = URL.matcher(rawLine).replaceAll(" ").replace(",,", "„");
            for (Anchor anchor : anchors(line)) {
                if (best == null || anchor.rank() < best.rank()) {
                    best = anchor;
                }
            }
        }
        return best == null ? Optional.empty() : best.name();
    }

    private record Word(String text, String key, int start, int end) {
        boolean capitalized() {
            return Character.isUpperCase(text.codePointAt(0)) || Character.isDigit(text.codePointAt(0));
        }
    }

    /** A word that names an event's kind; rank 0 for festivals and contests, 1 for series, 2 for host institutions. */
    private record Anchor(int rank, Optional<String> name) {
    }

    private static List<Anchor> anchors(String line) {
        List<Word> words = new ArrayList<>();
        var m = WORD.matcher(line);
        while (m.find()) {
            words.add(new Word(m.group(), ArtisticEventRankSupport.normalize(m.group()).replace(' ', '-'), m.start(), m.end()));
        }
        boolean[] separatedBefore = new boolean[words.size()];
        for (int i = 0; i < words.size(); i++) {
            if (i == 0) {
                separatedBefore[i] = true;
                continue;
            }
            String gap = line.substring(words.get(i - 1).end(), words.get(i).start());
            separatedBefore[i] = SEPARATOR.matcher(gap).find()
                    || (SENTENCE_END.matcher(gap + " ").find() && words.get(i - 1).text().length() > 3);
        }
        List<Anchor> out = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            String k = w.key();
            boolean firstOfPart = separatedBefore[i] || (i > 0 && LEFT_BOUNDS.contains(words.get(i - 1).key()));
            if (FESTIVAL_HEADS.contains(k) || ((k.equals("festival") || k.equals("concurs")) && firstOfPart)) {
                out.add(new Anchor(0, headed(line, words, separatedBefore, i, true)));
            } else if (k.contains("festival") || k.contains("wettbewerb") || k.equals("competition") || k.equals("contest")
                    || k.equals("festspiele")) {
                out.add(new Anchor(0, trailing(line, words, separatedBefore, i)));
            } else if (SERIES_HEADS.contains(k)) {
                out.add(new Anchor(1, headed(line, words, separatedBefore, i, false)));
            } else if (HOST_HEADS.contains(k) && Character.isUpperCase(w.text().codePointAt(0))) {
                out.add(new Anchor(2, headed(line, words, separatedBefore, i, false)));
            }
        }
        // the first usable anchor of the best rank; one that yields no name gives way to the next
        return out.stream().filter(a -> a.name().isPresent()).toList();
    }

    /**
     * From the anchor to the end of its stretch; over one dash when the stretch held only generic words ("Festivalul -
     * Concurs Internațional Remus Georgescu").
     */
    private static Optional<String> headed(String line, List<Word> words, boolean[] separatedBefore, int anchor,
                                           boolean festival) {
        int end = stretchEnd(words, separatedBefore, anchor);
        Optional<String> name = name(line, words, anchor, end, festival);
        if (name.isEmpty() && end + 1 < words.size() && !stops(words, end + 1)
                && DASH.matcher(line.substring(words.get(end).end(), words.get(end + 1).start())).matches()) {
            name = name(line, words, anchor, stretchEnd(words, separatedBefore, end + 1), festival);
        }
        return name;
    }

    private static final Pattern DASH = Pattern.compile("\\s*[-–—]+\\s*");

    /** The stretch around an anchor that ends a name: back to a separator or a connecting word, on to the end. */
    private static Optional<String> trailing(String line, List<Word> words, boolean[] separatedBefore, int anchor) {
        int start = anchor;
        while (start > 0 && !separatedBefore[start] && !LEFT_BOUNDS.contains(words.get(start - 1).key())
                && !stops(words, start - 1)) {
            start--;
        }
        return name(line, words, start, stretchEnd(words, separatedBefore, anchor), false);
    }

    /** The last word of the stretch that starts at {@code from}: before a separator, a number or a stop word. */
    private static int stretchEnd(List<Word> words, boolean[] separatedBefore, int from) {
        int end = from;
        while (end + 1 < words.size() && !separatedBefore[end + 1] && !stops(words, end + 1)) {
            end++;
        }
        return end;
    }

    private static boolean stops(List<Word> words, int i) {
        Word w = words.get(i);
        if (Character.isDigit(w.text().codePointAt(0)) || STOP_WORDS.contains(w.key())) return true;
        return i + 1 < words.size() && STOP_PAIRS.contains(w.key() + " " + words.get(i + 1).key());
    }

    /**
     * The words {@code from..to} as written (quotes dropped, a genitive kind made nominative), if they name an event: a
     * word beyond the kind must identify it — written with a capital, or for a festival whose name starts with a capital
     * any word that is not generic ("Festivalul muzicii românești").
     */
    private static Optional<String> name(String line, List<Word> words, int from, int to, boolean festival) {
        while (to > from && CONNECTORS.contains(words.get(to).key())) {
            to--;
        }
        int count = to - from + 1;
        if (count < 2 || count > MAX_WORDS) return Optional.empty();
        boolean identified = false;
        boolean lowercaseIdentifies = festival && words.get(from).capitalized();
        for (int i = from + 1; i <= to; i++) {
            Word w = words.get(i);
            if ((w.capitalized() || lowercaseIdentifies) && !GENERIC.contains(w.key()) && !CONNECTORS.contains(w.key())) {
                identified = true;
                break;
            }
        }
        if (!identified && !words.get(from).capitalized()) return Optional.empty();
        if (!identified && GENERIC.contains(words.get(from).key())) return Optional.empty();
        String text = line.substring(words.get(from).start(), words.get(to).end());
        String first = words.get(from).text();
        String nominative = NOMINATIVE.get(words.get(from).key());
        if (nominative != null) {
            text = nominative + text.substring(first.length());
        } else if (Character.isLowerCase(first.codePointAt(0))) {
            text = first.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
        }
        text = text.replaceAll("[„“”\"«»]+", "").replaceAll("\\s+", " ").trim();
        return text.length() < MIN_LENGTH ? Optional.empty() : Optional.of(text);
    }

    /** Words that name no particular event: its kind, its reach, its art. */
    private static final Set<String> GENERIC = Set.of("festivalul", "festival", "festivalului", "concursul", "concurs",
            "concursului", "competitia", "competition", "contest", "international", "internationala", "internationale",
            "national", "nationala", "nationale", "muzica", "muzicii", "music", "musik", "stagiunea", "stagiune", "gala",
            "turneul", "turneu", "online", "festivalul-concurs", "concert", "concerte", "concertul", "recital", "de", "si", "of",
            "the", "and", "a", "al");
}
