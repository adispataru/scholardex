package ro.uvt.pokedex.core.service.derivation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexInstitutionFact;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusAffiliationRorMatcher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

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

    // ── Publications (S1.c / Stage 2): DOI-first identity, Decision-0 blocklist, OpenAlex field precedence ──────

    private static final double SHARED_DOI_TITLE_SIMILARITY = 0.5;

    public record PublicationBuildResult(List<ScholardexPublicationFact> facts,
                                          List<ScholardexSourceLinkService.SourceLinkUpsertCommand> sourceLinks) {
    }

    /** One source publication, source-agnostic, carrying just the fields the canonical pub + its id need. */
    private record SourcePub(boolean openAlex, String source, String sourceRecordId, String eid, String doi,
                             String doiNorm, String title, String titleNorm, String coverDate, String creator,
                             Integer authorCount, Integer citedByCount, Boolean openAccess, String subtype,
                             String subtypeDescription, String scopusSubtype, String scopusSubtypeDescription,
                             String pii, String pubmedId, String volume, String issueIdentifier, String bookId,
                             String sourceEventId, String sourceBatchId, String sourceCorrelationId) {
    }

    /**
     * Derive canonical publications from Scopus + OpenAlex source pubs: DOI-keyed identity (Decision-0 container-DOI
     * blocklist), OpenAlex field precedence on shared DOIs, Scopus enrich (eid + Scopus-only fields), monotonic-max
     * citation count, plus the PUBLICATION source-links. forumId/affiliationIds/authorIds are filled by later steps.
     */
    public PublicationBuildResult buildPublications(List<ScopusPublicationFact> scopusPubs,
                                                    List<OpenAlexPublicationFact> openAlexPubs) {
        List<SourcePub> sources = new ArrayList<>(scopusPubs.size() + openAlexPubs.size());
        for (ScopusPublicationFact s : scopusPubs) {
            sources.add(fromScopus(s));
        }
        for (OpenAlexPublicationFact o : openAlexPubs) {
            sources.add(fromOpenAlex(o));
        }
        Set<String> doiBlocklist = computeDoiBlocklist(sources);

        // Group source pubs by their canonical id (DOI-first, blocklist-aware).
        LinkedHashMap<String, List<SourcePub>> byCanonicalId = new LinkedHashMap<>();
        for (SourcePub sp : sources) {
            String id = buildPublicationId(sp, doiBlocklist);
            byCanonicalId.computeIfAbsent(id, k -> new ArrayList<>()).add(sp);
        }

        List<ScholardexPublicationFact> facts = new ArrayList<>(byCanonicalId.size());
        List<ScholardexSourceLinkService.SourceLinkUpsertCommand> sourceLinks = new ArrayList<>();
        for (var entry : byCanonicalId.entrySet()) {
            facts.add(buildPublicationFact(entry.getKey(), entry.getValue()));
            for (SourcePub sp : entry.getValue()) {
                sourceLinks.add(new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                        ScholardexEntityType.PUBLICATION, sp.source(), sp.sourceRecordId(), entry.getKey(),
                        ScholardexSourceLinkService.STATE_LINKED, "scopus-publication-bridge",
                        sp.sourceEventId(), sp.sourceBatchId(), sp.sourceCorrelationId(), false));
            }
        }
        return new PublicationBuildResult(facts, sourceLinks);
    }

    /** Build one canonical pub from its source group: OpenAlex authoritative for content, Scopus enriches. */
    private ScholardexPublicationFact buildPublicationFact(String canonicalId, List<SourcePub> group) {
        SourcePub openAlex = group.stream().filter(SourcePub::openAlex).findFirst().orElse(null);
        SourcePub scopus = group.stream().filter(sp -> !sp.openAlex()).findFirst().orElse(null);
        SourcePub authoritative = openAlex != null ? openAlex : group.getFirst(); // OpenAlex precedence
        boolean openAlexOwned = openAlex != null;

        ScholardexPublicationFact fact = new ScholardexPublicationFact();
        Instant now = Instant.now();
        fact.setId(canonicalId);
        fact.setCreatedAt(now);
        fact.setUpdatedAt(now);
        // Bibliographic content — OpenAlex-authoritative when present.
        fact.setDoi(authoritative.doi());
        fact.setDoiNormalized(authoritative.doiNorm());
        fact.setTitle(authoritative.title());
        fact.setTitleNormalized(authoritative.titleNorm());
        fact.setCreator(authoritative.creator());
        fact.setAuthorCount(authoritative.authorCount());
        fact.setCoverDate(authoritative.coverDate());
        fact.setOpenAccess(authoritative.openAccess());
        fact.setSubtype(authoritative.subtype());
        fact.setSubtypeDescription(authoritative.subtypeDescription());
        // Citation count: monotonic max across sources (best-available index never regresses).
        int cited = 0;
        for (SourcePub sp : group) {
            if (sp.citedByCount() != null) {
                cited = Math.max(cited, sp.citedByCount());
            }
        }
        fact.setCitedByCount(cited);
        // Scopus-only enrichment.
        if (scopus != null) {
            fact.setEid(scopus.eid());
            fact.setPii(scopus.pii());
            fact.setPubmedId(scopus.pubmedId());
            fact.setScopusSubtype(scopus.scopusSubtype());
            fact.setScopusSubtypeDescription(scopus.scopusSubtypeDescription());
            fact.setVolume(scopus.volume());
            fact.setIssueIdentifier(scopus.issueIdentifier());
            fact.setBookId(scopus.bookId());
        }
        fact.setSource(openAlexOwned ? SOURCE_OPENALEX : authoritative.source());
        fact.setSourceRecordId(authoritative.sourceRecordId());
        fact.setSourceEventId(authoritative.sourceEventId());
        fact.setSourceBatchId(authoritative.sourceBatchId());
        fact.setSourceCorrelationId(authoritative.sourceCorrelationId());
        return fact;
    }

    /** Canonical pub id: DOI (unless blocklisted) → eid → title+date+creator. {@code spub_<hash>}. */
    private static String buildPublicationId(SourcePub sp, Set<String> doiBlocklist) {
        String material;
        if (sp.doiNorm() != null && !sp.doiNorm().isBlank() && !doiBlocklist.contains(sp.doiNorm())) {
            material = "doi|" + normalizeToken(sp.doiNorm());
        } else if (sp.eid() != null && !sp.eid().isBlank()) {
            material = "eid|" + normalizeToken(sp.eid());
        } else {
            material = "title|" + normalizeToken(sp.titleNorm())
                    + "|date|" + normalizeToken(sp.coverDate())
                    + "|creator|" + normalizeToken(sp.creator())
                    + "|forum|" + normalizeToken(null);
        }
        return "spub_" + shortHash(material);
    }

    /**
     * H66B Decision-0: a DOI carried by ≥2 records that form >1 title-cluster (token-Jaccard≥0.5) is a container DOI
     * (e.g. book chapters sharing the book's DOI). Computed over <b>Scopus</b> source pubs only — like V1 — so that
     * benign cross-source title variance (Scopus vs OpenAlex spelling of the same paper) never splits a real paper.
     */
    private static Set<String> computeDoiBlocklist(List<SourcePub> sources) {
        LinkedHashMap<String, List<SourcePub>> byDoi = new LinkedHashMap<>();
        for (SourcePub sp : sources) {
            if (!sp.openAlex() && sp.doiNorm() != null && !sp.doiNorm().isBlank()) {
                byDoi.computeIfAbsent(sp.doiNorm(), k -> new ArrayList<>()).add(sp);
            }
        }
        Set<String> blocklist = new java.util.HashSet<>();
        for (var e : byDoi.entrySet()) {
            if (e.getValue().size() >= 2 && titleClusterCount(e.getValue()) > 1) {
                blocklist.add(e.getKey());
            }
        }
        return blocklist;
    }

    /** Single-link clustering of a DOI group's titles by token-Jaccard ≥ 0.5; returns the cluster count. */
    private static int titleClusterCount(List<SourcePub> group) {
        List<Set<String>> clusters = new ArrayList<>();
        for (SourcePub sp : group) {
            Set<String> tokens = titleTokens(sp.titleNorm());
            boolean placed = false;
            for (Set<String> rep : clusters) {
                if (jaccard(rep, tokens) >= SHARED_DOI_TITLE_SIMILARITY) {
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                clusters.add(tokens);
            }
        }
        return clusters.size();
    }

    private static Set<String> titleTokens(String titleNorm) {
        Set<String> tokens = new java.util.HashSet<>();
        if (titleNorm != null) {
            for (String t : titleNorm.split("\\s+")) {
                if (!t.isBlank()) {
                    tokens.add(t);
                }
            }
        }
        return tokens;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        Set<String> inter = new java.util.HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new java.util.HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0 : (double) inter.size() / union.size();
    }

    private static SourcePub fromScopus(ScopusPublicationFact s) {
        String doiNorm = ScholardexPublicationCanonicalizationService.normalizeDoi(s.getDoi());
        String titleNorm = ScholardexPublicationCanonicalizationService.normalizeTitle(s.getTitle());
        return new SourcePub(false, s.getSource(), s.getSourceRecordId(), s.getEid(), s.getDoi(), doiNorm,
                s.getTitle(), titleNorm, s.getCoverDate(), s.getCreator(), s.getAuthorCount(), s.getCitedByCount(),
                s.getOpenAccess(), s.getSubtype(), s.getSubtypeDescription(), s.getScopusSubtype(),
                s.getScopusSubtypeDescription(), s.getPii(), s.getPubmedId(), s.getVolume(), s.getIssueIdentifier(),
                s.getBookId(), s.getSourceEventId(), s.getSourceBatchId(), s.getSourceCorrelationId());
    }

    private static SourcePub fromOpenAlex(OpenAlexPublicationFact o) {
        String doiNorm = ScholardexPublicationCanonicalizationService.normalizeDoi(o.getDoi());
        String titleNorm = ScholardexPublicationCanonicalizationService.normalizeTitle(o.getTitle());
        return new SourcePub(true, SOURCE_OPENALEX, o.getSourceRecordId(), null, o.getDoi(), doiNorm,
                o.getTitle(), titleNorm, o.getCoverDate(), o.getCreator(), o.getAuthorCount(), o.getCitedByCount(),
                o.getOpenAccess(), o.getType(), o.getType(), null, null, null, null, null, null, null,
                o.getSourceEventId(), o.getSourceBatchId(), o.getSourceCorrelationId());
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
