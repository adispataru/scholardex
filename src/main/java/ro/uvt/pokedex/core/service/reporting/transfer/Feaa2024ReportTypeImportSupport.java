package ro.uvt.pokedex.core.service.reporting.transfer;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportInstanceSnapshot;
import ro.uvt.pokedex.core.model.reporting.transfer.SnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingKind;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding;
import ro.uvt.pokedex.core.service.reporting.transfer.binding.TemplateBindingLoader;
import ro.uvt.pokedex.core.service.reporting.transfer.render.TemplateDocxRenderer;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Report-type support for the FV FEAA economics fišă (no-formula DOCX, Ordin 6129/2016 Anexa 27).
 * Slice 1 (H62): articles table (M / N / AIS / Pi from FEEA_P), citations table (AIS / quartile / Cj
 * from FEEA_C), and the summary's obtained P / C / S=P+C. The book slots (7–10) and the
 * Core/Infoeconomics article count are later slices and stay as template placeholders for now.
 */
@Component
public class Feaa2024ReportTypeImportSupport implements ReportTypeImportSupport {

    public static final String REPORT_TYPE_KEY = "feaa-2024";
    private static final String BINDING_RESOURCE = "report-templates/feaa-2024/binding.json";
    private static final String ROLE_ARTICLES = "journal-publications";
    private static final String ROLE_CITATIONS = "citations-per-publication";
    private static final String ROLE_BOOKS = "book-publications";
    /** Synthetic total key for S = P + C (books are display-only, excluded from P/S). */
    private static final String TOTAL_FINAL = "feaa-final";
    /** Count of ISI articles with AIS≠0 in Core Economics (M=10) or Infoeconomics (M=8). */
    private static final String TOTAL_CORE_COUNT = "feaa-core-count";
    /** Lowest multiplier that still counts as Core Economics/Infoeconomics (Social Science is M=6). */
    private static final int CORE_INFO_MIN_MULTIPLIER = 8;

    private final TemplateBindingLoader bindingLoader;
    private final TemplateDocxRenderer renderer;
    private TemplateBinding binding;

    public Feaa2024ReportTypeImportSupport(TemplateBindingLoader bindingLoader, TemplateDocxRenderer renderer) {
        this.bindingLoader = bindingLoader;
        this.renderer = renderer;
    }

    @PostConstruct
    void loadBinding() {
        this.binding = bindingLoader.load(BINDING_RESOURCE);
    }

    @Override
    public String reportTypeKey() {
        return REPORT_TYPE_KEY;
    }

    @Override
    public List<String> declaredRoles() {
        return binding.getRoles().stream().map(r -> r.getRoleKey()).toList();
    }

