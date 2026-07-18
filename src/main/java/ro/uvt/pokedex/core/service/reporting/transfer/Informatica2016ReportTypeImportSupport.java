package ro.uvt.pokedex.core.service.reporting.transfer;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.transfer.ActivitySnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportInstanceSnapshot;
import ro.uvt.pokedex.core.model.reporting.transfer.SnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding;
import ro.uvt.pokedex.core.service.reporting.transfer.binding.TemplateBindingLoader;
import ro.uvt.pokedex.core.service.reporting.transfer.parse.TemplateXlsxScoreParser;
import ro.uvt.pokedex.core.service.reporting.transfer.render.TemplateXlsxRenderer;
import ro.uvt.pokedex.core.service.reporting.transfer.render.TileData;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class Informatica2016ReportTypeImportSupport implements ReportTypeImportSupport {

    public static final String REPORT_TYPE_KEY = "informatica-2016";
    private static final String BINDING_RESOURCE = "report-templates/informatica-2016/binding.json";

    private final TemplateBindingLoader bindingLoader;
    private final TemplateXlsxRenderer renderer;
    private final TemplateXlsxScoreParser scoreParser;
    private TemplateBinding binding;

    public Informatica2016ReportTypeImportSupport(TemplateBindingLoader bindingLoader,
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
        return binding.getRoles().stream().map(r -> r.getRoleKey()).toList();
    }

    @Override
    public java.util.Map<String, List<String>> declaredBlocksByRole() {
        java.util.Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        for (var role : binding.getRoles()) {
            if (role.getKind() == ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingKind.STACKED_BLOCKS) {
                out.put(role.getRoleKey(), role.getBlocks().stream().map(b -> b.getActivityName()).toList());
            }
        }
        return out;
    }

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
        Map<String, List<TileData>> tilesByRole = new LinkedHashMap<>();

        for (SnapshotItem item : snapshot.getItems()) {
            if (item instanceof PublicationSnapshotItem pub && pub.getRoleKey() != null) {
                rowsByRole.computeIfAbsent(pub.getRoleKey(), k -> new ArrayList<>()).add(pub.toRowMap());
            } else if (item instanceof ActivitySnapshotItem act && act.getRoleKey() != null) {
                rowsByRole.computeIfAbsent(act.getRoleKey(), k -> new ArrayList<>()).add(act.toRowMap());
            } else if (item instanceof CitationSnapshotItem cit && cit.getRoleKey() != null) {
                tilesByRole.computeIfAbsent(cit.getRoleKey(), k -> new ArrayList<>())
                        .add(new TileData(cit.toHeaderMap(), cit.toInnerRowMaps()));
            }
        }

        return renderer.render(binding, rowsByRole, tilesByRole, snapshot.getTotals());
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
