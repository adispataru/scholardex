package ro.uvt.pokedex.core.view.user;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.model.scopus.canonical.PrincipalAuthorDeclaration;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.PrincipalAuthorDeclarationService;
import ro.uvt.pokedex.core.service.application.PrincipalAuthorDeclarationService.DeclarationException;

import java.util.Map;
import java.util.Optional;

/**
 * The researcher's side of the principal-authorship declarations, for the publications tab of the workspace.
 * Own controller on purpose — adding a constructor arg to the workspace controller takes its whole
 * {@code @WebMvcTest} slice down. A refusal is answered with its code; the page says it in words.
 */
@RestController
@RequestMapping("/user/workspace/publications/principal-author")
@RequiredArgsConstructor
public class PrincipalAuthorDeclarationWorkspaceController {

    private final PrincipalAuthorDeclarationService declarations;

    public record DeclareRequest(String publicationId, String kind, String evidence, String evidenceUrl) {}

    public record WithdrawRequest(String publicationId) {}

    @GetMapping("/state")
    public ResponseEntity<PrincipalAuthorDeclarationService.WorkspaceState> state(Authentication authentication) {
        return currentUser(authentication)
                .map(user -> ResponseEntity.ok(declarations.stateFor(user.getEmail())))
                .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> declare(@RequestBody DeclareRequest request,
                                                       Authentication authentication) {
        Optional<User> user = currentUser(authentication);
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            PrincipalAuthorDeclaration declaration = declarations.declare(user.get().getEmail(),
                    request.publicationId(), kindOf(request.kind()), request.evidence(), request.evidenceUrl());
            return ResponseEntity.ok(Map.of("status", declaration.getStatus().name(),
                    "kind", declaration.getKind().name()));
        } catch (DeclarationException refused) {
            return ResponseEntity.badRequest().body(Map.of("error", refused.refusal().name()));
        }
    }

    @PostMapping("/withdraw")
    public ResponseEntity<Map<String, String>> withdraw(@RequestBody WithdrawRequest request,
                                                        Authentication authentication) {
        Optional<User> user = currentUser(authentication);
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            PrincipalAuthorDeclaration declaration =
                    declarations.withdraw(user.get().getEmail(), request.publicationId());
            return ResponseEntity.ok(Map.of("status", declaration.getStatus().name()));
        } catch (DeclarationException refused) {
            return ResponseEntity.badRequest().body(Map.of("error", refused.refusal().name()));
        }
    }

    private static PrincipalAuthorDeclaration.Kind kindOf(String name) {
        if (name == null) {
            return null;
        }
        try {
            return PrincipalAuthorDeclaration.Kind.valueOf(name.trim());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static Optional<User> currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            return Optional.empty();
        }
        return Optional.of(user);
    }
}
