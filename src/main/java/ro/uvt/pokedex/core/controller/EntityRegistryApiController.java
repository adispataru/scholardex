package ro.uvt.pokedex.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.model.registry.RegistryKind;
import ro.uvt.pokedex.core.service.application.RegistryLookupFacade;

import java.util.List;
import java.util.Locale;

/**
 * H142 slice 3, H144 — the picker of the registries for a record's reference field: ranked entries and their spellings,
 * and the names already waiting for the experts, each with its level in the reader's language. Signed-in users (the
 * {@code /api/**} rule).
 */
@RestController
@RequestMapping("/api/entities/registry")
@RequiredArgsConstructor
public class EntityRegistryApiController {

    private final RegistryLookupFacade lookup;
    private final MessageSource messages;

    /** One name to pick, its level as the reader reads it (null while waiting). */
    public record SuggestionView(String name, String level, String levelLabel, String domain, String status, String spells) {
    }

    @GetMapping
    public ResponseEntity<List<SuggestionView>> search(@RequestParam("kind") String kind, @RequestParam("q") String q) {
        RegistryKind registry;
        try {
            registry = RegistryKind.valueOf(kind.trim());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        Locale locale = LocaleContextHolder.getLocale();
        return ResponseEntity.ok(lookup.search(registry, q).stream()
                .map(s -> new SuggestionView(s.name(), s.level(), s.level() == null ? null
                        : messages.getMessage("registry.level." + registry.name() + "." + s.level(), null, s.level(), locale),
                        s.domain(), s.status(), s.spells()))
                .toList());
    }
}
