package ro.uvt.pokedex.core.service.importing.wos;

import com.fasterxml.jackson.databind.JsonNode;
import ro.uvt.pokedex.core.model.reporting.wos.WosImportEvent;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedEventResult;

public abstract class AbstractWosImportEventParser implements WosImportEventParser {

    @Override
    public abstract boolean supports(WosImportEvent event);

    @Override
    public abstract WosParsedEventResult parse(WosImportEvent event);

    protected Double parseMetricValue(String rawValue) {
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

    protected Integer parseInt(String rawValue) {
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

    protected String normalizeIssn(String rawIssn) {
        String value = normalizeText(rawIssn);
        if (isBlank(value) || "N/A".equalsIgnoreCase(value)) {
            return null;
        }
        return WosCanonicalContractSupport.normalizeIssnToken(value);
    }

    protected String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    protected String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    protected boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
