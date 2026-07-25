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
            // WoS/JCR extracts use ±999 as the "no value" sentinel. Only the negative side was rejected, so
            // the positive one rode in as a real metric: the 1998 extracts carry journalImpactFactor=999.999
            // for 56 journals (50 SCIE + 6 SSCI) — an identical three-decimal value shared across a whole
            // year is not data. Unlike a non-finite value, nothing downstream clamps it (999.999 is a
            // perfectly ordinary double), so it scored as a genuine Impact Factor for Journal Of Sociology
            // and 55 others. The bound is safe for every metric this parses: the highest real IF on record
            // is ~685 (CA-A Cancer Journal), AIS peaks near 108 and RIS near 126.
            if (parsed <= -999.0 || parsed >= 999.0) {
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
