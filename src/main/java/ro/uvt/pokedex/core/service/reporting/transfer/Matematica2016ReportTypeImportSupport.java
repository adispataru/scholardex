package ro.uvt.pokedex.core.service.reporting.transfer;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.transfer.ActivitySnapshotItem;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Report-type support for the FV Matematică DOCX fișă (no-formula template). Export-only: the
 * Word template carries no formulas, so the renderer writes every final value — per-publication
 * si/ni rows plus the S/Srecent/C/Cx totals (sourced from the run's dedicated indicators via
 * {@code ReportInstanceSnapshot.totals}). Import/verify is out of scope for this slice.
 */
@Component
public class Matematica2016ReportTypeImportSupport implements ReportTypeImportSupport {

    public static final String REPORT_TYPE_KEY = "matematica-2016";
    private static final String BINDING_RESOURCE = "report-templates/matematica-2016/binding.json";

    private final TemplateBindingLoader bindingLoader;
    private final TemplateDocxRenderer renderer;
    private TemplateBinding binding;

    public Matematica2016ReportTypeImportSupport(TemplateBindingLoader bindingLoader, TemplateDocxRenderer renderer) {
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

        // Titles covered by the "recent" indicator — used to mark the "Publicat în ultimii 7 ani"
        // column. The recent role's items are NOT listed as separate rows (the main publications
        // role already lists every publication once).
        Set<String> recentTitles = new HashSet<>();
        for (SnapshotItem item : snapshot.getItems()) {
            if (item instanceof PublicationSnapshotItem p
                    && "journal-publications-recent".equals(p.getRoleKey()) && p.getTitle() != null) {
                recentTitles.add(p.getTitle());
            }
        }

        Map<String, List<Map<String, Object>>> rowsByRole = new LinkedHashMap<>();
        Set<String> seenPublicationTitles = new HashSet<>();
        for (SnapshotItem item : snapshot.getItems()) {
            if (item instanceof PublicationSnapshotItem pub && "journal-publications".equals(pub.getRoleKey())) {
                if (pub.getTitle() != null && !seenPublicationTitles.add(pub.getTitle())) continue; // each pub once
                rowsByRole.computeIfAbsent("journal-publications", k -> new ArrayList<>())
                        .add(publicationRow(pub, recentTitles.contains(pub.getTitle())));
            } else if (item instanceof CitationSnapshotItem cit && "citations-per-publication".equals(cit.getRoleKey())) {
                // Only list cited publications that actually received (counted) citations; the
                // citation indicator emits a row per cited publication, including ones with zero
                // citations, which would otherwise be empty noise rows.
                boolean hasCitings = cit.getCitingPublications() != null && !cit.getCitingPublications().isEmpty();
                if (!hasCitings) continue;
                rowsByRole.computeIfAbsent("citations-per-publication", k -> new ArrayList<>()).add(citationRow(cit));
            } else if (item instanceof ActivitySnapshotItem act
                    && "indicatori-suplimentari-uvt".equals(act.getRoleKey())) {
                // One row per scored entry in a C1..Cx_UVT block; grouped to its block by activityName
                // in the renderer. The block's placeholder line ("Carte 1 : …") is replaced by the
                // entry's description.
                rowsByRole.computeIfAbsent("indicatori-suplimentari-uvt", k -> new ArrayList<>())
                        .add(act.toRowMap());
            }
        }
        return renderer.render(binding, rowsByRole, snapshot.getTotals());
    }

    @Override
    public List<SnapshotItem> parse(InputStream input, ReportFormat format) {
        throw new UnsupportedOperationException("Import not supported for " + REPORT_TYPE_KEY);
    }

    /**
     * FV Matematică C4_UVT "Publicaţii" rows follow the fișă's order: Autori, titlul articol,
     * revista, vol. (anul) — the same shape as the main journal-publications reference cell, so we
     * reuse {@link #composeReference}. (Pages aren't carried in the snapshot.)
     */
    @Override
    public String formatBlockPublicationDescription(PublicationSnapshotItem pub) {
        return composeReference(pub);
    }

    private Map<String, Object> publicationRow(PublicationSnapshotItem pub, boolean recent) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("publication.reference", composeReference(pub));
        row.put("publication.recentFlag", recent ? "X" : "");
        // si = the journal score BEFORE the per-author division; si/ni = authorScore (the indicator
        // already divides by author count).
        row.put("publication.forumScore", pub.getForumScore());
        row.put("publication.authorCount", pub.getAuthorCount());
        row.put("publication.authorScore", pub.getAuthorScore());
        return row;
    }

    private Map<String, Object> citationRow(CitationSnapshotItem cit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("citation.citedTitle", cit.getPublicationTitle());
        row.put("citation.citingList", composeCiting(cit));
        row.put("citation.score", cit.getScore());
        return row;
    }

    private String composeReference(PublicationSnapshotItem pub) {
        List<String> parts = new ArrayList<>();
        if (notBlank(pub.getAuthors())) parts.add(pub.getAuthors());
        if (notBlank(pub.getTitle())) parts.add(pub.getTitle());
        if (notBlank(pub.getForumName())) parts.add(pub.getForumName());
        String tail = "";
        if (notBlank(pub.getVolumeInfo())) tail += pub.getVolumeInfo();
        if (pub.getYear() != null) tail += (tail.isEmpty() ? "" : " ") + "(" + pub.getYear() + ")";
        if (!tail.isEmpty()) parts.add(tail);
        return String.join(", ", parts);
    }

    private String composeCiting(CitationSnapshotItem cit) {
        if (cit.getCitingPublications() == null) return "";
        return cit.getCitingPublications().stream()
                .map(c -> {
                    String s = c.getTitle() == null ? "" : c.getTitle();
                    String meta = "";
                    if (notBlank(c.getForumName())) meta += c.getForumName();
                    if (c.getYear() != null) meta += (meta.isEmpty() ? "" : ", ") + c.getYear();
                    return meta.isEmpty() ? s : s + " (" + meta + ")";
                })
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("; "));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
