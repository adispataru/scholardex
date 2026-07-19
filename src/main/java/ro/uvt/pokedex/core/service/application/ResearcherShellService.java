package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.service.UserService;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Creates a passwordless RESEARCHER account shell for a person who has no account yet, so a unit head
 * can put them on a roster before their first login. The person claims the shell on first UVT SSO
 * login — Keycloak matches by email ({@code findById(email).orElseGet(...)}), so a pre-created shell
 * is adopted, not clobbered.
 *
 * <p>Guardrails: institutional email domain only, RESEARCHER role only, no usable password (a random
 * bcrypt value blocks form login; SSO doesn't use it), and never overwrite an existing account.</p>
 */
@Service
@RequiredArgsConstructor
public class ResearcherShellService {

    /**
     * Linear-time email shape check: dot-separated domain labels may not contain dots, so the regex
     * has no ambiguous backtracking (the old {@code [^@\s]+\.[^@\s]+} form was polynomial on inputs
     * like {@code a@a.a.a...} — CodeQL java/polynomial-redos #31). Paired with the RFC 5321 length
     * cap below so even the linear scan is bounded.
     */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

    /** RFC 5321 maximum total length of an address. */
    private static final int MAX_EMAIL_LENGTH = 254;

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    /** Comma-separated institutional domains a head may invite (case-insensitive, no leading '@'). */
    @Value("${app.roster.invite-allowed-domains:e-uvt.ro}")
    private String allowedDomainsCsv;

    public enum Result { CREATED, INVALID_EMAIL, DOMAIN_NOT_ALLOWED, ALREADY_EXISTS }

    public Result createShell(String rawEmail) {
        String email = rawEmail == null ? "" : rawEmail.strip().toLowerCase();
        if (email.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(email).matches()) {
            return Result.INVALID_EMAIL;
        }
        if (!isAllowedDomain(email)) {
            return Result.DOMAIN_NOT_ALLOWED;
        }
        User shell = new User();
        shell.setEmail(email);
        shell.setRoles(Set.of(UserRole.RESEARCHER));
        // Random bcrypt so form login is impossible; SSO is the intended entry.
        shell.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        shell.setLocked(false);
        // createUser is a no-op returning empty when the email already exists — never overwrites.
        return userService.createUser(shell).isPresent() ? Result.CREATED : Result.ALREADY_EXISTS;
    }

    public boolean isAllowedDomain(String email) {
        int at = email == null ? -1 : email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return false;
        }
        String domain = email.substring(at + 1).toLowerCase();
        return allowedDomains().contains(domain);
    }

    /** The configured domains, for messages ("Only …@e-uvt.ro emails can be invited"). */
    public Set<String> allowedDomains() {
        return Arrays.stream(allowedDomainsCsv.split(","))
                .map(s -> s.strip().toLowerCase())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
