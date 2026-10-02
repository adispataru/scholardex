package ro.uvt.pokedex.core.service.reporting.transfer;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.transfer.ActivitySnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportInstanceSnapshot;
import ro.uvt.pokedex.core.model.reporting.transfer.SnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingKind;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingRole;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding;
import ro.uvt.pokedex.core.service.reporting.transfer.binding.TemplateBindingLoader;
import ro.uvt.pokedex.core.service.reporting.transfer.parse.TemplateXlsxScoreParser;
import ro.uvt.pokedex.core.service.reporting.transfer.render.TemplateXlsxRenderer;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * H142 slice 2 — FV Muzică 2026 (Comisia 35) in the faculty's own fișă de verificare: the three tables (DID, CS,
 * RIA), each row listing the candidate's items in one cell with the row's points beside it, the way the faculty's
 * colleagues fill it. One {@code ITEMS_IN_CELL} role per table; each block is a row of the standard ("DID 1.1"),
 * fed by the indicators the report maps to it. The template is built from the official text, without personal
 * data; its total rows sum the points.
 */
@Component
public class Muzica2026ReportTypeImportSupport implements ReportTypeImportSupport {

    public static final String REPORT_TYPE_KEY = "muzica-2026";
    private static final String BINDING_RESOURCE = "report-templates/muzica-2026/binding.json";

    private final TemplateBindingLoader bindingLoader;
    private final TemplateXlsxRenderer renderer;
    private final TemplateXlsxScoreParser scoreParser;
    private TemplateBinding binding;

    public Muzica2026ReportTypeImportSupport(TemplateBindingLoader bindingLoader,
                                             TemplateXlsxRenderer renderer,
                                             TemplateXlsxScoreParser scoreParser) {
        this.bindingLoader = bindingLoader;
        this.renderer = renderer;
        this.scoreParser = scoreParser;
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
        return binding.getRoles().stream().map(BindingRole::getRoleKey).toList();
    }

    @Override
    public Map<String, List<String>> declaredBlocksByRole() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (BindingRole role : binding.getRoles()) {
            if (role.getKind() == BindingKind.ITEMS_IN_CELL) {
                out.put(role.getRoleKey(), role.getBlocks().stream().map(b -> b.getActivityName()).toList());
            }
        }
        return out;
    }

    @Override
    public TemplateBinding binding() {
        return binding;
    }

    @Override
    public Set<ReportFormat> supportedExportFormats() {
        return Set.of(ReportFormat.XLSX);
    }

    @Override
    public Set<ReportFormat> supportedImportFormats() {
        return Set.of(ReportFormat.XLSX);
    }

    @Override
    public byte[] render(ReportInstanceSnapshot snapshot, ReportFormat format) {
        if (format != ReportFormat.XLSX) {
            throw new IllegalArgumentException("Unsupported export format: " + format);
        }
        Map<String, List<Map<String, Object>>> rowsByRole = new LinkedHashMap<>();
        for (SnapshotItem item : snapshot.getItems()) {
            if (item instanceof ActivitySnapshotItem act && act.getRoleKey() != null) {
                rowsByRole.computeIfAbsent(act.getRoleKey(), k -> new ArrayList<>()).add(act.toRowMap());
            }
        }
        return renderer.render(binding, rowsByRole, Map.of(), snapshot.getTotals());
    }

    /** "Authors, title, journal, volume (year), DOI" — the way the grid's colleagues list their articles and books. */
    @Override
    public String formatBlockPublicationDescription(PublicationSnapshotItem pub) {
        List<String> parts = new ArrayList<>();
        if (pub.getAuthors() != null && !pub.getAuthors().isBlank()) parts.add(pub.getAuthors());
        if (pub.getTitle() != null && !pub.getTitle().isBlank()) parts.add(pub.getTitle());
        if (pub.getForumName() != null && !pub.getForumName().isBlank()) parts.add(pub.getForumName());
        String main = String.join(", ", parts);
        if (pub.getVolumeInfo() != null && !pub.getVolumeInfo().isBlank()) main += ", " + pub.getVolumeInfo();
        if (pub.getYear() != null) main += " (" + pub.getYear() + ")";
        if (pub.getDoi() != null && !pub.getDoi().isBlank()) main += ", DOI " + pub.getDoi();
        return main;
    }

    @Override
    public List<SnapshotItem> parse(InputStream input, ReportFormat format) {
        if (format != ReportFormat.XLSX) {
            throw new IllegalArgumentException("Unsupported import format: " + format);
        }
        return scoreParser.parse(binding, input);
    }

    @Override
    public List<SnapshotItem> parse(InputStream input, ReportFormat format, List<String> layoutWarnings) {
        if (format != ReportFormat.XLSX) {
            throw new IllegalArgumentException("Unsupported import format: " + format);
        }
        return scoreParser.parse(binding, input, layoutWarnings);
    }
}
