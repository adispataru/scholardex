package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import jakarta.servlet.http.HttpSession;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.UploadAcceptanceResult;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.ScopusUploadRunResult;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.UploadedPayload;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.WosIncrementalUploadRequest;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.WosUploadRunResult;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.WosUploadSourceType;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/incremental-updates")
@RequiredArgsConstructor
@Slf4j
public class AdminIncrementalUpdatesController {

    private static final String SESSION_SCOPUS_UPLOAD_BATCH_ID = "h29.scopus.lastUploadBatchId";
    private static final String SESSION_SCOPUS_UPLOAD_FILENAME = "h29.scopus.lastUploadFilename";

    private final IncrementalUpdateUploadFacade incrementalUpdateUploadFacade;

    @Value("${h29.incremental-upload.max-bytes:262144000}")
    private long maxUploadBytes;

    @Value("${h29.incremental-upload.wos-json.allowed-content-types:application/json,text/json,text/plain,application/octet-stream}")
    private String wosJsonAllowedContentTypes;

    @Value("${h29.incremental-upload.wos-excel.allowed-content-types:application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel,application/octet-stream}")
    private String wosExcelAllowedContentTypes;

    @Value("${h29.incremental-upload.scopus-json.allowed-content-types:application/json,text/json,text/plain,application/octet-stream}")
    private String scopusJsonAllowedContentTypes;

    @GetMapping
    public String showIncrementalUpdatesPage(Model model, HttpSession session) {
        model.addAttribute("scopusFollowUpContext", readScopusFollowUpContext(session));
        return "admin/incremental-updates";
    }

