package ro.uvt.pokedex.core.service.derivation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexInstitutionFact;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationFact;
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
import java.util.Map;
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

    // ── Citations (pub→pub, internal-only): OpenAlex referencedWorks + Scopus eid-keyed citation facts ──────────

    /**
     * Build internal citation edges (both endpoints held): OpenAlex {@code referencedWorks} resolved via a
     * workId→canonical-pub map, plus Scopus citation facts resolved via an eid→canonical-pub map. Deduped by the
     * {cited, citing} natural key (OpenAlex-first). External references (works we don't hold) are skipped.
     */
    public List<ScholardexCitationFact> buildCitations(List<ScopusPublicationFact> scopusPubs,
                                                       List<OpenAlexPublicationFact> openAlexPubs,
                                                       List<ScopusCitationFact> scopusCitations) {
        List<SourcePub> allSources = new ArrayList<>();
        for (ScopusPublicationFact s : scopusPubs) {
            allSources.add(fromScopus(s));
        }
        for (OpenAlexPublicationFact o : openAlexPubs) {
            allSources.add(fromOpenAlex(o));
        }
        Set<String> doiBlocklist = computeDoiBlocklist(allSources);

        Map<String, String> workToCanonical = new java.util.HashMap<>();
        for (OpenAlexPublicationFact o : openAlexPubs) {
            if (!isBlank(o.getSourceRecordId())) {
                workToCanonical.put(o.getSourceRecordId(), buildPublicationId(fromOpenAlex(o), doiBlocklist));
            }
        }
        Map<String, String> eidToCanonical = new java.util.HashMap<>();
        for (ScopusPublicationFact s : scopusPubs) {
            if (!isBlank(s.getEid())) {
                eidToCanonical.put(s.getEid(), buildPublicationId(fromScopus(s), doiBlocklist));
            }
        }

        Set<String> seen = new java.util.HashSet<>();
        List<ScholardexCitationFact> edges = new ArrayList<>();
        for (OpenAlexPublicationFact o : openAlexPubs) {
            String citing = workToCanonical.get(o.getSourceRecordId());
            if (citing == null || o.getReferencedWorks() == null) {
                continue;
            }
            for (String ref : o.getReferencedWorks()) {
                String cited = workToCanonical.get(ref);
                if (cited != null && !cited.equals(citing) && seen.add(cited + "|" + citing)) {
                    edges.add(citation(cited, citing, SOURCE_OPENALEX));
                }
            }
        }
        for (ScopusCitationFact sc : scopusCitations) {
            String cited = eidToCanonical.get(sc.getCitedEid());
            String citing = eidToCanonical.get(sc.getCitingEid());
            if (cited != null && citing != null && !cited.equals(citing) && seen.add(cited + "|" + citing)) {
                edges.add(citation(cited, citing, SOURCE_SCOPUS));
            }
        }
        return edges;
    }

    private static ScholardexCitationFact citation(String citedPubId, String citingPubId, String source) {
        ScholardexCitationFact e = new ScholardexCitationFact();
        Instant now = Instant.now();
        e.setCitedPublicationId(citedPubId);
        e.setCitingPublicationId(citingPubId);
        e.setSource(source);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }

    // ── Authors (Stage 2, core identity): seed nodes + positional bridge union-find, OpenAlex-keyed ─────────────

    /** Key tier for the canonical id of a merged component: ORCID > OpenAlex-id > Scopus (OpenAlex-first). */
    private enum KeyTier { ORCID, OPENALEX, SCOPUS }

    /** Mutable accumulator for one author seed node (a union-find element). */
    private static final class AuthorNode {
        final String id;
        final KeyTier tier;
        final java.util.LinkedHashSet<String> scopusAuthorIds = new java.util.LinkedHashSet<>();
        final java.util.LinkedHashSet<String> orcidIds = new java.util.LinkedHashSet<>();
        final java.util.LinkedHashSet<String> openAlexAuthorIds = new java.util.LinkedHashSet<>();
        final java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        String displayName;

        AuthorNode(String id, KeyTier tier) {
            this.id = id;
            this.tier = tier;
        }
    }

    public record AuthorBuildResult(List<ScholardexAuthorFact> authors,
                                    List<ScholardexSourceLinkService.SourceLinkUpsertCommand> sourceLinks,
                                    Map<String, List<String>> pubAuthorIds,
                                    List<ScholardexAuthorshipFact> authorshipEdges,
                                    List<ScholardexAuthorAffiliationFact> authorAffiliationEdges,
                                    List<ScholardexPublicationAuthorAffiliationFact> pubAuthorAffiliationEdges) {
    }

    /**
     * Build the canonical author graph (core cross-source identity; fuzzy/over-split reconcile deferred): seed a node
     * per Scopus AU-ID + per OpenAlex authorship (ORCID/OpenAlex-id keyed), union Scopus↔OpenAlex authors via the
     * positional bridge on shared-DOI papers (equal count + surname match), and resolve each component to one
     * {@link ScholardexAuthorFact}. The component's canonical id is OpenAlex-keyed when an ORCID/OpenAlex node is
     * present (the S2.2 inversion). Returns authors + AUTHOR source-links + the {@code pub.authorIds[]} denormalization.
     */
    public AuthorBuildResult buildAuthors(List<ScopusAuthorFact> scopusAuthors,
                                          List<ScopusPublicationFact> scopusPubs,
                                          List<OpenAlexPublicationFact> openAlexPubs) {
        Map<String, AuthorNode> nodes = new LinkedHashMap<>();
        Map<String, String> auidToNode = new java.util.HashMap<>();
        Map<String, String> scopusNameByAuid = new java.util.HashMap<>();

        // Seed Scopus nodes.
        for (ScopusAuthorFact sa : scopusAuthors) {
            String auid = normalizeBlank(sa.getAuthorId());
            if (auid == null) {
                continue;
            }
            String nodeId = "sauth_" + shortHash("scopus|" + normalizeToken(auid));
            AuthorNode node = nodes.computeIfAbsent(nodeId, id -> new AuthorNode(id, KeyTier.SCOPUS));
            node.scopusAuthorIds.add(auid);
            rememberName(node, sa.getName());
            auidToNode.put(auid, nodeId);
            if (sa.getName() != null) {
                scopusNameByAuid.put(auid, sa.getName());
            }
        }
        // Seed OpenAlex nodes from authorships across all OpenAlex pubs.
        for (OpenAlexPublicationFact pub : openAlexPubs) {
            if (pub.getAuthorships() == null) {
                continue;
            }
            for (OpenAlexPublicationFact.AuthorRef ref : pub.getAuthorships()) {
                String nodeId = openAlexNodeId(ref.getOrcid(), ref.getOpenAlexAuthorId());
                if (nodeId == null) {
                    continue; // name-only authorship: not id-resolvable
                }
                KeyTier tier = !isBlank(ref.getOrcid()) ? KeyTier.ORCID : KeyTier.OPENALEX;
                AuthorNode node = nodes.computeIfAbsent(nodeId, id -> new AuthorNode(id, tier));
                if (!isBlank(ref.getOrcid())) {
                    node.orcidIds.add(ref.getOrcid());
                }
                if (!isBlank(ref.getOpenAlexAuthorId())) {
                    node.openAlexAuthorIds.add(ref.getOpenAlexAuthorId());
                }
                rememberName(node, ref.getDisplayName());
            }
        }

        // Union-find over node ids.
        Map<String, String> parent = new java.util.HashMap<>();
        for (String id : nodes.keySet()) {
            parent.put(id, id);
        }

        // Positional bridge: index source pubs by canonical pub id, then union Scopus[i]↔OpenAlex[i] on shared papers.
        List<SourcePub> allSources = new ArrayList<>();
        for (ScopusPublicationFact s : scopusPubs) {
            allSources.add(fromScopus(s));
        }
        for (OpenAlexPublicationFact o : openAlexPubs) {
            allSources.add(fromOpenAlex(o));
        }
        Set<String> doiBlocklist = computeDoiBlocklist(allSources);
        Map<String, ScopusPublicationFact> scopusByCanon = new java.util.HashMap<>();
        for (ScopusPublicationFact s : scopusPubs) {
            scopusByCanon.putIfAbsent(buildPublicationId(fromScopus(s), doiBlocklist), s);
        }
        for (OpenAlexPublicationFact o : openAlexPubs) {
            String canonId = buildPublicationId(fromOpenAlex(o), doiBlocklist);
            ScopusPublicationFact sPub = scopusByCanon.get(canonId);
            if (sPub == null || sPub.getAuthors() == null || o.getAuthorships() == null) {
                continue;
            }
            List<String> auids = sPub.getAuthors();
            List<OpenAlexPublicationFact.AuthorRef> refs = o.getAuthorships();
            if (auids.isEmpty() || auids.size() != refs.size()) {
                continue; // count guard
            }
            for (int i = 0; i < auids.size(); i++) {
                String sNode = auidToNode.get(normalizeBlank(auids.get(i)));
                String oNode = openAlexNodeId(refs.get(i).getOrcid(), refs.get(i).getOpenAlexAuthorId());
                if (sNode == null || oNode == null || !parent.containsKey(oNode)) {
                    continue;
                }
                if (ro.uvt.pokedex.core.service.openalex.OpenAlexAuthorResolver.surnameMatches(
                        scopusNameByAuid.get(normalizeBlank(auids.get(i))), refs.get(i).getDisplayName())) {
                    union(parent, sNode, oNode);
                }
            }
        }

        // Resolve components → canonical authors (OpenAlex-keyed id), + AUTHOR source-links + nodeId→canonicalId map.
        Map<String, List<AuthorNode>> components = new LinkedHashMap<>();
        for (AuthorNode node : nodes.values()) {
            components.computeIfAbsent(find(parent, node.id), k -> new ArrayList<>()).add(node);
        }
        Map<String, String> nodeToCanonical = new java.util.HashMap<>();
        List<ScholardexAuthorFact> authors = new ArrayList<>(components.size());
        List<ScholardexSourceLinkService.SourceLinkUpsertCommand> sourceLinks = new ArrayList<>();
        for (List<AuthorNode> members : components.values()) {
            AuthorNode canonical = members.stream()
                    .min(Comparator.<AuthorNode>comparingInt(n -> n.tier.ordinal()).thenComparing(n -> n.id))
                    .orElseThrow();
            String canonicalId = canonical.id;
            ScholardexAuthorFact author = new ScholardexAuthorFact();
            Instant now = Instant.now();
            author.setId(canonicalId);
            author.setCreatedAt(now);
            author.setUpdatedAt(now);
            String displayName = canonical.displayName;
            for (AuthorNode m : members) {
                nodeToCanonical.put(m.id, canonicalId);
                m.scopusAuthorIds.forEach(a -> CanonicalizationSupport.addUnique(author.getScopusAuthorIds(), a));
                m.orcidIds.forEach(o -> CanonicalizationSupport.addUnique(author.getOrcidIds(), o));
                m.openAlexAuthorIds.forEach(x -> CanonicalizationSupport.addUnique(author.getOpenAlexAuthorIds(), x));
                if (displayName == null) {
                    displayName = m.displayName;
                }
                for (String n : m.names) {
                    CanonicalizationSupport.addUnique(author.getAlternativeNames(), n);
                }
            }
            author.setDisplayName(displayName);
            author.setNameNormalized(normalizeName(displayName));
            authors.add(author);
            for (String auid : author.getScopusAuthorIds()) {
                sourceLinks.add(new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                        ScholardexEntityType.AUTHOR, SOURCE_SCOPUS, auid, canonicalId,
                        ScholardexSourceLinkService.STATE_LINKED, "scopus-author-bridge", null, null, null, false));
            }
        }

        // Denormalize pub.authorIds: OpenAlex author order when an OpenAlex source is present, else Scopus order.
        Map<String, List<String>> pubAuthorIds = new java.util.HashMap<>();
        Map<String, OpenAlexPublicationFact> openAlexByCanon = new java.util.HashMap<>();
        for (OpenAlexPublicationFact o : openAlexPubs) {
            openAlexByCanon.putIfAbsent(buildPublicationId(fromOpenAlex(o), doiBlocklist), o);
        }
        // Edge accumulators, deduped by natural key (a recurring author↔affiliation across papers writes once).
        Set<String> seenAuthorship = new java.util.HashSet<>();
        Set<String> seenAuthorAff = new java.util.HashSet<>();
        Set<String> seenPubAuthorAff = new java.util.HashSet<>();
        List<ScholardexAuthorshipFact> authorshipEdges = new ArrayList<>();
        List<ScholardexAuthorAffiliationFact> authorAffiliationEdges = new ArrayList<>();
        List<ScholardexPublicationAuthorAffiliationFact> pubAuthorAffiliationEdges = new ArrayList<>();

        Set<String> canonIds = new java.util.LinkedHashSet<>();
        canonIds.addAll(scopusByCanon.keySet());
        canonIds.addAll(openAlexByCanon.keySet());
        for (String canonId : canonIds) {
            OpenAlexPublicationFact o = openAlexByCanon.get(canonId);
            ScopusPublicationFact s = scopusByCanon.get(canonId);

            // pub.authorIds: OpenAlex author order when present, else Scopus order.
            List<String> ids = new ArrayList<>();
            if (o != null && o.getAuthorships() != null) {
                for (OpenAlexPublicationFact.AuthorRef ref : o.getAuthorships()) {
                    addCanonicalAuthor(ids, nodeToCanonical, openAlexNodeId(ref.getOrcid(), ref.getOpenAlexAuthorId()));
                }
            } else if (s != null && s.getAuthors() != null) {
                for (String auid : s.getAuthors()) {
                    addCanonicalAuthor(ids, nodeToCanonical, auidToNode.get(normalizeBlank(auid)));
                }
            }
            if (!ids.isEmpty()) {
                pubAuthorIds.put(canonId, ids);
            }

            // Edges from BOTH sources (they coexist by `source`). OpenAlex also contributes affiliation edges via RORs.
            if (o != null && o.getAuthorships() != null) {
                for (OpenAlexPublicationFact.AuthorRef ref : o.getAuthorships()) {
                    String ca = nodeToCanonical.get(openAlexNodeId(ref.getOrcid(), ref.getOpenAlexAuthorId()));
                    if (ca == null) {
                        continue;
                    }
                    if (seenAuthorship.add(canonId + "|" + ca + "|" + SOURCE_OPENALEX)) {
                        authorshipEdges.add(authorship(canonId, ca, SOURCE_OPENALEX, ref.isCorresponding()));
                    }
                    if (ref.getInstitutionRors() != null) {
                        for (String ror : ref.getInstitutionRors()) {
                            if (isBlank(ror)) {
                                continue;
                            }
                            String aff = CanonicalizationSupport.buildRorBackboneAffiliationId(ror);
                            if (seenAuthorAff.add(ca + "|" + aff + "|" + SOURCE_OPENALEX)) {
                                authorAffiliationEdges.add(authorAffiliation(ca, aff, SOURCE_OPENALEX));
                            }
                            if (seenPubAuthorAff.add(canonId + "|" + ca + "|" + aff + "|" + SOURCE_OPENALEX)) {
                                pubAuthorAffiliationEdges.add(pubAuthorAffiliation(canonId, ca, aff, SOURCE_OPENALEX));
                            }
                        }
                    }
                }
            }
            if (s != null && s.getAuthors() != null) {
                for (String auid : s.getAuthors()) {
                    String ca = nodeToCanonical.get(auidToNode.get(normalizeBlank(auid)));
                    if (ca != null && seenAuthorship.add(canonId + "|" + ca + "|" + SOURCE_SCOPUS)) {
                        authorshipEdges.add(authorship(canonId, ca, SOURCE_SCOPUS, false));
                    }
                }
            }
        }
        return new AuthorBuildResult(authors, sourceLinks, pubAuthorIds,
                authorshipEdges, authorAffiliationEdges, pubAuthorAffiliationEdges);
    }

    private static void addCanonicalAuthor(List<String> ids, Map<String, String> nodeToCanonical, String nodeId) {
        if (nodeId == null) {
            return;
        }
        String canonical = nodeToCanonical.get(nodeId);
        if (canonical != null && !ids.contains(canonical)) {
            ids.add(canonical);
        }
    }

    private static final String LINK_STATE_LINKED = "LINKED";

    private static ScholardexAuthorshipFact authorship(String pubId, String authorId, String source, boolean corresponding) {
        ScholardexAuthorshipFact e = new ScholardexAuthorshipFact();
        Instant now = Instant.now();
        e.setPublicationId(pubId);
        e.setAuthorId(authorId);
        e.setSource(source);
        e.setLinkState(LINK_STATE_LINKED);
        e.setCorresponding(corresponding);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }

    private static ScholardexAuthorAffiliationFact authorAffiliation(String authorId, String affiliationId, String source) {
        ScholardexAuthorAffiliationFact e = new ScholardexAuthorAffiliationFact();
        Instant now = Instant.now();
        e.setAuthorId(authorId);
        e.setAffiliationId(affiliationId);
        e.setSource(source);
        e.setLinkState(LINK_STATE_LINKED);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }

    private static ScholardexPublicationAuthorAffiliationFact pubAuthorAffiliation(
            String pubId, String authorId, String affiliationId, String source) {
        ScholardexPublicationAuthorAffiliationFact e = new ScholardexPublicationAuthorAffiliationFact();
        Instant now = Instant.now();
        e.setPublicationId(pubId);
        e.setAuthorId(authorId);
        e.setAffiliationId(affiliationId);
        e.setSource(source);
        e.setLinkState(LINK_STATE_LINKED);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }

    private static String openAlexNodeId(String orcid, String openAlexAuthorId) {
        if (!isBlank(orcid)) {
            return "sauth_" + shortHash("orcid|" + orcid.toLowerCase(java.util.Locale.ROOT));
        }
        if (!isBlank(openAlexAuthorId)) {
            return "sauth_" + shortHash("openalex|" + openAlexAuthorId);
        }
        return null;
    }

    private static void rememberName(AuthorNode node, String name) {
        if (name != null && !name.isBlank()) {
            node.names.add(name.trim());
            if (node.displayName == null) {
                node.displayName = name.trim();
            }
        }
    }

    private static String find(Map<String, String> parent, String i) {
        while (!parent.get(i).equals(i)) {
            parent.put(i, parent.get(parent.get(i)));
            i = parent.get(i);
        }
        return i;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        parent.put(find(parent, a), find(parent, b));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
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
                             String scopusForumId, String openAlexHostVenueId, List<String> scopusAffiliations,
                             String sourceEventId, String sourceBatchId, String sourceCorrelationId) {
    }

    /** Forum + affiliation lookups so the V2 pub build can resolve {@code forumId} + {@code affiliationIds}. */
    public record PubResolvers(Map<String, String> scopusForumToCanonical,
                               Map<String, String> openAlexVenueToCanonical,
                               Map<String, String> afidToCanonicalAffiliation) {
        public static PubResolvers empty() {
            return new PubResolvers(Map.of(), Map.of(), Map.of());
        }
    }

    /**
     * Derive canonical publications from Scopus + OpenAlex source pubs: DOI-keyed identity (Decision-0 container-DOI
     * blocklist), OpenAlex field precedence on shared DOIs, Scopus enrich (eid + Scopus-only fields), monotonic-max
     * citation count, plus the PUBLICATION source-links. forumId/affiliationIds/authorIds are filled by later steps.
     */
    public PublicationBuildResult buildPublications(List<ScopusPublicationFact> scopusPubs,
                                                    List<OpenAlexPublicationFact> openAlexPubs,
                                                    PubResolvers resolvers) {
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
            facts.add(buildPublicationFact(entry.getKey(), entry.getValue(), resolvers));
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
    private ScholardexPublicationFact buildPublicationFact(String canonicalId, List<SourcePub> group, PubResolvers resolvers) {
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
        // forumId: OpenAlex host venue first (by OpenAlex venue id), else the Scopus forum id — both → sforum_.
        String forumId = null;
        if (openAlex != null && openAlex.openAlexHostVenueId() != null) {
            forumId = resolvers.openAlexVenueToCanonical().get(openAlex.openAlexHostVenueId());
        }
        if (forumId == null && scopus != null && scopus.scopusForumId() != null) {
            forumId = resolvers.scopusForumToCanonical().get(scopus.scopusForumId());
        }
        fact.setForumId(forumId);
        // affiliationIds: resolve the Scopus pub's afids to canonical (saff_) affiliations (deduped, order-preserving).
        if (scopus != null && scopus.scopusAffiliations() != null) {
            java.util.LinkedHashSet<String> affs = new java.util.LinkedHashSet<>();
            for (String afid : scopus.scopusAffiliations()) {
                String saff = resolvers.afidToCanonicalAffiliation().get(afid);
                if (saff != null) {
                    affs.add(saff);
                }
            }
            fact.setAffiliationIds(new ArrayList<>(affs));
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
                s.getBookId(), s.getForumId(), null, s.getAffiliations(),
                s.getSourceEventId(), s.getSourceBatchId(), s.getSourceCorrelationId());
    }

    private static SourcePub fromOpenAlex(OpenAlexPublicationFact o) {
        String doiNorm = ScholardexPublicationCanonicalizationService.normalizeDoi(o.getDoi());
        String titleNorm = ScholardexPublicationCanonicalizationService.normalizeTitle(o.getTitle());
        return new SourcePub(true, SOURCE_OPENALEX, o.getSourceRecordId(), null, o.getDoi(), doiNorm,
                o.getTitle(), titleNorm, o.getCoverDate(), o.getCreator(), o.getAuthorCount(), o.getCitedByCount(),
                o.getOpenAccess(), o.getType(), o.getType(), null, null, null, null, null, null, null,
                null, o.getHostVenueOpenAlexId(), null,
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
