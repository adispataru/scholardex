package ro.uvt.pokedex.core.service.importing.wos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.WosImportEvent;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedEventResult;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * H66 A3.2: parses MJL coverage events (one CSV row per journal × edition) into {@link WosParsedRecord}s with
 * no metric/quartile — just identity (issn/eissn/title) + edition + category. The multi-valued "Web of Science
 * Categories" column (pipe-separated) is split into one record per category, so coverage facts mirror the
 * category-fact grain; a blank categories column yields a single category-less coverage record. {@code year}
 * is the sourceVersion (the snapshot as-of, e.g. 2025).
 */
@Component
public class MjlImportEventParser extends AbstractWosImportEventParser {
    private final ObjectMapper objectMapper;

    public MjlImportEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(WosImportEvent event) {
        return event != null
                && event.getSourceType() == WosSourceType.MJL_COVERAGE
                && "mjl-csv-row".equals(event.getPayloadFormat());
    }

    @Override
    public WosParsedEventResult parse(WosImportEvent event) {
        if (!supports(event)) {
            return WosParsedEventResult.skipped("unsupported source/payload: " + (event == null ? "null" : event.getPayloadFormat()));
        }
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload());
            String issn = normalizeIssn(text(payload, "issn"));
            String eIssn = normalizeIssn(text(payload, "eIssn"));
            if (isBlank(issn) && isBlank(eIssn)) {
                return WosParsedEventResult.skipped("missing issn/eissn");
            }
            EditionNormalized edition = parseEdition(text(payload, "edition"));
            if (edition == null) {
                return WosParsedEventResult.skipped("unrecognized edition=" + text(payload, "edition"));
            }
            Integer year = parseInt(event.getSourceVersion());
            if (year == null) {
                return WosParsedEventResult.error("missing/invalid sourceVersion (year): " + event.getSourceVersion());
            }
            String title = normalizeText(text(payload, "title"));
            String editionRaw = text(payload, "edition");
            List<String> categories = splitCategories(text(payload, "categories"));

            List<WosParsedRecord> records = new ArrayList<>();
            for (String category : categories) {
                records.add(new WosParsedRecord(
                        title,
                        issn,
                        eIssn,
                        year,
                        null,           // metricType — coverage, not a metric
                        null,           // value
                        category,       // categoryNameCanonical (nullable)
                        editionRaw,
                        edition,
                        null,           // quarter
                        null,           // quartileRank
                        null,           // rank
                        event.getId(),
                        event.getSourceType(),
                        event.getSourceFile(),
                        event.getSourceVersion(),
                        event.getSourceRowItem()
                ));
            }
            return WosParsedEventResult.parsed(records);
        } catch (Exception e) {
            return WosParsedEventResult.error("parse error: " + e.getMessage());
        }
    }

    /** MJL edition tokens are already clean (SCIE/SSCI/AHCI/ESCI); map directly. */
    private static EditionNormalized parseEdition(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "SCIE" -> EditionNormalized.SCIE;
            case "SSCI" -> EditionNormalized.SSCI;
            case "AHCI" -> EditionNormalized.AHCI;
            case "ESCI" -> EditionNormalized.ESCI;
            default -> null;
        };
    }

    /** Split the pipe-separated WoS-categories column; an empty column yields a single null category. */
    private List<String> splitCategories(String raw) {
        List<String> result = new ArrayList<>();
        if (isBlank(raw)) {
            result.add(null);
            return result;
        }
        for (String part : raw.split("\\|")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        if (result.isEmpty()) {
            result.add(null);
        }
        return result;
    }
}
