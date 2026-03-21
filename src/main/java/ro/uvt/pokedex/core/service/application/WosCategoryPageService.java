package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.service.application.model.WosCategoryDetailViewModel;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WosCategoryPageService {

    private static final Set<EditionNormalized> SUPPORTED_EDITIONS = Set.of(EditionNormalized.SCIE, EditionNormalized.SSCI);

    private final PostgresWosCategoryReadPort postgresWosCategoryReadPort;

    public Optional<WosCategoryDetailViewModel> findCategory(String key) {
        Optional<CategoryKey> parsed = parseCategoryKey(key);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        CategoryKey categoryKey = parsed.get();
        return postgresWosCategoryReadPort.findCategoryPage(categoryKey.categoryName(), categoryKey.edition());
    }

    public static String categoryKey(String categoryName, EditionNormalized edition) {
        return categoryName + " - " + edition.name();
    }

    private Optional<CategoryKey> parseCategoryKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        int separator = key.lastIndexOf(" - ");
        if (separator < 0) {
            return Optional.empty();
        }
        String categoryName = key.substring(0, separator).trim();
        String editionRaw = key.substring(separator + 3).trim().toUpperCase(Locale.ROOT);
        if (categoryName.isBlank()) {
            return Optional.empty();
        }
        try {
            EditionNormalized edition = EditionNormalized.valueOf(editionRaw);
            if (!SUPPORTED_EDITIONS.contains(edition)) {
                return Optional.empty();
            }
            return Optional.of(new CategoryKey(categoryName, edition));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private record CategoryKey(String categoryName, EditionNormalized edition) {}
}
