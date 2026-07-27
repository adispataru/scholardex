package ro.uvt.pokedex.core.service.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * H94 — applies the committed indicator descriptions ({@code indicator-descriptions/*.json}, keyed by
 * indicator NAME) onto the live indicators. The descriptions ship in the same commit as the code that
 * renders them (the changelog pattern), and this endpoint is the data-after-code step: deploy, then apply.
 *
 * <p>Matching is by exact name because names are the stable human identity of an indicator here (ids
 * differ between environments; the seed and prod were created separately). Unmatched names on either
 * side are REPORTED, not ignored — a silently dropped description is how docs rot.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndicatorDescriptionService {

    /** Static on purpose — a JSON parser, not a bean; same pattern as the thresholds mapper (H68). */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final IndicatorRepository indicatorRepository;

    public ApplyReport apply(boolean dryRun) {
        Map<String, String> descriptions = loadCommitted();
        Map<String, List<Indicator>> byName = new LinkedHashMap<>();
        for (Indicator indicator : indicatorRepository.findAll()) {
            byName.computeIfAbsent(indicator.getName(), ignored -> new ArrayList<>()).add(indicator);
        }

        int updated = 0;
        int unchanged = 0;
        List<String> unmatchedDescriptions = new ArrayList<>();
        for (Map.Entry<String, String> entry : descriptions.entrySet()) {
            List<Indicator> matches = byName.get(entry.getKey());
            if (matches == null || matches.isEmpty()) {
                unmatchedDescriptions.add(entry.getKey());
                continue;
            }
            for (Indicator indicator : matches) {
                if (entry.getValue().equals(indicator.getDescription())) {
                    unchanged++;
                    continue;
                }
                updated++;
                if (!dryRun) {
                    indicator.setDescription(entry.getValue());
                    indicatorRepository.save(indicator);
                }
            }
        }
        List<String> indicatorsWithoutDescription = byName.keySet().stream()
                .filter(name -> !descriptions.containsKey(name))
                .sorted()
                .toList();
        log.info("Indicator descriptions ({}): updated={} unchanged={} unmatchedDescriptions={} indicatorsWithout={}",
                dryRun ? "dry-run" : "apply", updated, unchanged,
                unmatchedDescriptions.size(), indicatorsWithoutDescription.size());
        return new ApplyReport(dryRun, updated, unchanged, unmatchedDescriptions, indicatorsWithoutDescription);
    }

    /** Every {@code indicator-descriptions/*.json} on the classpath, merged; {@code _comment} dropped. */
    private Map<String, String> loadCommitted() {
        Map<String, String> merged = new LinkedHashMap<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:indicator-descriptions/*.json");
            for (Resource resource : resources) {
                Map<String, String> file = JSON.readValue(resource.getInputStream(),
                        new TypeReference<LinkedHashMap<String, String>>() { });
                file.remove("_comment");
                merged.putAll(file);
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot read committed indicator descriptions", e);
        }
        return merged;
    }

    public record ApplyReport(boolean dryRun, int updated, int unchanged,
                              List<String> unmatchedDescriptions,
                              List<String> indicatorsWithoutDescription) {
    }
}
