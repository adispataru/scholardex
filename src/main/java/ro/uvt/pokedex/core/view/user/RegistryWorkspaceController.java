package ro.uvt.pokedex.core.view.user;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.model.registry.RegistryKind;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.RegistryLookupFacade;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * H142 slice 3, H144 — what the registries say about the entities the signed-in researcher's records name, as text in
 * the reader's language: the level and why, that the experts have not ranked the name yet (and what counts meanwhile),
 * or that they rejected it.
 */
@RestController
@RequiredArgsConstructor
public class RegistryWorkspaceController {

    private static final Map<String, String> CRITERION_LETTERS = Map.of(
            "INTERNATIONAL_ORGANISER", "(a)", "PROCEEDINGS_LANGUAGE", "(b)", "SESSIONS_LANGUAGE", "(c)", "PEER_REVIEW", "(d)");

    private final RegistryLookupFacade lookup;
    private final MessageSource messages;

    /** One entity a record names: the registry, the name, its status and what the researcher reads about it. */
    public record EntityLevelView(String kind, String entity, String status, String level, String text) {
    }

    @GetMapping("/user/workspace/activities/registry-levels")
    public ResponseEntity<Map<String, List<EntityLevelView>>> levels(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            return ResponseEntity.status(401).build();
        }
        Locale locale = LocaleContextHolder.getLocale();
        Map<String, List<EntityLevelView>> out = new LinkedHashMap<>();
        lookup.levelsFor(user.getEmail()).forEach((recordId, levels) -> {
            List<EntityLevelView> lines = new java.util.ArrayList<>(levels.entities().stream().map(l -> view(l, locale)).toList());
            if (levels.facts() != null) {
                lines.addAll(facts(levels.facts(), locale));
            }
            out.put(recordId, lines);
        });
        return ResponseEntity.ok(out);
    }

    /** What the lists say about the journal and the university the record names. */
    private List<EntityLevelView> facts(RegistryLookupFacade.ListFacts f, Locale locale) {
        List<EntityLevelView> lines = new java.util.ArrayList<>();
        if (f.issn() != null) {
            String text;
            if (f.journal() == null) {
                text = msg("registry.workspace.journal.unknown", "unknown", locale);
            } else {
                List<String> parts = new java.util.ArrayList<>();
                if (f.journal().webOfScience()) parts.add(msg("registry.workspace.journal.wos", "WoS", locale));
                else if (f.journal().webOfScienceCore()) parts.add(msg("registry.workspace.journal.esci", "ESCI", locale));
                if (f.journal().scopus()) parts.add(msg("registry.workspace.journal.scopus", "Scopus", locale));
                List<String> databases = f.databaseNames();
                String named = databases.isEmpty() ? "—" : String.join(", ", databases);
                parts.add(messages.getMessage("registry.workspace.journal.databases", new Object[]{named}, named, locale));
                if (f.journal().impactFactor() != null) {
                    parts.add(messages.getMessage("registry.workspace.journal.if",
                            new Object[]{String.format(locale, "%.3f", f.journal().impactFactor())}, "IF", locale));
                }
                parts.add(msg(f.journal().feeJournal() ? "registry.workspace.journal.fee" : "registry.workspace.journal.free",
                        "fee", locale));
                text = String.join("; ", parts);
            }
            lines.add(new EntityLevelView("JOURNAL", "ISSN " + f.issn(), f.journal() == null ? "UNKNOWN" : "KNOWN", null, text));
        }
        if (f.university() != null && f.universityFacts() != null) {
            var u = f.universityFacts();
            List<String> parts = new java.util.ArrayList<>();
            if (u.urapRank() != null) {
                parts.add(messages.getMessage("registry.workspace.university.urap", new Object[]{u.urapRank()}, "URAP", locale));
            }
            if (u.worldRank() != null) {
                parts.add(messages.getMessage("registry.workspace.university.world", new Object[]{u.worldRank()}, "QS", locale));
            }
            if (parts.isEmpty()) {
                parts.add(msg("registry.workspace.university.unranked", "unranked", locale));
            }
            if (u.country() != null) {
                parts.add(u.country());
            }
            lines.add(new EntityLevelView("UNIVERSITY", f.university(), u.urapRank() == null && u.worldRank() == null
                    ? "UNRANKED" : "RANKED", null, String.join("; ", parts)));
        }
        return lines;
    }

    private EntityLevelView view(RegistryLookupFacade.EntityLevel l, Locale locale) {
        RegistryKind kind = l.kind();
        String text;
        if ("RANKED".equals(l.status())) {
            StringBuilder b = new StringBuilder(msg("registry.level." + kind.name() + "." + l.level(), l.level(), locale));
            if (l.basis() != null) {
                b.append(" — ").append(msg("registry.basis." + kind.name() + "." + l.basis(), l.basis(), locale));
            }
            if (l.criteria() != null && !l.criteria().isEmpty()) {
                String letters = l.criteria().stream().map(c -> CRITERION_LETTERS.getOrDefault(c, c)).sorted()
                        .collect(Collectors.joining(", "));
                b.append(" (").append(messages.getMessage("registry.workspace.criteria", new Object[]{letters}, letters, locale)).append(")");
            }
            if (l.note() != null && !l.note().isBlank()) {
                b.append(". ").append(l.note());
            }
            text = b.toString();
        } else if ("REJECTED".equals(l.status())) {
            text = messages.getMessage("registry.workspace.rejected", new Object[]{l.note() == null ? "—" : l.note()},
                    "rejected", locale);
        } else {
            text = msg("registry.workspace.waiting." + kind.name(), "waiting", locale);
        }
        return new EntityLevelView(kind.name(), l.entity(), l.status(), l.level(), text);
    }

    private String msg(String key, String fallback, Locale locale) {
        return messages.getMessage(key, null, fallback, locale);
    }
}
