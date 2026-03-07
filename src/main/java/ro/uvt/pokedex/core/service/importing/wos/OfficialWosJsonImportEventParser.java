package ro.uvt.pokedex.core.service.importing.wos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosImportEvent;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedEventResult;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class OfficialWosJsonImportEventParser implements WosImportEventParser {
    private final ObjectMapper objectMapper;

    public OfficialWosJsonImportEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(WosImportEvent event) {
        return event != null
                && event.getSourceType() == WosSourceType.OFFICIAL_WOS_EXTRACT
                && "json-item".equals(event.getPayloadFormat());
    }

    @Override
    public WosParsedEventResult parse(WosImportEvent event) {
        if (!supports(event)) {
            return WosParsedEventResult.skipped("unsupported source/payload: " + (event == null ? "null" : event.getPayloadFormat()));
        }
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload());
            Integer year = parseInt(text(payload, "year"));
            if (year == null) {
                return WosParsedEventResult.error("missing year");
            }
            String title = normalizeText(text(payload, "journalTitle"));
            if (isBlank(title)) {
                title = normalizeText(text(payload, "abbrJournal"));
            }
            String issn = normalizeIssn(text(payload, "issn"));
            String eIssn = normalizeIssn(firstNonBlank(text(payload, "eissn"), text(payload, "eIssn")));
            String category = normalizeText(text(payload, "categoryName"));
            Integer rank = parseInt(text(payload, "rank"));
            String editionRaw = normalizeText(text(payload, "edition"));
            Set<EditionNormalized> editions = WosCanonicalContractSupport.normalizeEditionCandidates(editionRaw);

            List<WosParsedRecord> records = new ArrayList<>();
            Double aisValue = parseMetricValue(text(payload, "articleInfluenceScore"));
            if (aisValue != null || payload.has("articleInfluenceScore")) {
                records.addAll(toRecords(event, title, issn, eIssn, year, MetricType.AIS, aisValue, category, editionRaw, rank, editions));
            }
            Double ifValue = parseMetricValue(text(payload, "journalImpactFactor"));
            if (ifValue != null || payload.has("journalImpactFactor")) {
                records.addAll(toRecords(event, title, issn, eIssn, year, MetricType.IF, ifValue, category, editionRaw, rank, editions));
            }

            if (records.isEmpty()) {
                return WosParsedEventResult.skipped("no supported metric values");
            }
            return WosParsedEventResult.parsed(records);
        } catch (Exception e) {
            return WosParsedEventResult.error("parse error: " + e.getMessage());
        }
    }

    private List<WosParsedRecord> toRecords(
            WosImportEvent event,
            String title,
            String issn,
            String eIssn,
            Integer year,
            MetricType metricType,
            Double metricValue,
            String category,
            String editionRaw,
            Integer rank,
            Set<EditionNormalized> editions
    ) {
        List<WosParsedRecord> records = new ArrayList<>();
        for (EditionNormalized edition : editions) {
            records.add(new WosParsedRecord(
                    title,
                    issn,
                    eIssn,
                    year,
                    metricType,
                    metricValue,
                    category,
                    editionRaw,
                    edition,
                    null,
                    null,
                    rank,
                    event.getId(),
                    event.getSourceType(),
                    event.getSourceFile(),
                    event.getSourceVersion(),
                    event.getSourceRowItem()
            ));
        }
        return records;
    }

    private Double parseMetricValue(String rawValue) {
        String value = normalizeText(rawValue);
        if (isBlank(value)) {
            return null;
        }
        try {
            Double parsed = Double.parseDouble(value.replace(",", "."));
            if (parsed <= -999.0) {
                return null;
            }
            return WosCanonicalContractSupport.normalizeMetricValue(parsed);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseInt(String rawValue) {
        String value = normalizeText(rawValue);
        if (isBlank(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            try {
                return (int) Double.parseDouble(value);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private String normalizeIssn(String rawIssn) {
        String value = normalizeText(rawIssn);
        if (isBlank(value) || "N/A".equalsIgnoreCase(value)) {
            return null;
        }
        return WosCanonicalContractSupport.normalizeIssnToken(value);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return value.asText();
    }

    private String firstNonBlank(String left, String right) {
        String first = normalizeText(left);
        if (!isBlank(first)) {
            return first;
        }
        return normalizeText(right);
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
