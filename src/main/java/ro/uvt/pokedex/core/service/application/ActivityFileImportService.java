package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.ActivityRepository;
import ro.uvt.pokedex.core.service.importing.grid.CnfisArticlesSheetParser;
import ro.uvt.pokedex.core.service.importing.grid.CnfisArtsSheetParser;
import ro.uvt.pokedex.core.service.importing.grid.CnfisCitationSheetParser;
import ro.uvt.pokedex.core.service.importing.grid.CnfisSheets;
import ro.uvt.pokedex.core.service.importing.grid.GridItemSplitter;
import ro.uvt.pokedex.core.service.importing.grid.MusicGridLayout;
import ro.uvt.pokedex.core.service.importing.grid.MusicGridParser;
import ro.uvt.pokedex.core.service.reporting.ArtisticEventRankSupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * H142 slice 2 — turns a file a colleague already has into their activities, so nobody types again what is
 * written in their fișă de verificare: the Music grid of the faculty (each row's items become activities of the
 * row's type), a person's CNFIS Anexa 5.1 (each performance becomes a «Participare eveniment artistic» with its
 * kind and level), a person's CNFIS Anexa 4.1 (each citation of an artistic work becomes a «Citare sau cronică a unei
 * creații artistice (CNFIS 4.1)») or, since H142 slice 7, a person's CNFIS Anexa 5 (each article becomes a declared CS 2.1
 * article, its journal named by ISSN).
 *
 * <p>An import only ADDS records, marked as imported and "to check"; a record already brought by an earlier import
 * of the same text is skipped, so importing a file twice changes nothing. Roles and ensemble sizes are filled only
 * where a word makes them obvious ("dirijor", "duo"); everything else is left to the person's review. Files the
 * faculty already submitted ({@link ImportOptions#facultySubmitted}, H142 slice 7) land checked.</p>
 */
@Service
@RequiredArgsConstructor
public class ActivityFileImportService {

    private static final Logger log = LoggerFactory.getLogger(ActivityFileImportService.class);

    public enum FileKind { MUSIC_GRID, CNFIS_ARTS, CNFIS_CITATIONS, CNFIS_ARTICLES, INSTITUTIONAL_TABLE, UNSUPPORTED;

        /** A file that holds one person's activities and may be imported as theirs. */
        public boolean importable() {
            return this == MUSIC_GRID || this == CNFIS_ARTS || this == CNFIS_CITATIONS || this == CNFIS_ARTICLES;
        }
    }

    /**
     * H142 slice 7 — how a file is imported. {@code facultySubmitted}: the file is one the faculty already submitted (its
     * CNFIS report), so the records land checked rather than "to check", their source says so ({@code sourceLabel}).
     */
    public record ImportOptions(boolean facultySubmitted, String sourceLabel) {
        public static final ImportOptions PERSONAL = new ImportOptions(false, null);
    }

    /** An event a CNFIS Anexa 5.1 row names that the registry does not list, with the level the file gave it. */
    public record ReportedLevel(String eventName, String level) {
    }

    /**
     * What an import did: the kind of file, records created and skipped, items without a year, events recognised in
     * the registry, records per row, rows nobody recognised, activity types missing from the platform, and the name
     * the file's heading gives (the grid's "Lect.univ.dr. …").
     */
    public record ImportReport(FileKind kind, int created, int alreadyImported, int withoutYear, int eventsRecognised,
                               Map<String, Integer> byRow, List<String> unrecognisedRows, List<String> missingTypes,
                               String heading, int unmarkedRows, List<ReportedLevel> reportedLevels) {
        public ImportReport(FileKind kind, int created, int alreadyImported, int withoutYear, int eventsRecognised,
                            Map<String, Integer> byRow, List<String> unrecognisedRows, List<String> missingTypes,
                            String heading, int unmarkedRows) {
            this(kind, created, alreadyImported, withoutYear, eventsRecognised, byRow, unrecognisedRows, missingTypes,
                    heading, unmarkedRows, List.of());
        }

        static ImportReport of(FileKind kind) {
            return new ImportReport(kind, 0, 0, 0, 0, Map.of(), List.of(), List.of(), null, 0, List.of());
        }
    }

    private static final Pattern YEAR_SPAN = Pattern.compile(
            "((?:19|20)\\d{2})\\s*[-–—]\\s*((?:19|20)\\d{2}|prezent|present|în prezent|in prezent|azi)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOI = Pattern.compile("\\b10\\.\\d{4,9}/[^\\s,;]+");

    private final ActivityRepository activityRepository;
    private final ActivityInstanceRepository activityInstanceRepository;

    /** What the file is, without importing anything. */
    public static FileKind kindOf(Workbook workbook) {
        if (MusicGridParser.looksLikeGrid(workbook)) {
            return FileKind.MUSIC_GRID;
        }
        boolean citations = CnfisCitationSheetParser.looksLikeCitationSheet(workbook);
        boolean arts = CnfisArtsSheetParser.looksLikeArtsSheet(workbook);
        boolean articles = CnfisArticlesSheetParser.looksLikeArticlesSheet(workbook);
        // an institutional table (Anexa 6, 6.1) holds the whole faculty: never one person's records, whatever its columns
        if ((citations || arts || articles) && CnfisArtsSheetParser.isInstitutionalTable(workbook)) {
            return FileKind.INSTITUTIONAL_TABLE;
        }
        if (citations) {
            return FileKind.CNFIS_CITATIONS;
        }
        if (arts) {
            return FileKind.CNFIS_ARTS;
        }
        return articles ? FileKind.CNFIS_ARTICLES : FileKind.UNSUPPORTED;
    }

    /** Who the file is about (for matching it to a person): the grid's heading, a CNFIS sheet's name; or null. */
    public static String headingOf(Workbook workbook) {
        if (MusicGridParser.looksLikeGrid(workbook)) {
            return MusicGridParser.parse(workbook).heading();
        }
        return CnfisSheets.personName(workbook).orElse(null);
    }

    /**
     * Imports the file for {@code researcherEmail}. {@code uploadedBy} is the person who uploaded it when that is
     * not the researcher (a head or an admin), recorded with the source.
     */
    public ImportReport importFile(String researcherEmail, String fileName, InputStream input, String uploadedBy) {
        return importFile(researcherEmail, fileName, input, uploadedBy, ImportOptions.PERSONAL);
    }

    public ImportReport importFile(String researcherEmail, String fileName, InputStream input, String uploadedBy,
                                   ImportOptions options) {
        try (Workbook workbook = new XSSFWorkbook(input)) {
            return importWorkbook(researcherEmail, fileName, workbook, uploadedBy, options);
        } catch (IOException | RuntimeException e) {
            log.info("Activity import of {} for {} refused: {}", fileName, researcherEmail, e.getMessage());
            return ImportReport.of(FileKind.UNSUPPORTED);
        }
    }

    ImportReport importWorkbook(String researcherEmail, String fileName, Workbook workbook, String uploadedBy) {
        return importWorkbook(researcherEmail, fileName, workbook, uploadedBy, ImportOptions.PERSONAL);
    }

    ImportReport importWorkbook(String researcherEmail, String fileName, Workbook workbook, String uploadedBy,
                                ImportOptions options) {
        FileKind kind = kindOf(workbook);
        String source = switch (kind) {
            case MUSIC_GRID -> "Fișa de verificare: " + fileName;
            case CNFIS_ARTS -> "Anexa 5.1 CNFIS: " + fileName;
            case CNFIS_CITATIONS -> "Anexa 4.1 CNFIS: " + fileName;
            case CNFIS_ARTICLES -> "Anexa 5 CNFIS: " + fileName;
            default -> null;
        };
        if (source == null) {
            return ImportReport.of(kind);
        }
        boolean facultySubmitted = options != null && options.facultySubmitted();
        if (facultySubmitted && options.sourceLabel() != null && !options.sourceLabel().isBlank()) {
            source = options.sourceLabel().trim() + " — " + source;
        }
        if (uploadedBy != null && !uploadedBy.equalsIgnoreCase(researcherEmail)) {
            source += " (încărcată de " + uploadedBy + ")";
        }
        Map<String, Activity> types = new HashMap<>();
        List<String> missingTypes = new ArrayList<>();
        List<Draft> drafts = new ArrayList<>();
        List<String> unrecognised = List.of();
        List<ReportedLevel> reportedLevels = new ArrayList<>();
        String heading = null;
        int unmarked = 0;
        if (kind == FileKind.CNFIS_ARTICLES) {
            CnfisArticlesSheetParser.ParsedArticles articles = CnfisArticlesSheetParser.parse(workbook);
            for (CnfisArticlesSheetParser.ArticleRow row : articles.rows()) {
                drafts.add(articleDraft(row));
            }
            if (articles.patents() > 0) {
                unrecognised = List.of("Anexa 5: " + articles.patents() + " brevet(e), neimportate");
            }
        } else if (kind == FileKind.MUSIC_GRID) {
            MusicGridParser.ParsedGrid grid = MusicGridParser.parse(workbook);
            heading = grid.heading();
            unrecognised = grid.unrecognised();
            for (MusicGridParser.ParsedRow row : grid.rows()) {
                for (GridItemSplitter.Item item : row.items()) {
                    drafts.add(gridDraft(row.row(), item));
                }
            }
        } else if (kind == FileKind.CNFIS_CITATIONS) {
            for (CnfisCitationSheetParser.CitationRow row : CnfisCitationSheetParser.parse(workbook)) {
                drafts.add(citationDraft(row));
            }
        } else {
            CnfisArtsSheetParser.ParsedArts arts = CnfisArtsSheetParser.parse(workbook);
            unmarked = arts.unmarked();
            for (CnfisArtsSheetParser.ArtsRow row : arts.rows()) {
                Draft draft = artsDraft(row);
                drafts.add(draft);
                String event = draft.references().get(Activity.ReferenceField.EVENT_NAME);
                if (!draft.eventRecognised() && event != null && row.level() != null) {
                    reportedLevels.add(new ReportedLevel(event, row.level()));
                }
            }
        }

        Map<String, Draft> byKey = new LinkedHashMap<>();
        for (Draft draft : drafts) {
            Activity type = types.computeIfAbsent(draft.type(), name -> activityRepository.findByName(name).stream().findFirst().orElse(null));
            if (type == null) {
                if (!missingTypes.contains(draft.type())) missingTypes.add(draft.type());
                continue;
            }
            byKey.putIfAbsent(importKey(researcherEmail, draft.type(), draft.keyText()), draft);
        }
        Set<String> existing = byKey.isEmpty() ? Set.of() : activityInstanceRepository
                .findAllByResearcherIdAndImportKeyIn(researcherEmail, byKey.keySet()).stream()
                .map(ActivityInstance::getImportKey).collect(Collectors.toSet());

        List<ActivityInstance> toSave = new ArrayList<>();
        Map<String, Integer> byRow = new LinkedHashMap<>();
        int withoutYear = 0, recognised = 0;
        for (Map.Entry<String, Draft> entry : byKey.entrySet()) {
            if (existing.contains(entry.getKey())) {
                continue;
            }
            Draft draft = entry.getValue();
            Activity type = types.get(draft.type());
            ActivityInstance instance = new ActivityInstance();
            instance.setResearcherId(researcherEmail);
            instance.setActivity(type);
            instance.setName(abbreviate(draft.text(), NAME_LENGTH));
            instance.setDate(draft.date());
            instance.setFields(onlyDeclared(type, draft.fields()));
            instance.setReferenceFields(new HashMap<>(draft.references()));
            instance.setImportSource(source);
            instance.setImportKey(entry.getKey());
            instance.setNeedsReview(facultySubmitted ? Boolean.FALSE : Boolean.TRUE);
            instance.setEventLevelSuggestion(draft.eventLevelSuggestion());
            toSave.add(instance);
            byRow.merge(draft.rowLabel(), 1, Integer::sum);
            if (draft.date() == null) withoutYear++;
            if (draft.eventRecognised()) recognised++;
        }
        if (!toSave.isEmpty()) {
            activityInstanceRepository.saveAll(toSave);
        }
        int alreadyImported = byKey.size() - toSave.size();
        log.info("Activity import {} for {}: created {}, already imported {}, rows not recognised {}",
                source, researcherEmail, toSave.size(), alreadyImported, unrecognised.size());
        return new ImportReport(kind, toSave.size(), alreadyImported, withoutYear, recognised, byRow, unrecognised,
                missingTypes, heading, unmarked, List.copyOf(reportedLevels));
    }

    // ── what each row's item becomes ──────────────────────────────────────────

    /**
     * One record to create: its type, text, date, fields, references and the row it came from; for an artistic
     * performance, the level the file gave it — shown to the experts who rank the event, never scored (H142 slice 3).
     * {@code keyText} tells two records apart on a re-import (the text, unless two records may share it).
     */
    record Draft(String type, String rowLabel, String text, String date, Map<String, String> fields,
                 Map<Activity.ReferenceField, String> references, boolean eventRecognised, String eventLevelSuggestion,
                 String keyText) {
        Draft(String type, String rowLabel, String text, String date, Map<String, String> fields,
              Map<Activity.ReferenceField, String> references, boolean eventRecognised, String eventLevelSuggestion) {
            this(type, rowLabel, text, date, fields, references, eventRecognised, eventLevelSuggestion, text);
        }
    }

    static final String CITATION_TYPE = "Citare sau cronică a unei creații artistice (CNFIS 4.1)";
    private static final int NAME_LENGTH = 160;

    /**
     * A row of Anexa 4.1: the work is the record's name — a long identification is cut at its first comma, the rest
     * kept as the details — the citation is the publication, and its year the record's date. The same work cited
     * twice is two records.
     */
    static Draft citationDraft(CnfisCitationSheetParser.CitationRow row) {
        String work = row.work().replaceAll("\\s+", " ").trim();
        String details = null;
        if (work.length() > NAME_LENGTH) {
            int cut = work.indexOf(", ");
            if (cut > 0 && cut <= NAME_LENGTH) {
                details = work.substring(cut + 2).trim();
                work = work.substring(0, cut).trim();
            }
        }
        Map<String, String> fields = new LinkedHashMap<>();
        if (row.workYear() != null) fields.put("An_creatie", String.valueOf(row.workYear()));
        if (details != null) fields.put("Detalii_creatie", details);
        fields.put("Publicatie", row.citation().replaceAll("\\s+", " ").trim());
        return new Draft(CITATION_TYPE, "CNFIS 4.1", work, row.citationYear() == null ? null : row.citationYear() + "-01-01",
                fields, Map.of(), false, null, row.work() + " | " + row.citation());
    }

    /**
     * H142 slice 7 — a row of Anexa 5: a declared CS 2.1 article, its journal named by ISSN (the first the row gives), so
     * its databases come from the lists (H145, H142 slice 4); the DOI when the row gives a valid one, the rest (the WoS
     * code, the ISBNs, a malformed DOI) kept as evidence. The same title twice is one record.
     */
    static Draft articleDraft(CnfisArticlesSheetParser.ArticleRow row) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Titlu", row.title());
        if (row.journal() != null) fields.put("Revista_sau_volumul", row.journal());
        if (row.doi() != null) fields.put("DOI", row.doi());
        List<String> evidence = new ArrayList<>();
        if (row.wosCode() != null) evidence.add("WoS: " + row.wosCode());
        if (!row.isbns().isEmpty()) evidence.add("ISBN: " + String.join(", ", row.isbns()));
        if (row.issns().size() > 1) evidence.add("ISSN: " + row.issns().stream().map(ActivityFileImportService::hyphenated)
                .collect(Collectors.joining(", ")));
        if (!evidence.isEmpty()) fields.put("Dovezi", String.join("; ", evidence));
        Map<Activity.ReferenceField, String> references = new LinkedHashMap<>();
        if (!row.issns().isEmpty()) {
            references.put(Activity.ReferenceField.FORUM_ISSN, hyphenated(row.issns().getFirst()));
        }
        return new Draft(MusicGridLayout.Row.CS_2_1.activityType(), "CNFIS 5", row.title(),
                row.year() == null ? null : row.year() + "-01-01", fields, references, false, null,
                row.title() + (row.doi() == null ? "" : " | " + row.doi()));
    }

    private static String hyphenated(String issn) {
        return issn.length() == 8 ? issn.substring(0, 4) + "-" + issn.substring(4) : issn;
    }

    /** The visibility rows of the Music grid, as the experts read them. */
    static final String SUGGESTED_TOP = "Fișa de verificare: CS 1.1 — vizibilitate internațională sau națională de vârf";
    static final String SUGGESTED_REGIONAL = "Fișa de verificare: CS 1.2 — vizibilitate regională sau locală";
    /** H144 — what the other rows of the grid imply about the entity a record names, as the experts read it. */
    static final String SUGGESTED_ORGANISER_INTERNATIONAL = "Fișa de verificare: RIA 1.4 — manifestare internațională";
    static final String SUGGESTED_ORGANISER_NATIONAL = "Fișa de verificare: RIA 1.5 — manifestare națională";
    static final String SUGGESTED_JURY_INTERNATIONAL = "Fișa de verificare: RIA 3.3 — concurs sau distincție internațională";
    static final String SUGGESTED_JURY_NATIONAL = "Fișa de verificare: RIA 3.3 — concurs sau distincție națională";
    static final String SUGGESTED_KEYNOTE_INTERNATIONAL = "Fișa de verificare: RIA 3.7 — manifestare științifică internațională";
    static final String SUGGESTED_KEYNOTE_NATIONAL = "Fișa de verificare: RIA 3.7 — manifestare științifică națională";

    /** The artistic event of the registry the text names, as the record's EVENT_NAME; whether one was found. */
    private static boolean putArtisticEvent(String text, Map<Activity.ReferenceField, String> references) {
        Optional<String> event = ArtisticEventRankSupport.findIn(text);
        event.ifPresent(name -> references.put(Activity.ReferenceField.EVENT_NAME, name));
        return event.isPresent();
    }

    static Draft gridDraft(MusicGridLayout.Row row, GridItemSplitter.Item item) {
        String text = item.text();
        String lower = fold(text);
        Map<String, String> fields = new LinkedHashMap<>();
        Map<Activity.ReferenceField, String> references = new LinkedHashMap<>();
        boolean eventRecognised = false;
        String levelSuggestion = null;
        if (row.textField() != null) {
            fields.put(row.textField(), text);
        }
        // H143: a book's publisher, when the line names one the lists know — its category follows without typing
        ro.uvt.pokedex.core.service.reporting.PublisherCategorySupport.findIn(text)
                .ifPresent(publisher -> fields.put(ro.uvt.pokedex.core.service.reporting.PublisherRules.FIELD_PUBLISHER, publisher));
        String firstLink = item.links().isEmpty() ? null : item.links().getFirst();
        switch (row) {
            case DID_2_1 -> {
                if (firstLink != null) fields.put("Link", firstLink);
                String support = lower.contains("youtu") || lower.contains("streaming") || lower.contains("video") ? "Streaming (înregistrare video din concert public)"
                        : lower.contains("vinil") || lower.contains("vinyl") ? "Disc de vinil"
                        : lower.contains("dvd") ? "DVD" : lower.matches(".*\\bcd\\b.*") ? "CD" : null;
                if (support != null) fields.put("Suport", support);
            }
            case CS_1_1, CS_1_2, RIA_2_3 -> {
                if (row == MusicGridLayout.Row.RIA_2_3) {
                    fields.put("Rezultat", "Premiu");
                } else {
                    fields.put("Rezultat", "Participare");
                    levelSuggestion = row == MusicGridLayout.Row.CS_1_1 ? SUGGESTED_TOP : SUGGESTED_REGIONAL;
                    roleOf(lower).ifPresent(role -> fields.put("Rol", role));
                    ensembleSizeOf(lower).ifPresent(size -> fields.put("Marime_formatie", String.valueOf(size)));
                }
                Optional<String> event = ArtisticEventRankSupport.findIn(text);
                if (event.isPresent()) {
                    references.put(Activity.ReferenceField.EVENT_NAME, event.get());
                    eventRecognised = true;
                }
            }
            case CS_2_1 -> {
                Matcher doi = DOI.matcher(text);
                if (doi.find()) fields.put("DOI", doi.group());
            }
            case CS_3_1 -> fields.put("Rol", "Membru");
            case RIA_1_2 -> fields.put("Rol", "Director (proiect național)");
            case RIA_1_1 -> putYears(text, fields);
            case RIA_1_3 -> fields.put("Rol", lower.contains("recenz") ? "Recenzor" : "Membru în colectivul de redacție");
            // H144: the level a row of the grid implies is a suggestion for the experts who rank the named entity,
            // never a field; an artistic event the text names is taken from the registry
            case RIA_1_4, RIA_1_5 -> {
                levelSuggestion = row == MusicGridLayout.Row.RIA_1_4 ? SUGGESTED_ORGANISER_INTERNATIONAL : SUGGESTED_ORGANISER_NATIONAL;
                eventRecognised = putArtisticEvent(text, references);
            }
            case RIA_3_1 -> fields.put("Rol", "Membru");
            case RIA_3_2 -> {
                fields.put("Rol", "Funcție de conducere");
                fields.put("Functia", text);
                putYears(text, fields);
            }
            case RIA_3_3 -> {
                levelSuggestion = lower.contains("internationa") ? SUGGESTED_JURY_INTERNATIONAL : SUGGESTED_JURY_NATIONAL;
                eventRecognised = putArtisticEvent(text, references);
            }
            case RIA_3_4, CS_2_2 -> eventRecognised = putArtisticEvent(text, references);
            case RIA_3_7 -> levelSuggestion = lower.contains("internationa") ? SUGGESTED_KEYNOTE_INTERNATIONAL : SUGGESTED_KEYNOTE_NATIONAL;
            default -> { }
        }
        if (firstLink != null && !fields.containsKey("Link") && !"Dovezi".equals(row.textField())) {
            fields.putIfAbsent("Dovezi", String.join(" ", item.links()));
        }
        return new Draft(row.activityType(), row.label(), text, item.date(), fields, references, eventRecognised,
                levelSuggestion);
    }

    static Draft artsDraft(CnfisArtsSheetParser.ArtsRow row) {
        String text = row.work().isEmpty() ? row.event() : row.work();
        Map<String, String> fields = new LinkedHashMap<>();
        // H145: the kind the sheet marked is the ensemble's size, a fact — kept as the size it stands for (one, two to
        // four, five or more), from which the CNFIS kind is derived; a prize or a nomination is the result
        switch (row.kind()) {
            case "INDIVIDUAL" -> fields.put("Marime_formatie", "1");
            case "GROUP" -> fields.put("Marime_formatie", "2");
            case "COLLECTIVE" -> fields.put("Marime_formatie", "5");
            default -> { }
        }
        fields.put("Rezultat", switch (row.kind()) {
            case "NOMINATION" -> "Nominalizare";
            case "PRIZE" -> "Premiu";
            default -> "Participare";
        });
        if (row.participants() != null) fields.put("N_participanti_universitate", String.valueOf(row.participants()));
        fields.put("Dovezi", (row.work() + (row.event().isEmpty() ? "" : " | " + row.event())).trim());
        Map<Activity.ReferenceField, String> references = new LinkedHashMap<>();
        Optional<String> listed = ArtisticEventRankSupport.findIn(row.event() + " " + row.work());
        if (listed.isPresent()) {
            references.put(Activity.ReferenceField.EVENT_NAME, listed.get());
        } else if (!row.event().isEmpty()) {
            // H142 slice 7: the event's own name, cut from the cell like the institutional table's ("Festivalul …,
            // ediția a XXVI-a, Iași, 14-20 octombrie 2023" → "Festivalul …"), so the experts and the ranking read one name
            references.put(Activity.ReferenceField.EVENT_NAME, eventNameOf(row.event()));
        }
        // the level the faculty gave the event: a suggestion for the experts, never a score (H142 slice 3)
        String suggestion = switch (row.level() == null ? "" : row.level()) {
            case "INTERNATIONAL_TOP" -> "Fișa CNFIS 5.1: internațional de vârf";
            case "INTERNATIONAL" -> "Fișa CNFIS 5.1: internațional";
            case "NATIONAL" -> "Fișa CNFIS 5.1: național";
            default -> null;
        };
        return new Draft(MusicGridLayout.EVENT_TYPE, "CNFIS 5.1", text + " | " + row.event(),
                row.year() == null ? null : row.year() + "-01-01", fields, references, listed.isPresent(), suggestion);
    }

    /** A date at the start of a cell ("03.11.2022 – ", "01-04.06.2023 – ", "15 octombrie 2022, "), dropped from a name. */
    private static final Pattern LEADING_DATE = Pattern.compile(
            "^\\s*(\\d{1,2}(\\s*[-–]\\s*\\d{1,2})?\\s*[./]\\s*\\d{1,2}\\s*[./]\\s*\\d{2,4}|\\d{1,2}\\s+\\p{L}+\\s+\\d{4})\\s*[-–—,:]?\\s*");

    /**
     * H142 slice 7 — the name an Anexa 5.1 cell gives its event: the festival, series or host the cell names (cut like
     * the institutional table's), else the cell without its leading date — the same concert reported for several
     * years is then one name.
     */
    static String eventNameOf(String cell) {
        Optional<String> named = ArtisticEventSeedService.eventName(cell);
        if (named.isPresent()) {
            return named.get();
        }
        String text = LEADING_DATE.matcher(cell == null ? "" : cell.replaceAll("\\s+", " ").trim()).replaceFirst("")
                .replaceAll("[;.,\\s]+$", "");
        return abbreviate(text.isEmpty() ? cell : text, 200);
    }

    private static Optional<String> roleOf(String lower) {
        List<String> roles = new ArrayList<>();
        if (lower.contains("dirijor") || lower.contains("dirijat")) roles.add("Dirijor");
        if (lower.matches(".*\\bsolist.*")) roles.add("Solist");
        if (lower.contains("concert-maestru") || lower.contains("concertmaestru") || lower.contains("concert maestru")) roles.add("Concert-maestru");
        if (lower.contains("regizor") || lower.contains("regia ")) roles.add("Regizor");
        if (lower.contains("maestru de balet")) roles.add("Maestru de balet");
        if (lower.matches(".*\\bcompozitor\\b.*")) roles.add("Compozitor");
        return roles.size() == 1 ? Optional.of(roles.getFirst()) : Optional.empty(); // two roles named: let the person choose
    }

    private static Optional<Integer> ensembleSizeOf(String lower) {
        if (lower.matches(".*\\b(duo|duet)\\b.*")) return Optional.of(2);
        if (lower.matches(".*\\btrio\\b.*")) return Optional.of(3);
        if (lower.matches(".*\\bcvartet.*")) return Optional.of(4);
        if (lower.matches(".*\\bcvintet.*")) return Optional.of(5);
        return Optional.empty();
    }

    private static void putYears(String text, Map<String, String> fields) {
        Matcher span = YEAR_SPAN.matcher(text);
        if (span.find()) {
            fields.put("An_inceput", span.group(1));
            if (span.group(2).matches("\\d{4}")) fields.put("An_sfarsit", span.group(2));
            return;
        }
        Matcher year = Pattern.compile("\\b((?:19|20)\\d{2})\\b").matcher(text);
        if (year.find()) {
            fields.put("An_inceput", year.group(1));
            fields.put("An_sfarsit", year.group(1));
        }
    }

    /** Only the fields the activity type declares, and for a select only one of its options. */
    private static Map<String, String> onlyDeclared(Activity type, Map<String, String> fields) {
        Map<String, String> out = new LinkedHashMap<>();
        if (type.getFields() == null) {
            return out;
        }
        for (Activity.Field field : type.getFields()) {
            String value = fields.get(field.getName());
            if (value == null || value.isBlank()) continue;
            if (field.getAllowedValues() != null && !field.getAllowedValues().isEmpty()
                    && !field.getAllowedValues().contains(value)) continue;
            out.put(field.getName(), value);
        }
        return out;
    }

    static String importKey(String researcherEmail, String type, String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((researcherEmail.toLowerCase(Locale.ROOT) + "|" + type + "|" + fold(text))
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String fold(String value) {
        String n = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String abbreviate(String value, int max) {
        String t = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
