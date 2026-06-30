package ro.uvt.pokedex.core.service.reporting.transfer;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.transfer.ActivitySnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportInstanceSnapshot;
import ro.uvt.pokedex.core.model.reporting.transfer.SnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingKind;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding;
import ro.uvt.pokedex.core.service.reporting.EffectiveAuthorCountSupport;
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
 * H65 slice 1 — report-type support for the FV Fizică fišă (Ordin 6129/2016, Anexa 1; no-formula DOCX). This slice
 * renders the research-activity section + the T20 summary: indicator <b>I</b> = ΣAISᵢ/Nefᵢ (2.1, all-author article
 * table) and <b>P</b> = ΣAISᵢ (2.2, first-or-corresponding article table via the H63 role), plus the summary's
 * obtained I / P / T (partial: T = I/2 + P/2 until the A/C/h slices land). The didactic block (A1–A10), C and h are
 * later slices and stay as template placeholders.
 */
@Component
public class Fizica2024ReportTypeImportSupport implements ReportTypeImportSupport {

    public static final String REPORT_TYPE_KEY = "fizica-ff";
    private static final String BINDING_RESOURCE = "report-templates/fizica-ff/binding.json";
    private static final String ROLE_ARTICLES = "fizica-articles-author";       // I = ΣAIS/Nef
    private static final String ROLE_PRINCIPAL = "fizica-articles-principal";    // P = ΣAIS (first-or-corresponding)
    private static final String ROLE_CITATIONS = "fizica-c";                     // C = Σcᵢ/Nefᵢ (IF-journal cites, self-excl)
    private static final String ROLE_HINDEX = "fizica-h";                        // h = WoS Hirsch (indicative, H67)
    /** A1–A10 didactic + patents/projects activity blocks (totals keyed by block id in the snapshot). */
    private static final List<String> A_BLOCKS =
            List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10");
    /** Synthetic total key for the didactic subtotal A = ΣAᵢ. */
    private static final String TOTAL_A = "fizica-A";
    /** Synthetic total key for the composite T = A + P/2 + I/2 + C/20 + h/5. */
    private static final String TOTAL_T = "fizica-T";

    private final TemplateBindingLoader bindingLoader;
    private final TemplateDocxRenderer renderer;
    private TemplateBinding binding;

    public Fizica2024ReportTypeImportSupport(TemplateBindingLoader bindingLoader, TemplateDocxRenderer renderer) {
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
        for (SnapshotItem item : snapshot.getItems()) {
            if (item instanceof PublicationSnapshotItem pub && ROLE_ARTICLES.equals(pub.getRoleKey())) {
                rowsByRole.computeIfAbsent(ROLE_ARTICLES, k -> new ArrayList<>()).add(articleRow(pub));
            } else if (item instanceof PublicationSnapshotItem pub && ROLE_PRINCIPAL.equals(pub.getRoleKey())) {
                rowsByRole.computeIfAbsent(ROLE_PRINCIPAL, k -> new ArrayList<>()).add(principalRow(pub));
            } else if (item instanceof ActivitySnapshotItem act && act.getRoleKey() != null
                    && act.getRoleKey().startsWith("fizica-a")) {
                // A1–A10 activities (didactic A1–A6, patents A7/A8, projects A9/A10): one row per scored entry,
                // grouped to its A-block by activityName.
                rowsByRole.computeIfAbsent(act.getRoleKey(), k -> new ArrayList<>()).add(act.toRowMap());
            }
        }

        Map<String, Double> totals = new LinkedHashMap<>(snapshot.getTotals());
        double i = totals.getOrDefault(ROLE_ARTICLES, 0.0);
        double p = totals.getOrDefault(ROLE_PRINCIPAL, 0.0);
        // A = ΣA₁..A₁₀ (block totals keyed A1..A10).
        double a = A_BLOCKS.stream().mapToDouble(b -> totals.getOrDefault(b, 0.0)).sum();
        // C = Σcᵢ/Nefᵢ (the fizica-c indicator total); h = WoS Hirsch (the fizica-h indicator total). Both come from
        // the snapshot totals keyed by role; absent → 0 (e.g. a Lector row with no C/h requirement).
        double c = totals.getOrDefault(ROLE_CITATIONS, 0.0);
        double h = totals.getOrDefault(ROLE_HINDEX, 0.0);
        totals.put(TOTAL_A, a);
        // Composite T = A + P/2 + I/2 + C/20 + h/5.
        totals.put(TOTAL_T, a + p / 2.0 + i / 2.0 + c / 20.0 + h / 5.0);

        return renderer.render(binding, rowsByRole, totals);
    }

    @Override
    public List<SnapshotItem> parse(InputStream input, ReportFormat format) {
        throw new UnsupportedOperationException("Import not supported for " + REPORT_TYPE_KEY);
    }

    /** 2.1 row: Referinţa | AISᵢ | nᵢ | nᵢᵉᶠ | AISᵢ/nᵢᵉᶠ. */
    private Map<String, Object> articleRow(PublicationSnapshotItem pub) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("publication.reference", composePublication(pub.getAuthors(), pub.getTitle(),
                pub.getForumName(), pub.getVolumeInfo(), pub.getYear()));
        row.put("publication.forumScore", pub.getForumScore());                       // AISᵢ
        row.put("publication.authorCount", pub.getAuthorCount());                     // nᵢ
        row.put("publication.nef", pub.getAuthorCount() == null ? null
                : EffectiveAuthorCountSupport.computeNef(pub.getAuthorCount()));       // nᵢᵉᶠ
        row.put("publication.authorScore", pub.getAuthorScore());                     // AISᵢ/nᵢᵉᶠ
        return row;
    }

    /** 2.2 row: Referinţa | AISᵢ (P scores ΣAIS, so authorScore = AIS, no Nef divisor). */
    private Map<String, Object> principalRow(PublicationSnapshotItem pub) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("publication.reference", composePublication(pub.getAuthors(), pub.getTitle(),
                pub.getForumName(), pub.getVolumeInfo(), pub.getYear()));
        row.put("publication.authorScore", pub.getAuthorScore());                     // AISᵢ
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
