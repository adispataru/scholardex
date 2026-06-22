package ro.uvt.pokedex.core.service.derivation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexInstitutionFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationFact;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusAffiliationRorMatcher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.normalizeBlank;
import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.normalizeName;
import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.normalizeToken;
import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.shortHash;

/**
 * H75 — the pure, in-memory canonical graph builder (the "Transform" of the V2 Load→Build→Write engine). Zero DB I/O:
 * every method takes already-loaded source facts and returns the canonical facts + source-links to bulk-write. Ports
 * the V1 identity/dedup rules verbatim (see {@code docs/tasks/active/h75-rules-catalog.md}) so the differential
 * harness asserts byte-parity with V1.
 *
 * <p>Stage 1.a: {@link #buildAffiliations}. Forums and publications follow in S1.b / S1.c.
 */
@Component
public class CanonicalGraphBuilder {

    private static final String SOURCE_OPENALEX = "OPENALEX";
    private static final String SOURCE_SCOPUS = "SCOPUS";
    private static final String LINK_REASON_SCOPUS_AFFILIATION_BRIDGE = "scopus-affiliation-bridge";

    /** H72 slice 1: drop ad-hoc Scopus afids (keep only verified {@code 60…}). Same property V1's canon reads. */
    @Value("${scopus.affiliation.verified-only:true}")
    private boolean verifiedOnly;

    public record AffiliationBuildResult(List<ScholardexAffiliationFact> facts,
                                         List<ScholardexSourceLinkService.SourceLinkUpsertCommand> sourceLinks) {
    }

