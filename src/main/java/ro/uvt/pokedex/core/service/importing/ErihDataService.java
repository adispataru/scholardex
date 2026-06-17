package ro.uvt.pokedex.core.service.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.erih.ErihJournalFact;
import ro.uvt.pokedex.core.repository.erih.ErihJournalFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * H66 A5 — load the ERIH PLUS snapshot (JSONL export of the {@code erihplus_tidsskrift_cache} Typesense
 * collection) into {@code erih.journal_facts}. One fact per journal keyed by native ERIH+ id; ISSNs
 * normalized for the later match-only forum onboarding ({@code ErihOnboardingService}).
 */
@Service
@RequiredArgsConstructor
public class ErihDataService {

    private static final Logger logger = LoggerFactory.getLogger(ErihDataService.class);
    private static final int DEFAULT_ERROR_SAMPLE_SIZE = 20;
    private static final String SOURCE_ERIH = "ERIH";
    private static final Pattern ISSN_NON_ALNUM = Pattern.compile("[^0-9Xx]");

    private final ErihJournalFactRepository erihJournalFactRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse the ERIH PLUS JSONL at {@code absolutePath} (one document per line) and upsert one
     * {@link ErihJournalFact} per journal.
     *
     * @param asOf snapshot stamp surfaced as {@code membership_view.as_of}; may be null.
     */
    public ImportProcessingResult importErihJsonlFromPath(String absolutePath, String batchId, String asOf) {
        ImportProcessingResult result = new ImportProcessingResult(DEFAULT_ERROR_SAMPLE_SIZE);
        Instant now = Instant.now();
        Map<String, ErihJournalFact> byId = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(absolutePath))) {
            String line;
            long lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                result.markProcessed();
                JsonNode node;
                try {
                    node = objectMapper.readTree(line);
                } catch (IOException ex) {
                    result.markError("erih-bad-json line=" + lineNo + ": " + ex.getMessage());
                    continue;
                }
                String erihId = text(node, "id");
                if (erihId == null) {
                    result.markSkipped("erih-row-without-id line=" + lineNo);
                    continue;
                }
                ErihJournalFact fact = byId.computeIfAbsent(erihId, k -> {
                    ErihJournalFact f = new ErihJournalFact();
                    f.setId(k);
                    return f;
                });
                fact.setIssn(normalizeIssn(text(node, "tidsskriftISSNP")));
                fact.setEIssn(normalizeIssn(text(node, "tidsskriftISSNE")));
                fact.setTitle(firstNonBlank(text(node, "navn_en"), text(node, "navn")));
                fact.setDisciplineIds(stringList(node.get("discipline_ids")));
                fact.setOaDoaj(node.path("oa_doaj").asInt(0) == 1);
                fact.setAsOf(asOf);
                fact.setSource(SOURCE_ERIH);
                fact.setSourceBatchId(batchId);
            }

            // Blank-tolerant upsert: preserve created timestamps, refresh content.
            Map<String, ErihJournalFact> existing = new HashMap<>();
            for (ErihJournalFact stored : erihJournalFactRepository.findByIdIn(byId.keySet())) {
                existing.put(stored.getId(), stored);
            }
            for (ErihJournalFact incoming : byId.values()) {
                ErihJournalFact stored = existing.get(incoming.getId());
                incoming.setCreatedAt(stored != null && stored.getCreatedAt() != null ? stored.getCreatedAt() : now);
                incoming.setUpdatedAt(now);
                if (stored == null) {
                    result.markImported();
                } else {
                    result.markUpdated();
                }
            }
            erihJournalFactRepository.saveAll(new ArrayList<>(byId.values()));
            logger.info("ERIH import complete: path={} journals={} batchId={} asOf={}",
                    absolutePath, byId.size(), batchId, asOf);
        } catch (IOException ex) {
            logger.error("ERIH import failed: path={}", absolutePath, ex);
            result.markError("erih-import-failed: " + ex.getMessage());
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static List<String> stringList(JsonNode arrayNode) {
        List<String> out = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode element : arrayNode) {
                String value = element.asText();
                if (value != null && !value.isBlank()) {
                    out.add(value.trim());
                }
            }
        }
        return out;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null ? a : b;
    }

    /** Compact, upper-cased ISSN (hyphens/spaces stripped); null unless exactly 8 ISSN chars. */
    private static String normalizeIssn(String raw) {
        if (raw == null) {
            return null;
        }
        String compact = ISSN_NON_ALNUM.matcher(raw).replaceAll("").toUpperCase(Locale.ROOT);
        return compact.length() == 8 ? compact : null;
    }
}