    @PostMapping("/wos")
    public String uploadWosIncrementalUpdate(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "sourceType", required = false) String sourceType,
            @RequestParam(name = "sourceVersion", required = false) String sourceVersion,
            RedirectAttributes redirectAttributes
    ) {
        WosUploadSourceType parsedSourceType;
        try {
            parsedSourceType = parseWosSourceType(sourceType);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/admin/incremental-updates";
        }

        if (rejectMissingOrOversizedFile(file, redirectAttributes)) {
            return "redirect:/admin/incremental-updates";
        }
        if (!hasAllowedExtension(file.getOriginalFilename(), parsedSourceType)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    parsedSourceType == WosUploadSourceType.OFFICIAL_JSON
                            ? "WoS official JSON uploads require a .json file."
                            : "WoS government Excel uploads require a .xls or .xlsx file."
            );
            return "redirect:/admin/incremental-updates";
        }
        if (!hasAllowedContentType(file.getContentType(), allowedWosContentTypes(parsedSourceType))) {
            redirectAttributes.addFlashAttribute("errorMessage", "Unsupported content type for the selected WoS upload source.");
            return "redirect:/admin/incremental-updates";
        }

        try {
            UploadedPayload payload = readPayload(file);
            WosUploadRunResult result = incrementalUpdateUploadFacade.acceptWosUpload(
                    new WosIncrementalUploadRequest(parsedSourceType, normalizeOptional(sourceVersion), payload)
            );
            redirectAttributes.addFlashAttribute("successMessage", formatWosSuccessMessage(result));
        } catch (IOException e) {
            log.error("WoS incremental upload read failed: fileName={}, size={}", file.getOriginalFilename(), file.getSize(), e);
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to read the uploaded WoS file.");
        } catch (IllegalArgumentException e) {
            log.error("WoS incremental upload validation failed: fileName={}, sourceType={}", file.getOriginalFilename(), parsedSourceType, e);
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        } catch (RuntimeException e) {
            log.error("WoS incremental upload orchestration failed: fileName={}, sourceType={}", file.getOriginalFilename(), parsedSourceType, e);
            redirectAttributes.addFlashAttribute("errorMessage", "WoS incremental update failed: " + e.getMessage());
        }
        return "redirect:/admin/incremental-updates";
    }

    @PostMapping("/scopus")
    public String uploadScopusIncrementalUpdate(
            @RequestParam("file") MultipartFile file,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        if (rejectMissingOrOversizedFile(file, redirectAttributes)) {
            return "redirect:/admin/incremental-updates";
        }
        if (!hasJsonExtension(file.getOriginalFilename())) {
            redirectAttributes.addFlashAttribute("errorMessage", "Scopus incremental uploads require a .json file.");
            return "redirect:/admin/incremental-updates";
        }
        if (!hasAllowedContentType(file.getContentType(), parseAllowedContentTypes(scopusJsonAllowedContentTypes))) {
            redirectAttributes.addFlashAttribute("errorMessage", "Unsupported content type for Scopus JSON upload.");
            return "redirect:/admin/incremental-updates";
        }

        try {
            UploadedPayload payload = readPayload(file);
            ScopusUploadRunResult result = incrementalUpdateUploadFacade.acceptScopusUpload(payload);
            storeScopusFollowUpContext(session, result);
            redirectAttributes.addFlashAttribute("successMessage", formatScopusSuccessMessage(result));
        } catch (IOException e) {
            log.error("Scopus incremental upload read failed: fileName={}, size={}", file.getOriginalFilename(), file.getSize(), e);
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to read the uploaded Scopus file.");
        } catch (IllegalArgumentException e) {
            log.error("Scopus incremental upload validation failed: fileName={}", file.getOriginalFilename(), e);
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Scopus incremental upload orchestration failed: fileName={}", file.getOriginalFilename(), e);
            redirectAttributes.addFlashAttribute("errorMessage", "Scopus incremental update failed: " + e.getMessage());
        }
        return "redirect:/admin/incremental-updates";
    }

    @PostMapping("/scopus/buildProjections")
    public String rebuildScopusUploadProjections(
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        ScopusFollowUpContext context = requireScopusFollowUpContext(session, redirectAttributes);
        if (context == null) {
            return "redirect:/admin/incremental-updates";
        }
        try {
            var result = incrementalUpdateUploadFacade.rebuildScopusUploadProjections(context.uploadBatchId());
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Scopus projection rebuild complete for last uploaded batch [" + context.originalFilename() + "]. "
                            + "Projections[p=" + (result == null ? 0 : result.processed())
                            + ", i=" + (result == null ? 0 : result.imported())
                            + ", u=" + (result == null ? 0 : result.updated())
                            + ", s=" + (result == null ? 0 : result.skipped())
                            + ", e=" + (result == null ? 0 : result.errors())
                            + "]."
            );
        } catch (RuntimeException e) {
            log.error("Scopus incremental projection rebuild failed: fileName={}, batchId={}", context.originalFilename(), context.uploadBatchId(), e);
            redirectAttributes.addFlashAttribute("errorMessage", "Scopus projection rebuild failed: " + e.getMessage());
        }
        return "redirect:/admin/incremental-updates";
    }

    @PostMapping("/scopus/reconcileEdges")
    public String reconcileScopusUploadEdges(
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        ScopusFollowUpContext context = requireScopusFollowUpContext(session, redirectAttributes);
        if (context == null) {
            return "redirect:/admin/incremental-updates";
        }
        try {
            var result = incrementalUpdateUploadFacade.reconcileScopusUploadEdges(context.uploadBatchId());
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Scopus edge reconcile complete for last uploaded batch [" + context.originalFilename() + "]. "
                            + "Edges[p=" + result.getProcessedCount()
                            + ", u=" + result.getUpdatedCount()
                            + ", s=" + result.getSkippedCount()
                            + ", e=" + result.getErrorCount()
                            + "]."
            );
        } catch (RuntimeException e) {
            log.error("Scopus incremental edge reconcile failed: fileName={}, batchId={}", context.originalFilename(), context.uploadBatchId(), e);
            redirectAttributes.addFlashAttribute("errorMessage", "Scopus edge reconcile failed: " + e.getMessage());
        }
        return "redirect:/admin/incremental-updates";
    }

    @PostMapping("/scopus/reconcileSourceLinks")
    public String repairScopusSourceLinks(
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        ScopusFollowUpContext context = requireScopusFollowUpContext(session, redirectAttributes);
        if (context == null) {
            return "redirect:/admin/incremental-updates";
        }
        try {
            var result = incrementalUpdateUploadFacade.repairScopusSourceLinks();
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Scopus source-link ledger repair complete after last uploaded batch [" + context.originalFilename() + "]. "
                            + "Repair[updated=" + result.updated()
                            + ", skipped=" + result.skipped()
                            + ", errors=" + result.errors()
                            + "]. This advanced action repairs ledger state globally and is not part of the routine post-upload path."
            );
        } catch (RuntimeException e) {
            log.error("Scopus source-link ledger repair failed after incremental upload: fileName={}, batchId={}", context.originalFilename(), context.uploadBatchId(), e);
            redirectAttributes.addFlashAttribute("errorMessage", "Scopus source-link ledger repair failed: " + e.getMessage());
        }
        return "redirect:/admin/incremental-updates";
    }

    private boolean rejectMissingOrOversizedFile(MultipartFile file, RedirectAttributes redirectAttributes) {
        if (file == null || file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please select a file to upload.");
            return true;
        }
        if (file.getSize() > maxUploadBytes) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Uploaded file is too large. Maximum allowed size is " + (maxUploadBytes / (1024 * 1024)) + " MB."
            );
            return true;
        }
        return false;
    }

    private WosUploadSourceType parseWosSourceType(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("Please choose a WoS source type.");
        }
        try {
            return WosUploadSourceType.valueOf(sourceType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid WoS source type.");
        }
    }

    private boolean hasAllowedExtension(String fileName, WosUploadSourceType sourceType) {
        return sourceType == WosUploadSourceType.OFFICIAL_JSON
                ? hasJsonExtension(fileName)
                : hasExcelExtension(fileName);
    }

    private boolean hasJsonExtension(String fileName) {
        return normalizedFileName(fileName).endsWith(".json");
    }

    private boolean hasExcelExtension(String fileName) {
        String normalized = normalizedFileName(fileName);
        return normalized.endsWith(".xls") || normalized.endsWith(".xlsx");
    }

    private String normalizedFileName(String fileName) {
        return fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
    }

    private Set<String> allowedWosContentTypes(WosUploadSourceType sourceType) {
        return sourceType == WosUploadSourceType.OFFICIAL_JSON
                ? parseAllowedContentTypes(wosJsonAllowedContentTypes)
                : parseAllowedContentTypes(wosExcelAllowedContentTypes);
    }

    private boolean hasAllowedContentType(String contentType, Set<String> allowedContentTypes) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        return allowedContentTypes.contains(contentType.trim().toLowerCase(Locale.ROOT));
    }

    private Set<String> parseAllowedContentTypes(String configuredValues) {
        return Arrays.stream(configuredValues.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private UploadedPayload readPayload(MultipartFile file) throws IOException {
        return new UploadedPayload(
                file.getOriginalFilename(),
                normalizeOptional(file.getContentType()),
                file.getBytes()
        );
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String formatSuccessMessage(UploadAcceptanceResult result) {
        return result.sourceLabel() + " incremental upload accepted for " + result.originalFilename() + ". " + result.summary();
    }

    private String formatWosSuccessMessage(WosUploadRunResult result) {
        return "WoS incremental upload complete for " + result.originalFilename()
                + " [sourceType=" + result.sourceType()
                + ", sourceVersion=" + result.effectiveSourceVersion()
                + "]. Ingest[p=" + result.ingest().getProcessedCount()
                + ", i=" + result.ingest().getImportedCount()
                + ", u=" + result.ingest().getUpdatedCount()
                + ", s=" + result.ingest().getSkippedCount()
                + ", e=" + result.ingest().getErrorCount()
                + "] Facts[p=" + result.factBuild().result().getProcessedCount()
                + ", i=" + result.factBuild().result().getImportedCount()
                + ", u=" + result.factBuild().result().getUpdatedCount()
                + ", s=" + result.factBuild().result().getSkippedCount()
                + ", e=" + result.factBuild().result().getErrorCount()
                + ", resumedFromCheckpoint=" + result.factBuild().resumedFromCheckpoint()
                + "]. " + result.note();
    }

    private String formatScopusSuccessMessage(ScopusUploadRunResult result) {
        return "Scopus incremental upload complete for " + result.originalFilename()
                + ". Publications[p=" + result.publicationIngest().getProcessedCount()
                + ", i=" + result.publicationIngest().getImportedCount()
                + ", u=" + result.publicationIngest().getUpdatedCount()
                + ", s=" + result.publicationIngest().getSkippedCount()
                + ", e=" + result.publicationIngest().getErrorCount()
                + "] Citations[p=" + result.citationIngest().getProcessedCount()
                + ", i=" + result.citationIngest().getImportedCount()
                + ", u=" + result.citationIngest().getUpdatedCount()
                + ", s=" + result.citationIngest().getSkippedCount()
                + ", e=" + result.citationIngest().getErrorCount()
                + "] Ingest[p=" + result.ingestCombined().processed()
                + ", i=" + result.ingestCombined().imported()
                + ", u=" + result.ingestCombined().updated()
                + ", s=" + result.ingestCombined().skipped()
                + ", e=" + result.ingestCombined().errors()
                + "] Facts[p=" + (result.buildFacts() == null ? 0 : result.buildFacts().processed())
                + ", i=" + (result.buildFacts() == null ? 0 : result.buildFacts().imported())
                + ", u=" + (result.buildFacts() == null ? 0 : result.buildFacts().updated())
                + ", s=" + (result.buildFacts() == null ? 0 : result.buildFacts().skipped())
                + ", e=" + (result.buildFacts() == null ? 0 : result.buildFacts().errors())
                + "]. " + result.note();
    }

    private void storeScopusFollowUpContext(HttpSession session, ScopusUploadRunResult result) {
        session.setAttribute(SESSION_SCOPUS_UPLOAD_BATCH_ID, result.uploadBatchId());
        session.setAttribute(SESSION_SCOPUS_UPLOAD_FILENAME, result.originalFilename());
    }

    private ScopusFollowUpContext readScopusFollowUpContext(HttpSession session) {
        Object batchId = session.getAttribute(SESSION_SCOPUS_UPLOAD_BATCH_ID);
        Object fileName = session.getAttribute(SESSION_SCOPUS_UPLOAD_FILENAME);
        if (!(batchId instanceof String batchIdValue) || batchIdValue.isBlank()) {
            return null;
        }
        String originalFilename = fileName instanceof String fileNameValue && !fileNameValue.isBlank()
                ? fileNameValue
                : "unknown";
        return new ScopusFollowUpContext(originalFilename, batchIdValue);
    }

    private ScopusFollowUpContext requireScopusFollowUpContext(HttpSession session, RedirectAttributes redirectAttributes) {
        ScopusFollowUpContext context = readScopusFollowUpContext(session);
        if (context == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "No successful Scopus upload is stored in this session yet. Upload a Scopus JSON file first before running post-upload maintenance."
            );
        }
        return context;
    }

    public record ScopusFollowUpContext(
            String originalFilename,
            String uploadBatchId
    ) {
    }
}
