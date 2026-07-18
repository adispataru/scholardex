package ro.uvt.pokedex.core.service.reporting.transfer;

import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportInstanceSnapshot;
import ro.uvt.pokedex.core.model.reporting.transfer.SnapshotItem;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public interface ReportTypeImportSupport {

    String reportTypeKey();

    /**
     * Role keys declared by this support's binding (e.g. "journal-publications"). Used by the
     * admin UI to populate per-indicator role-key pickers.
     */
    List<String> declaredRoles();

    /**
     * Block names (i.e. {@code BindingBlock.activityName}) for each STACKED_BLOCKS role keyed by
     * roleKey. Empty for supports without any STACKED_BLOCKS role. Used by the admin UI to render
     * the per-block indicator picker.
     */
    java.util.Map<String, List<String>> declaredBlocksByRole();

    /**
     * The parsed template binding this support drives. Exposed so the snapshot builder can walk
     * binding-level structures (e.g. blocks) without re-parsing the JSON.
     */
    ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding binding();

    Set<ReportFormat> supportedExportFormats();

    Set<ReportFormat> supportedImportFormats();

    byte[] render(ReportInstanceSnapshot snapshot, ReportFormat format);

    List<SnapshotItem> parse(InputStream input, ReportFormat format);

    /**
     * Parse variant that also reports layout deviations (e.g. a researcher-restructured template
     * whose columns are shifted): one human-readable note per deviated sheet is appended to
     * {@code layoutWarnings}. Default delegates to {@link #parse(InputStream, ReportFormat)} and
     * reports nothing; supports backed by the template parser override it.
     */
    default List<SnapshotItem> parse(InputStream input, ReportFormat format, List<String> layoutWarnings) {
        return parse(input, format);
    }

    /**
     * Formats the one-line description for a publication-fed STACKED_BLOCKS item (e.g. the C4_UVT
     * "Publicaţii" rows). The default order is {@code Title — Authors — Forum, vol (year)}; report
     * types whose template fixes a different order (e.g. FV Matematică's "Autori, titlu, revistă,
     * vol. (anul)") override this. Called by the block projectors at projection time so the rendered
     * snapshot already carries the per-report wording.
     */
    default String formatBlockPublicationDescription(PublicationSnapshotItem pub) {
        List<String> parts = new ArrayList<>();
        if (notBlank(pub.getTitle())) parts.add(pub.getTitle());
        if (notBlank(pub.getAuthors())) parts.add(pub.getAuthors());
        if (notBlank(pub.getForumName())) parts.add(pub.getForumName());
        String main = String.join(" — ", parts);
        if (notBlank(pub.getVolumeInfo())) main += ", " + pub.getVolumeInfo();
        if (pub.getYear() != null) main += " (" + pub.getYear() + ")";
        return main;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
