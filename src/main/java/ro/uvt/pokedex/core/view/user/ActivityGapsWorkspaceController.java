package ro.uvt.pokedex.core.view.user;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.ActivityGapsFacade;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * H142 slice 7 — per record of the signed-in researcher, what it lacks to count, as text in the reader's language
 * (the activities panel's «Nu se punctează încă» filter).
 */
@RestController
@RequiredArgsConstructor
public class ActivityGapsWorkspaceController {

    private final ActivityGapsFacade gaps;
    private final MessageSource messages;

    @GetMapping("/user/workspace/activities/gaps")
    public ResponseEntity<Map<String, List<String>>> gaps(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            return ResponseEntity.status(401).build();
        }
        Locale locale = LocaleContextHolder.getLocale();
        Map<String, List<String>> out = new LinkedHashMap<>();
        gaps.gapsFor(user.getEmail()).forEach((recordId, list) -> out.put(recordId, list.stream()
                .map(g -> messages.getMessage("workspace.activities.gap." + g.gap().name(),
                        new Object[]{g.detail() == null ? "" : g.detail()}, g.gap().name(), locale))
                .toList()));
        return ResponseEntity.ok(out);
    }
}
