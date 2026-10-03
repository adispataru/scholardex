package ro.uvt.pokedex.core.service.importing.journaldb;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.journaldb.JournalDatabaseJournalFact;
import ro.uvt.pokedex.core.repository.journaldb.JournalDatabaseJournalFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.reporting.JournalDatabases;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * H142 slice 4 — loads the title lists of the journal databases the standards name, the way DOAJ and ERIH are loaded:
 * the files a person downloads from each vendor (the vendors' terms forbid fetching them by robot) go under
 * {@code data/journal-databases/<DATABASE>/} — one folder per database of {@link JournalDatabases}, any number of
 * lists inside, in any shape {@link TitleListParser} reads — and become {@code journaldb.journal_facts}, one per
 * journal and database (rows sharing an ISSN are one journal). {@code JournalDatabaseOnboardingService} then matches
 * them to forums by ISSN, and the projection publishes them as memberships.
 * <p>
 * A database whose files all read is replaced by what they hold. A database with a file that yields no journal keeps
 * its previous facts (the error says which file); a database without a folder keeps its facts too.
 */
@Service
@RequiredArgsConstructor
public class JournalDatabaseDataService {

    private static final Logger log = LoggerFactory.getLogger(JournalDatabaseDataService.class);

    private final JournalDatabaseJournalFactRepository repository;

    /** What an import did: the counts, and a line per database for the admin. */
    public record ImportSummary(ImportProcessingResult result, List<String> databases) {
        public String message() {
            return String.join("; ", databases);
        }
    }

    public ImportSummary importFromFolder(String folder, String batchId) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        List<String> lines = new ArrayList<>();
        Path root = folder == null ? null : Path.of(folder);
        if (root == null || !Files.isDirectory(root)) {
            result.markError("no folder " + folder);
            lines.add("no folder " + folder + ": nothing changed");
            return new ImportSummary(result, lines);
        }
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .filter(name -> !JournalDatabases.ALL.contains(name)).sorted()
                    .forEach(name -> lines.add(name + ": not a database of the standards, ignored"));
        } catch (IOException e) {
            result.markError("cannot list " + folder + ": " + e.getMessage());
        }
        Instant now = Instant.now();
        for (String database : JournalDatabases.ALL.stream().sorted().toList()) {
            List<Path> files = files(root.resolve(database));
            if (files.isEmpty()) {
                continue;
            }
            Map<String, JournalDatabaseJournalFact> byIssn = new HashMap<>();
            Map<String, JournalDatabaseJournalFact> facts = new LinkedHashMap<>();
            List<String> failed = new ArrayList<>();
            int skipped = 0;
            for (Path file : files) {
                String name = file.getFileName().toString();
                try (InputStream in = Files.newInputStream(file)) {
                    TitleListParser.Parsed parsed = TitleListParser.parse(in);
                    if (parsed.rows().isEmpty()) {
                        failed.add(name);
                        result.markError(database + "/" + name + ": no journal with an ISSN (" + parsed.skipped()
                                + " rows without one, or no header with an ISSN column)");
                        continue;
                    }
                    for (TitleListParser.TitleRow row : parsed.rows()) {
                        result.markProcessed();
                        add(database, name, row, byIssn, facts);
                    }
                    skipped += parsed.skipped();
                } catch (IOException | RuntimeException e) {
                    failed.add(name);
                    result.markError(database + "/" + name + ": " + e.getMessage());
                }
            }
            if (!failed.isEmpty()) {
                lines.add(database + ": kept the previous " + repository.countByDatabase(database)
                        + " journals (unreadable: " + String.join(", ", failed) + ")");
                continue;
            }
            String asOf = asOf(files);
            for (JournalDatabaseJournalFact fact : facts.values()) {
                fact.setAsOf(asOf);
                fact.setSourceBatchId(batchId);
                fact.setCreatedAt(now);
                fact.setUpdatedAt(now);
                result.markImported();
            }
            for (int i = 0; i < skipped; i++) {
                result.markSkipped(database + ": a row without an ISSN or no serial");
            }
            repository.deleteByDatabase(database);
            repository.saveAll(facts.values());
            lines.add(database + ": " + facts.size() + " journals from " + files.size() + " list(s), as of " + asOf
                    + (skipped > 0 ? ", " + skipped + " rows without an ISSN or no serial" : ""));
            log.info("Journal database {}: {} journals from {} list(s) ({} rows skipped)", database, facts.size(),
                    files.size(), skipped);
        }
        if (lines.isEmpty()) {
            lines.add("no list under " + folder + " (one folder per database: " + String.join(", ",
                    JournalDatabases.ALL.stream().sorted().toList()) + "): nothing changed");
        }
        return new ImportSummary(result, lines);
    }

    /** One row: a journal already met through one of its ISSNs gains the row's ISSNs, years and file. */
    private static void add(String database, String file, TitleListParser.TitleRow row,
                            Map<String, JournalDatabaseJournalFact> byIssn, Map<String, JournalDatabaseJournalFact> facts) {
        JournalDatabaseJournalFact met = row.issns().stream().map(byIssn::get).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        final JournalDatabaseJournalFact fact = met != null ? met : new JournalDatabaseJournalFact();
        if (met == null) {
            fact.setId(database + ":" + row.issns().iterator().next());
            fact.setDatabase(database);
            fact.setTitle(row.title() == null || row.title().isBlank() ? null : row.title());
            fact.setIssn(row.printIssn());
            fact.setEIssn(row.onlineIssn());
            fact.setCoverageFrom(row.from());
            fact.setCoverageTo(row.to());
            facts.put(fact.getId(), fact);
        } else {
            if (fact.getIssn() == null) {
                fact.setIssn(row.printIssn());
            }
            if (fact.getEIssn() == null) {
                fact.setEIssn(row.onlineIssn());
            }
            if (fact.getTitle() == null && row.title() != null && !row.title().isBlank()) {
                fact.setTitle(row.title());
            }
            if (row.from() != null && (fact.getCoverageFrom() == null || row.from() < fact.getCoverageFrom())) {
                fact.setCoverageFrom(row.from());
            }
            // a row still covering the journal (no last year) wins over one that ended
            if (row.to() == null || (fact.getCoverageTo() != null && row.to() > fact.getCoverageTo())) {
                fact.setCoverageTo(row.to());
            }
        }
        for (String issn : row.issns()) {
            byIssn.putIfAbsent(issn, fact);
            if (!fact.getAliasIssns().contains(issn)) {
                fact.getAliasIssns().add(issn);
            }
        }
        fact.getAliasIssns().removeIf(issn -> issn.equals(fact.getIssn()) || issn.equals(fact.getEIssn()));
        if (!fact.getFiles().contains(file)) {
            fact.getFiles().add(file);
        }
    }

    private static List<Path> files(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The date the newest list of the folder was saved (when it was downloaded). */
    private static String asOf(List<Path> files) {
        Instant newest = null;
        for (Path file : files) {
            try {
                Instant modified = Files.getLastModifiedTime(file).toInstant();
                if (newest == null || modified.isAfter(newest)) {
                    newest = modified;
                }
            } catch (IOException ignored) {
                // a file without a date does not date the list
            }
        }
        return newest == null ? null : newest.atZone(ZoneOffset.UTC).toLocalDate().toString();
    }
}
