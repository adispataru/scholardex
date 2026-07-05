package ro.uvt.pokedex.core.service.importing.wos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.wos.WosImportEvent;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedEventResult;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedRecord;

import java.util.List;

/**
 * Parses JCR matrix events (one row per journal: Title20 abbreviation + full title + edition flags) into a
 * single naming-reference {@link WosParsedRecord} — full title as {@code title}, Title20 as
 * {@code abbreviatedTitle}, no ISSN and no metric. These records never become facts or identities; the fact
 * builder routes them into {@link WosTitleAuthority} before resolving the ISSN-bearing sources.
 */
@Component
public class JcrReferenceImportEventParser extends AbstractWosImportEventParser {
    private final ObjectMapper objectMapper;

    public JcrReferenceImportEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(WosImportEvent event) {
        return event != null
                && event.getSourceType() == WosSourceType.JCR_REFERENCE
                && "jcr-csv-row".equals(event.getPayloadFormat());
    }

    @Override
    public WosParsedEventResult parse(WosImportEvent event) {
        if (!supports(event)) {
            return WosParsedEventResult.skipped("unsupported source/payload: " + (event == null ? "null" : event.getPayloadFormat()));
        }
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload());
            String title20 = normalizeText(text(payload, "title20"));
            String fullTitle = normalizeText(text(payload, "title"));
            if (isBlank(title20) || isBlank(fullTitle)) {
                return WosParsedEventResult.skipped("missing title20/title");
            }
            WosParsedRecord record = new WosParsedRecord(
                    fullTitle,
                    null,           // issn — JCR matrix carries none
                    null,           // eIssn
                    parseInt(event.getSourceVersion()),
                    null,           // metricType — naming reference, not a metric
                    null,           // value
                    null,           // categoryNameCanonical
                    normalizeText(text(payload, "editions")),
                    null,           // editionNormalized
                    null,           // quarter
                    null,           // quartileRank
                    null,           // rank
                    event.getId(),
                    event.getSourceType(),
                    event.getSourceFile(),
                    event.getSourceVersion(),
                    event.getSourceRowItem(),
                    title20
            );
            return WosParsedEventResult.parsed(List.of(record));
        } catch (Exception e) {
            return WosParsedEventResult.error("parse error: " + e.getMessage());
        }
    }
}