    /**
     * Derive the canonical affiliation graph: the ROR backbone from OpenAlex institution facts, then Scopus afids
     * resolved into it (3-tier alias match → enrich) or minted afid-keyed. Mirrors the importer's {@code toBackboneFact}
     * + {@code ScholardexAffiliationCanonicalizationService} (verified-only, sort-by-afid, enrich-vs-mint, source-link).
     */
    public AffiliationBuildResult buildAffiliations(List<OpenAlexInstitutionFact> institutions,
                                                    List<ScopusAffiliationFact> scopusAffiliations) {
        // 1) ROR backbone (saff_<hash(ror)>), deterministic insertion order by institution id.
        LinkedHashMap<String, ScholardexAffiliationFact> byCanonicalId = new LinkedHashMap<>();
        List<OpenAlexInstitutionFact> sortedInstitutions = new ArrayList<>(institutions);
        sortedInstitutions.sort(Comparator.comparing(OpenAlexInstitutionFact::getId, Comparator.nullsLast(String::compareTo)));
        for (OpenAlexInstitutionFact inst : sortedInstitutions) {
            ScholardexAffiliationFact backbone = toBackboneFact(inst);
            if (backbone != null) {
                byCanonicalId.putIfAbsent(backbone.getId(), backbone);
            }
        }
        ScopusAffiliationRorMatcher matcher = ScopusAffiliationRorMatcher.build(new ArrayList<>(byCanonicalId.values()));

        // 2) Scopus afids resolve into the backbone or mint — processed in V1's sort order (afid asc, nulls last).
        List<ScholardexSourceLinkService.SourceLinkUpsertCommand> sourceLinks = new ArrayList<>();
        List<ScopusAffiliationFact> sortedScopus = new ArrayList<>(scopusAffiliations);
        sortedScopus.sort(Comparator.comparing(ScopusAffiliationFact::getAfid, Comparator.nullsLast(String::compareTo)));
        for (ScopusAffiliationFact sa : sortedScopus) {
            String afid = normalizeBlank(sa.getAfid());
            if (afid == null) {
                continue;
            }
            if (verifiedOnly && !CanonicalizationSupport.isVerifiedScopusAffiliationId(afid)) {
                continue; // ad-hoc afid dropped (verified-only)
            }
            ScholardexAffiliationFact backboneMatch = matcher == null
                    ? null
                    : matcher.match(sa.getName(), sa.getCity(), sa.getCountry());
            String canonicalId = backboneMatch != null
                    ? backboneMatch.getId()
                    : buildCanonicalAffiliationId(afid, sa.getName(), sa.getCity(), sa.getCountry());

            ScholardexAffiliationFact target = byCanonicalId.get(canonicalId);
            boolean created = target == null;
            boolean backbone = !created && SOURCE_OPENALEX.equals(target.getSource());
            Instant now = Instant.now();
            if (created) {
                target = new ScholardexAffiliationFact();
                target.setCreatedAt(now);
            }
            target.setId(canonicalId);
            CanonicalizationSupport.addUnique(target.getScopusAffiliationIds(), afid);
            if (backbone) {
                // Keep OpenAlex name/city/country/source/rorIds authoritative; record the Scopus name as an alias.
                CanonicalizationSupport.addUnique(target.getAliases(), normalizeAlias(sa.getName(), sa.getCity(), sa.getCountry()));
            } else {
                target.setName(sa.getName());
                target.setNameNormalized(normalizeName(sa.getName()));
                target.setCity(sa.getCity());
                target.setCountry(sa.getCountry());
                CanonicalizationSupport.addUnique(target.getAliases(), normalizeAlias(sa.getName(), sa.getCity(), sa.getCountry()));
                target.setSourceEventId(sa.getSourceEventId());
                target.setSource(sa.getSource());
                target.setSourceRecordId(afid);
                target.setSourceBatchId(sa.getSourceBatchId());
                target.setSourceCorrelationId(sa.getSourceCorrelationId());
            }
            target.setUpdatedAt(now);
            byCanonicalId.put(canonicalId, target);

            sourceLinks.add(new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                    ScholardexEntityType.AFFILIATION, SOURCE_SCOPUS, afid, canonicalId,
                    ScholardexSourceLinkService.STATE_LINKED, LINK_REASON_SCOPUS_AFFILIATION_BRIDGE,
                    sa.getSourceEventId(), sa.getSourceBatchId(), sa.getSourceCorrelationId(), false));
        }
        return new AffiliationBuildResult(new ArrayList<>(byCanonicalId.values()), sourceLinks);
    }

    /** Mirror of the importer's {@code toBackboneFact}: ROR-keyed backbone fact; null when the institution has no ROR. */
    private ScholardexAffiliationFact toBackboneFact(OpenAlexInstitutionFact inst) {
        String ror = normalizeBlank(inst.getRor());
        if (ror == null) {
            return null;
        }
        ScholardexAffiliationFact fact = new ScholardexAffiliationFact();
        fact.setId(CanonicalizationSupport.buildRorBackboneAffiliationId(ror));
        CanonicalizationSupport.addUnique(fact.getRorIds(), ror);
        fact.setName(inst.getDisplayName());
        fact.setNameNormalized(normalizeName(inst.getDisplayName()));
        addTrimmedAliases(fact, inst.getDisplayNameAlternatives());
        addTrimmedAliases(fact, inst.getDisplayNameAcronyms());
        fact.setCity(inst.getGeoCity());
        String country = inst.getGeoCountry() != null && !inst.getGeoCountry().isBlank()
                ? inst.getGeoCountry()
                : inst.getCountryCode();
        fact.setCountry(country);
        Instant now = Instant.now();
        fact.setCreatedAt(now);
        fact.setUpdatedAt(now);
        fact.setSource(SOURCE_OPENALEX);
        fact.setSourceRecordId(inst.getId());
        fact.setSourceEventId(inst.getId());
        fact.setSourceBatchId(inst.getSourceBatchId());
        fact.setSourceCorrelationId(inst.getSourceCorrelationId());
        return fact;
    }

    private static void addTrimmedAliases(ScholardexAffiliationFact fact, List<String> values) {
        if (values == null) {
            return;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                CanonicalizationSupport.addUnique(fact.getAliases(), v.trim());
            }
        }
    }

    /** Mirror of the affiliation canon's id builder: afid → {@code scopus|<afid>}, else name+city+country. */
    static String buildCanonicalAffiliationId(String afid, String name, String city, String country) {
        String material;
        if (afid != null && !afid.isBlank()) {
            material = "scopus|" + normalizeToken(afid);
        } else {
            material = "name|" + normalizeToken(normalizeName(name))
                    + "|city|" + normalizeToken(city)
                    + "|country|" + normalizeToken(country);
        }
        return "saff_" + shortHash(material);
    }

    /** Mirror of the affiliation canon's {@code normalizeAlias}; {@code null} when all three tokens are blank. */
    private static String normalizeAlias(String name, String city, String country) {
        String alias = normalizeToken(normalizeName(name)) + "|" + normalizeToken(city) + "|" + normalizeToken(country);
        return alias.equals("||") ? null : alias;
    }
}
