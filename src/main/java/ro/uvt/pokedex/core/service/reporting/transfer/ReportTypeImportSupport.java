package ro.uvt.pokedex.core.service.reporting.transfer;

import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportImportItem;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportInstanceSnapshot;
import ro.uvt.pokedex.core.model.reporting.transfer.SnapshotItem;

import java.io.InputStream;
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

    ReportImportItem reconcile(SnapshotItem item);

    CommitResult commit(ReportImportItem item, String userEmail);
}
