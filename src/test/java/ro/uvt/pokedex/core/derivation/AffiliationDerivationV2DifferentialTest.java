package ro.uvt.pokedex.core.derivation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexInstitutionFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationFactRepository;
import ro.uvt.pokedex.core.service.derivation.CanonicalDerivationV2Service;
import ro.uvt.pokedex.core.service.importing.scopus.CanonicalBuildOptions;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationService;
import ro.uvt.pokedex.core.service.openalex.OpenAlexBulkImportService;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexInstitutionRecord;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H75 S1.a — the first V2-vs-V1 differential: assert the V2 affiliation builder produces a byte-identical
 * {@code scholardex.affiliation_facts} + AFFILIATION source-links to V1 (importer backbone + the Scopus affiliation
 * canon), on a fixture covering all three outcomes: backbone enrich (Scopus afid alias-matches a ROR institution),
 * mint (Scopus-only afid), and drop (ad-hoc afid under verified-only).
 */
class AffiliationDerivationV2DifferentialTest extends CanonicalDerivationIntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private OpenAlexInstitutionFactRepository institutionFactRepository;
    @Autowired private ScopusAffiliationFactRepository scopusAffiliationFactRepository;
    @Autowired private OpenAlexBulkImportService openAlexBulkImportService;
    @Autowired private ScholardexAffiliationCanonicalizationService affiliationCanonicalizationService;
    @Autowired private CanonicalDerivationV2Service v2Service;

    private static final String UVT_INSTITUTION_JSON = """
            {"id":"https://openalex.org/I123","ror":"https://ror.org/0583a0t97",
             "display_name":"West University of Timişoara",
             "display_name_alternatives":["Universitatea de Vest din Timișoara"],
             "display_name_acronyms":["UVT"],
             "country_code":"RO","geo":{"city":"Timișoara","country":"Romania","country_code":"RO"}}
            """;

    @Test
    void v2AffiliationBuildMatchesV1Exactly() throws Exception {
        mongoTemplate.getDb().drop();
        seedSourcesAndV1Backbone();

        // V1: the Scopus affiliation canon enriches the backbone / mints / drops, writing affiliation_facts + links.
        affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts(CanonicalBuildOptions.defaults());
        Map<String, Map<String, String>> v1 = CanonicalSnapshot.snapshot(mongoTemplate);

        // V2: wipe the canonical affiliation layer and rebuild it purely from the source facts.
        v2Service.rebuildAffiliationsV2();
        Map<String, Map<String, String>> v2 = CanonicalSnapshot.snapshot(mongoTemplate);

        List<String> diff = CanonicalSnapshot.diff(v1, v2);
        assertThat(diff)
                .as("V2 affiliation build must equal V1 byte-for-byte:\n%s", String.join("\n", diff))
                .isEmpty();

        // Sanity: backbone(UVT, afid-enriched) + minted(Berlin); the ad-hoc afid was dropped. Two SCOPUS links.
        assertThat(v1.get("scholardex.affiliation_facts")).hasSize(2);
        assertThat(v1.get("scholardex.source_links")).hasSize(2);
    }

    private void seedSourcesAndV1Backbone() throws Exception {
        OpenAlexInstitutionRecord rec = MAPPER.readValue(UVT_INSTITUTION_JSON, OpenAlexInstitutionRecord.class);
        // Source facts (V2 inputs): the institution + three Scopus affiliations.
        institutionFactRepository.save(openAlexBulkImportService.toInstitutionFact(rec, "I123", "batch", "corr"));
        scopusAffiliationFactRepository.saveAll(List.of(
                scopusAffiliation("60013876", "Universitatea de Vest din Timișoara", "Timișoara", "Romania"), // alias match -> enrich
                scopusAffiliation("60009999", "Technical University of Berlin", "Berlin", "Germany"),          // no match -> mint
                scopusAffiliation("100000001", "Ad-hoc Unmatched Org", "Nowhere", "Nowhere")));                // ad-hoc -> dropped
        // V1 backbone (what the importer writes during ingest), via the real importer mapping.
        mongoTemplate.save(openAlexBulkImportService.toBackboneFact(rec, "I123", "batch", "corr"));
    }

    private ScopusAffiliationFact scopusAffiliation(String afid, String name, String city, String country) {
        ScopusAffiliationFact fact = new ScopusAffiliationFact();
        fact.setAfid(afid);
        fact.setName(name);
        fact.setCity(city);
        fact.setCountry(country);
        fact.setSource("SCOPUS");
        fact.setSourceRecordId(afid);
        return fact;
    }
}
