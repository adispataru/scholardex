package ro.uvt.pokedex.core.service.reporting.transfer.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.uvt.pokedex.core.controller.dto.ScholardexProjectListItemResponse;
import ro.uvt.pokedex.core.service.application.ScholardexProjectReadPort;

import java.util.ArrayList;
import java.util.List;

/**
 * H64 slice 3 — resolves a {@code PROJECT_GRANT_ID} reference (a {@code sproj_…} canonical id) to a
 * trusted label {@code code — title (funder) — Director: First Last}. Falls back to the raw value
 * when unresolved (the project view not yet populated, or the reference is free text predating the
 * picker) so callers never break on a bad reference.
 *
 * <p>Stateless on purpose: {@link ActivityBlockProjector} (report export/import) and {@code
 * UserReportFacade} (evidence-panel drilldown, {@code IndicatorDetailResponseAssembler}) both need
 * this exact resolution so the two surfaces describe the same activity identically, but {@code
 * ActivityBlockProjector} depends on {@code UserIndicatorResultService} which itself depends on
 * {@code UserReportFacade} — injecting the projector into the facade would close that cycle. Each
 * caller instead injects its own {@link ScholardexProjectReadPort} and calls this static helper.
 */
public final class ProjectLabelResolver {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectLabelResolver.class);

    private ProjectLabelResolver() {
    }

    public static String resolve(ScholardexProjectReadPort scholardexProjectReadPort, String reference) {
        ScholardexProjectListItemResponse project;
        try {
            project = scholardexProjectReadPort.findById(reference);
        } catch (RuntimeException ex) {
            LOG.warn("Project reference resolution failed for {} — using raw value", reference, ex);
            return reference;
        }
        if (project == null) {
            return reference;
        }
        List<String> head = new ArrayList<>();
        if (project.code() != null && !project.code().isBlank()) head.add(project.code());
        if (project.title() != null && !project.title().isBlank()) head.add(project.title());
        String label = head.isEmpty() ? reference : String.join(" — ", head);
        if (project.funder() != null && !project.funder().isBlank()) {
            label = label + " (" + project.funder() + ")";
        }
        if (project.director() != null && !project.director().isBlank()) {
            label = label + " — Director: " + project.director();
        }
        return label;
    }
}
