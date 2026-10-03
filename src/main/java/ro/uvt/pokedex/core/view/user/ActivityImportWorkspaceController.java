package ro.uvt.pokedex.core.view.user;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.ActivityFileImportService;
import ro.uvt.pokedex.core.service.application.ActivityReviewService;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * H142 slice 2 — the researcher imports their own fișă de verificare (or CNFIS Anexa 5.1) into their activities, and
 * reviews the imported records many at once. Own controller on purpose: a new constructor argument on the workspace
 * controller takes its whole {@code @WebMvcTest} slice down.
 */
@RestController
@RequestMapping("/user/workspace/activities")
@RequiredArgsConstructor
public class ActivityImportWorkspaceController {

    /** The largest file accepted: a filled grid is a few hundred kilobytes. */
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private final ActivityFileImportService importService;
    private final ActivityReviewService reviewService;

    /** {@code action}: SET_FIELDS (with {@code values}), MARK_REVIEWED or DELETE. */
    public record BulkRequest(List<String> ids, String action, Map<String, String> values) {}

    @PostMapping("/import-file")
    public ResponseEntity<?> importFile(@RequestParam("file") MultipartFile file, Authentication authentication) {
        Optional<User> user = currentUser(authentication);
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "EMPTY"));
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("error", "TOO_LARGE"));
        }
        String name = file.getOriginalFilename() == null ? "fișier" : file.getOriginalFilename();
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) {
            return ResponseEntity.badRequest().body(Map.of("error", "NOT_XLSX"));
        }
        try (InputStream in = file.getInputStream()) {
            ActivityFileImportService.ImportReport report = importService.importFile(user.get().getEmail(), name, in, null);
            return switch (report.kind()) {
                case MUSIC_GRID, CNFIS_ARTS, CNFIS_CITATIONS, CNFIS_ARTICLES -> ResponseEntity.ok(report);
                case INSTITUTIONAL_TABLE -> ResponseEntity.badRequest().body(Map.of("error", "INSTITUTIONAL_TABLE"));
                case UNSUPPORTED -> ResponseEntity.badRequest().body(Map.of("error", "UNSUPPORTED"));
            };
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "UNREADABLE"));
        }
    }

    @PostMapping("/bulk")
    public ResponseEntity<?> bulk(@RequestBody BulkRequest request, Authentication authentication) {
        Optional<User> user = currentUser(authentication);
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (request == null || request.ids() == null || request.ids().isEmpty() || request.action() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "NOTHING_SELECTED"));
        }
        if (request.ids().size() > 1000) {
            return ResponseEntity.badRequest().body(Map.of("error", "TOO_MANY"));
        }
        String email = user.get().getEmail();
        return switch (request.action()) {
            case "SET_FIELDS" -> ResponseEntity.ok(reviewService.setFields(email, request.ids(), request.values()));
            case "MARK_REVIEWED" -> ResponseEntity.ok(reviewService.markReviewed(email, request.ids()));
            case "DELETE" -> ResponseEntity.ok(reviewService.delete(email, request.ids()));
            default -> ResponseEntity.badRequest().body(Map.of("error", "UNKNOWN_ACTION"));
        };
    }

    private static Optional<User> currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            return Optional.empty();
        }
        return Optional.of(user);
    }
}
