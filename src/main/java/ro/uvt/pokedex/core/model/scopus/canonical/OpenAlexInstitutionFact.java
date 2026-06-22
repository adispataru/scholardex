package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * H75 S1.0 — an OpenAlex <b>institution</b> source fact (referenced-only, from {@code data/openalex/institutions/*.gz}).
 *
 * <p>Promotes the institution snapshot to a first-class source collection so the ROR affiliation backbone becomes a
 * real <em>derivation</em> (the V2 engine maps these into {@code saff_<hash(ror)>} {@code ScholardexAffiliationFact}s)
 * rather than an ingest side-effect that V1's bulk importer wrote straight into the wiped canonical collection. Stores
 * the <b>raw</b> mapping inputs (display name + multilingual aliases/acronyms, geo, country code, stripped ROR) so the
 * derivation — country fallback, alias merge, ROR keying — lives entirely in the builder, not at ingest.
 */
@Data
@Document(collection = "openalex.institution_facts")
public class OpenAlexInstitutionFact {
    /** Stripped OpenAlex institution id (no {@code https://openalex.org/} prefix), e.g. {@code I123}. */
    @Id
    private String id;
    /** Stripped ROR (no {@code https://ror.org/} prefix) — the backbone key; records without it are not backboned. */
    private String ror;
    private String displayName;
    private List<String> displayNameAlternatives = new ArrayList<>();
    private List<String> displayNameAcronyms = new ArrayList<>();
    private String countryCode;
    private String geoCity;
    private String geoCountry;

    private String source;
    private String sourceRecordId;
    private String sourceBatchId;
    private String sourceCorrelationId;
    private Instant createdAt;
    private Instant updatedAt;
    private String builderVersion;
}