    @Override
    public Map<String, List<String>> declaredBlocksByRole() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        binding.getRoles().stream()
                .filter(r -> r.getKind() == BindingKind.STACKED_BLOCKS)
                .forEach(r -> out.put(r.getRoleKey(),
                        r.getBlocks().stream().map(b -> b.getActivityName()).collect(Collectors.toList())));
        return out;
    }

    @Override
    public TemplateBinding binding() {
        return binding;
    }

    @Override
    public Set<ReportFormat> supportedExportFormats() {
        return Set.of(ReportFormat.DOCX);
    }

    @Override
    public Set<ReportFormat> supportedImportFormats() {
        return Set.of();
    }

    @Override
    public byte[] render(ReportInstanceSnapshot snapshot, ReportFormat format) {
        if (format != ReportFormat.DOCX) {
            throw new IllegalArgumentException("Unsupported export format: " + format);
        }

        Map<String, List<Map<String, Object>>> rowsByRole = new LinkedHashMap<>();
        Map<String, Double> slotTotals = new LinkedHashMap<>();
        int coreInfoCount = 0;
        for (SnapshotItem item : snapshot.getItems()) {
            if (item instanceof PublicationSnapshotItem pub && ROLE_ARTICLES.equals(pub.getRoleKey())) {
                rowsByRole.computeIfAbsent(ROLE_ARTICLES, k -> new ArrayList<>()).add(articleRow(pub));
                if (isCoreOrInfoArticle(pub)) coreInfoCount++;
            } else if (item instanceof PublicationSnapshotItem book && ROLE_BOOKS.equals(book.getRoleKey())) {
                // One book indicator feeds all four slots; the coefficient (base score) picks the slot.
                String slotRole = bookSlotRole(book.getForumScore());
                if (slotRole == null) continue;
                rowsByRole.computeIfAbsent(slotRole, k -> new ArrayList<>()).add(bookRow(book));
                double pi = book.getAuthorScore() != null ? book.getAuthorScore() : 0.0;
                slotTotals.merge(slotRole, pi, Double::sum);
            } else if (item instanceof CitationSnapshotItem cit && ROLE_CITATIONS.equals(cit.getRoleKey())) {
                // FEAA lists one row per citation (citing publication), not per cited publication.
                if (cit.getCitingPublications() == null) continue;
                for (CitationSnapshotItem.CitingPublication citing : cit.getCitingPublications()) {
                    rowsByRole.computeIfAbsent(ROLE_CITATIONS, k -> new ArrayList<>()).add(citationRow(citing));
                }
            }
        }

        // Books (slots 7–10) are shown for the candidate's information but are NOT folded into P/S — they
        // carry a per-position cap (25% of P_min) that varies by target position, so the candidate
        // substitutes a book for a weaker article manually. P = articles only; S = P + C.
        Map<String, Double> totals = new LinkedHashMap<>(snapshot.getTotals());
        totals.putAll(slotTotals);
        double p = totals.getOrDefault(ROLE_ARTICLES, 0.0);
        double c = totals.getOrDefault(ROLE_CITATIONS, 0.0);
        totals.put(TOTAL_FINAL, p + c);
        totals.put(TOTAL_CORE_COUNT, (double) coreInfoCount);

        return renderer.render(binding, rowsByRole, totals);
    }

    /** An ISI article with non-null AIS whose journal is Core Economics (M=10) or Infoeconomics (M=8). */
    private boolean isCoreOrInfoArticle(PublicationSnapshotItem pub) {
        return pub.getMultiplier() != null && pub.getMultiplier() >= CORE_INFO_MIN_MULTIPLIER
                && pub.getForumScore() != null && pub.getForumScore() > 0;
    }

    /** Maps a book coefficient (the base score) to its fišă slot role. */
    private String bookSlotRole(Double coefficient) {
        if (coefficient == null) return null;
        if (near(coefficient, 0.5)) return "feaa-book-7";   // carte ∈ Anexa 1
        if (near(coefficient, 0.25)) return "feaa-book-8";  // capitol ∈ Anexa 1
        if (near(coefficient, 0.2)) return "feaa-book-9";   // carte ∉ Anexa 1
        if (near(coefficient, 0.1)) return "feaa-book-10";  // capitol ∉ / ISI proceedings
        return null;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    private Map<String, Object> bookRow(PublicationSnapshotItem book) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("publication.reference", composePublication(book.getAuthors(), book.getTitle(),
                book.getForumName(), book.getVolumeInfo(), book.getYear()));
        row.put("publication.authorCount", book.getAuthorCount()); // N
        row.put("publication.forumScore", book.getForumScore());   // Pi coefficient (0.5/0.25/0.2/0.1)
        row.put("publication.authorScore", book.getAuthorScore()); // Punctaj final = coefficient / N
        return row;
    }

    @Override
    public List<SnapshotItem> parse(InputStream input, ReportFormat format) {
        throw new UnsupportedOperationException("Import not supported for " + REPORT_TYPE_KEY);
    }

    private Map<String, Object> articleRow(PublicationSnapshotItem pub) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("publication.reference", composePublication(pub.getAuthors(), pub.getTitle(),
                pub.getForumName(), pub.getVolumeInfo(), pub.getYear()));
        row.put("publication.multiplier", pub.getMultiplier());   // M
        row.put("publication.authorCount", pub.getAuthorCount()); // N
        row.put("publication.forumScore", pub.getForumScore());   // AIS (base score)
        row.put("publication.authorScore", pub.getAuthorScore()); // Pi = M·AIS·(1-(N-1)·0.1)
        return row;
    }

    private Map<String, Object> citationRow(CitationSnapshotItem.CitingPublication citing) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("citation.reference", composePublication(citing.getAuthors(), citing.getTitle(),
                citing.getForumName(), citing.getVolumeInfo(), citing.getYear()));
        row.put("citation.ais", citing.getScore());          // AIS = base/journal score
        row.put("citation.quartile", citing.getQuartile());  // Cuartila
        row.put("citation.score", citing.getAuthorScore());  // Cj (quartile points)
        return row;
    }

    private String composePublication(String authors, String title, String forum, String volume, Integer year) {
        List<String> parts = new ArrayList<>();
        if (notBlank(authors)) parts.add(authors);
        if (notBlank(title)) parts.add(title);
        if (notBlank(forum)) parts.add(forum);
        String tail = "";
        if (notBlank(volume)) tail += volume;
        if (year != null) tail += (tail.isEmpty() ? "" : " ") + "(" + year + ")";
        if (!tail.isEmpty()) parts.add(tail);
        return String.join(", ", parts);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
