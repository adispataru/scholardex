package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.WorkspacePreferencesRepository;

import java.time.Instant;

/**
 * Assembles the signed-in greeting shown on the landing page. Its job is to make a successful login
 * unmistakable — the previous landing page looked identical signed in and signed out, apart from one button.
 *
 * <p>The "new updates" count reuses the workspace's {@code lastVisitAt} stamp, so a returning user lands on a
 * concrete reason to open the changelog rather than a generic greeting.</p>
 */
@Service
@RequiredArgsConstructor
public class WelcomeFacade {

    private final WorkspacePreferencesRepository workspacePreferencesRepository;
    private final ChangelogService changelogService;

    public Welcome forUser(User user, boolean isAdmin) {
        if (user == null) {
            return null;
        }
        Instant lastVisit = workspacePreferencesRepository.findById(nullSafeEmail(user))
                .map(prefs -> prefs.getLastVisitAt())
                .orElse(null);
        long newUpdates = changelogService.newSince(lastVisit, isAdmin);
        return new Welcome(displayName(user), nullSafeEmail(user), newUpdates, lastVisit != null);
    }

    /**
     * First name when the researcher profile carries one (the friendliest form), else the full name, else the
     * local part of the e-mail — never blank, so the greeting cannot render as "Bun venit, !".
     */
    static String displayName(User user) {
        User.ResearcherProfile profile = user.getResearcherProfile();
        if (profile != null) {
            String first = trimToNull(profile.getFirstName());
            if (first != null) {
                return first;
            }
            String last = trimToNull(profile.getLastName());
            if (last != null) {
                return last;
            }
        }
        String email = nullSafeEmail(user);
        int at = email.indexOf('@');
        String local = at > 0 ? email.substring(0, at) : email;
        return local.isBlank() ? "cercetător" : local;
    }

    private static String nullSafeEmail(User user) {
        return user.getEmail() == null ? "" : user.getEmail();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * @param displayName  what to greet them by
     * @param email        shown as the "signed in as" confirmation — the unambiguous proof of WHICH account
     * @param newUpdates   changelog entries published since their last workspace visit
     * @param returning    false for someone who has never opened the workspace (no "since last visit" framing)
     */
    public record Welcome(String displayName, String email, long newUpdates, boolean returning) {
    }
}
