package ro.uvt.pokedex.core.service.importing.scopus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Set;

/**
 * H120 — what is NOT kept from a Scopus record. Elsevier's policy for research information systems lists the
 * metadata an institution may hold (ids, authors, affiliations, title, source, dates, document type, citation
 * count, …); abstracts, author keywords, funding, open-access status, the corresponding-author list and the
 * text of a citing document's reference are outside that list, and nothing in the platform scores by them.
 * They are dropped where a Scopus payload enters the platform — the import event — so the raw layer does not
 * hold them either. Funding and open access come from OpenAlex.
 * <p>
 * Payloads of other sources (the publication wizard, OpenAlex) pass through untouched.
 */
public final class ScopusLicensedFields {

    static final Set<String> NOT_KEPT = Set.of(
            "description", "abstract",
            "authkeywords", "keywords",
            "fund_acr", "fund_no", "fund_sponsor",
            "openaccess", "freetoread", "freetoreadLabel",
            "correspondingAuthors",
            "matched_reference", "matchedReference"
    );

    private ScopusLicensedFields() {
    }

    public static boolean isScopusSource(String source) {
        return source != null && source.toUpperCase(Locale.ROOT).startsWith("SCOPUS");
    }

    /** The payload without the fields that are not kept; the very same object for a non-Scopus source. */
    public static Object strip(String source, Object payloadObject, ObjectMapper objectMapper) {
        if (payloadObject == null || !isScopusSource(source)) {
            return payloadObject;
        }
        JsonNode tree = payloadObject instanceof JsonNode node ? node : objectMapper.valueToTree(payloadObject);
        if (!(tree instanceof ObjectNode object)) {
            return payloadObject;
        }
        boolean carriesAny = false;
        for (String field : NOT_KEPT) {
            if (object.has(field)) {
                carriesAny = true;
                break;
            }
        }
        if (!carriesAny) {
            return payloadObject;
        }
        ObjectNode copy = object.deepCopy();
        copy.remove(NOT_KEPT);
        return copy;
    }
}
