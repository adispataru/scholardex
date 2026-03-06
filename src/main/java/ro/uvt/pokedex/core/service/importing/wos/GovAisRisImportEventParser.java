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
public class GovAisRisImportEventParser implements WosImportEventParser {
    private final ObjectMapper objectMapper;

    public GovAisRisImportEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(WosImportEvent event) {
        return event != null
                && event.getSourceType() == WosSourceType.GOV_AIS_RIS
                && "excel-row".equals(event.getPayloadFormat());
    }

    @Override
    public WosParsedEventResult parse(WosImportEvent event) {
        if (!supports(event)) {
            return WosParsedEventResult.skipped("unsupported source/payload: " + (event == null ? "null" : event.getPayloadFormat()));
        }
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload());
            String metricTypeRaw = text(payload, "metricType");
            MetricType metricType = parseMetricType(metricTypeRaw);
            if (metricType == null) {
                return WosParsedEventResult.skipped("unsupported metricType=" + metricTypeRaw);
            }
            Integer year = parseInt(text(payload, "year"));
            if (year == null) {
                return WosParsedEventResult.error("missing year");
            }
            JsonNode cells = payload.path("cells");
            ParsedRow row = parseGovRow(metricType, year, cells);
            if (row == null) {
                return WosParsedEventResult.skipped("no parser mapping for metric/year");
            }
            if (isBlank(row.title) && isBlank(row.issn) && isBlank(row.eIssn)) {
                return WosParsedEventResult.skipped("missing identity fields");
            }
            List<WosParsedRecord> records = toRecords(event, row, metricType, year);
            if (records.isEmpty()) {
                return WosParsedEventResult.skipped("no normalized editions generated");
            }
            return WosParsedEventResult.parsed(records);
        } catch (Exception e) {
            return WosParsedEventResult.error("parse error: " + e.getMessage());
        }
    }

    private List<WosParsedRecord> toRecords(WosImportEvent event, ParsedRow row, MetricType metricType, Integer year) {
        String editionRaw = row.editionRaw;
        Set<EditionNormalized> editions = WosCanonicalContractSupport.normalizeEditionCandidates(editionRaw);
        List<WosParsedRecord> records = new ArrayList<>();
        for (EditionNormalized edition : editions) {
            records.add(new WosParsedRecord(
                    row.title,
                    row.issn,
                    row.eIssn,
                    year,
                    metricType,
                    row.metricValue,
                    normalizeCategory(row.categoryName),
                    editionRaw,
                    edition,
                    row.quarter,
                    row.rank,
                    event.getId(),
                    event.getSourceType(),
                    event.getSourceFile(),
                    event.getSourceVersion(),
                    event.getSourceRowItem()
            ));
        }
        return records;
    }

    private ParsedRow parseGovRow(MetricType metricType, int year, JsonNode cells) {
        ParsedRow row = new ParsedRow();
        if (metricType == MetricType.AIS) {
            if (year == 2011) {
                row.title = normalizeText(text(cells, "c0"));
                row.issn = normalizeIssn(text(cells, "c1"));
                row.metricValue = parseMetricValue(text(cells, "c2"));
                return row;
            }
            if (year == 2012 || year == 2013) {
                row.title = normalizeText(text(cells, "c0"));
                row.issn = normalizeIssn(text(cells, "c1"));
                row.metricValue = parseMetricValue(text(cells, "c2"));
                row.categoryName = normalizeText(text(cells, "c3"));
                row.editionRaw = normalizeText(text(cells, "c4"));
                return row;
            }
            if (year >= 2014 && year <= 2017) {
                row.title = normalizeText(text(cells, "c1"));
                row.issn = normalizeIssn(text(cells, "c2"));
                row.metricValue = parseMetricValue(text(cells, "c3"));
                return row;
            }
            if (year == 2018 || year == 2019) {
                row.title = normalizeText(text(cells, "c0"));
                row.issn = normalizeIssn(text(cells, "c1"));
                row.metricValue = parseMetricValue(text(cells, "c2"));
                row.editionRaw = normalizeText(text(cells, "c3"));
                row.categoryName = normalizeText(text(cells, "c4"));
                row.quarter = normalizeQuarter(text(cells, "c5"));
                return row;
            }
            if (year == 2020) {
                row.title = normalizeText(text(cells, "c0"));
                row.issn = normalizeIssn(text(cells, "c1"));
                row.eIssn = normalizeIssn(text(cells, "c2"));
                row.metricValue = parseMetricValue(text(cells, "c3"));
                row.editionRaw = normalizeText(text(cells, "c4"));
                row.categoryName = normalizeText(text(cells, "c5"));
                row.quarter = normalizeQuarter(text(cells, "c6"));
                return row;
            }
            if (year == 2021) {
                row.title = normalizeText(text(cells, "c0"));
                row.issn = normalizeIssn(text(cells, "c1"));
                row.eIssn = normalizeIssn(text(cells, "c2"));
                row.metricValue = parseMetricValue(text(cells, "c3"));
                String editionFromIndex = normalizeText(text(cells, "c4"));
                parseCategoryAndEdition(normalizeText(text(cells, "c5")), row);
                if (isBlank(row.editionRaw)) {
                    row.editionRaw = editionFromIndex;
                }
                row.quarter = normalizeQuarter(text(cells, "c6"));
                return row;
            }
            if (year == 2022) {
                row.title = normalizeText(text(cells, "c0"));
                row.issn = normalizeIssn(text(cells, "c1"));
                row.eIssn = normalizeIssn(text(cells, "c2"));
                parseCategoryAndEdition(normalizeText(text(cells, "c3")), row);
                row.metricValue = parseMetricValue(text(cells, "c4"));
                row.quarter = normalizeQuarter(text(cells, "c5"));
                return row;
            }
            if (year == 2023) {
                row.title = normalizeText(text(cells, "c0"));
                row.issn = normalizeIssn(text(cells, "c1"));
                row.eIssn = normalizeIssn(text(cells, "c2"));
                row.categoryName = normalizeText(text(cells, "c3"));
                row.editionRaw = normalizeText(text(cells, "c4"));
                row.metricValue = parseMetricValue(text(cells, "c5"));
                return row;
            }
            return null;
        }
        if (metricType == MetricType.RIS) {
            row.title = normalizeText(text(cells, "c0"));
            row.issn = normalizeIssn(text(cells, "c1"));
            if (year >= 2020) {
                row.eIssn = normalizeIssn(text(cells, "c2"));
                row.metricValue = parseMetricValue(text(cells, "c3"));
            } else {
                row.metricValue = parseMetricValue(text(cells, "c2"));
            }
            return row;
        }
        return null;
    }

    private void parseCategoryAndEdition(String categoryEdition, ParsedRow row) {
        if (isBlank(categoryEdition)) {
            return;
        }
        String[] tokens = categoryEdition.split("\\s+-\\s+");
        if (tokens.length >= 2) {
            row.categoryName = normalizeText(tokens[0]);
            row.editionRaw = normalizeText(tokens[tokens.length - 1]);
            return;
        }
        row.categoryName = normalizeText(categoryEdition);
    }

    private String normalizeQuarter(String rawQuarter) {
        String q = normalizeText(rawQuarter);
        if (isBlank(q)) {
            return null;
        }
        String upper = q.toUpperCase(Locale.ROOT).replace(" ", "");
        if (upper.matches("^Q[1-4]$")) {
            return upper;
        }
        if (upper.matches("^[1-4](\\.0+)?$")) {
            return "Q" + upper.charAt(0);
        }
        return upper;
    }

    private MetricType parseMetricType(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "AIS" -> MetricType.AIS;
            case "RIS" -> MetricType.RIS;
            default -> null;
        };
    }

    private String normalizeCategory(String rawCategory) {
        String category = normalizeText(rawCategory);
        return isBlank(category) ? null : category;
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

    private Integer parseInt(String raw) {
        String value = normalizeText(raw);
        if (isBlank(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeIssn(String rawIssn) {
        String v = normalizeText(rawIssn);
        if (isBlank(v) || "N/A".equalsIgnoreCase(v)) {
            return null;
        }
        return WosCanonicalContractSupport.normalizeIssnToken(v);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
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

    private static final class ParsedRow {
        private String title;
        private String issn;
        private String eIssn;
        private Double metricValue;
        private String categoryName;
        private String editionRaw;
        private String quarter;
        private Integer rank;
    }
}
