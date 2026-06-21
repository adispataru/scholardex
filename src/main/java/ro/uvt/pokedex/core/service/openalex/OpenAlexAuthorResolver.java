package ro.uvt.pokedex.core.service.openalex;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * H66B Phase 4a — resolve an OpenAlex corresponding author to a canonical {@link ScholardexAuthorFact} by its
 * cross-source id keys (ORCID, then OpenAlex author id), minting one when nothing matches (the "mint + reconcile
 * later" policy — OpenAlex becomes an author source). Also seeds an ORCID onto an existing canonical author (the
 * syncing researcher's Scopus-derived author) so corresponding-author resolution dedups against them instead of
 * minting a duplicate. Name-only refs (no ORCID, no OpenAlex id) are not id-resolvable and return {@code null}.
 *
 * <p><b>H73 slice 3 (bulk mode):</b> at citer-full scale the per-author repo lookups (ORCID / OpenAlex-id contains
 * + findById) dominate. A {@link BulkContext} primes an in-memory index of all authors once; resolve/attach/bridge
 * then read and mutate the cache (deferring writes to a single batched flush). All three author-mutating entry
 * points route through the same context so a write is never stale against a later read. {@code null} context keeps
 * the original per-call repo behavior for the per-researcher path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAlexAuthorResolver {

    static final String SOURCE_OPENALEX = "OPENALEX";

    private final ScholardexAuthorFactRepository authorRepository;

    /**
     * H73 slice 3: in-memory author index for a bulk canonicalization run. Primed once from the full author
     * collection; resolve/attach/bridge read its maps and register mutations as dirty for a single batched flush.
     * Single-threaded within one run.
     */
    public static final class BulkContext {
        private final Map<String, ScholardexAuthorFact> byId = new HashMap<>();
        private final Map<String, ScholardexAuthorFact> byOrcid = new HashMap<>();
        private final Map<String, ScholardexAuthorFact> byOpenAlex = new HashMap<>();
        private final Map<String, ScholardexAuthorFact> dirty = new LinkedHashMap<>();

        void index(ScholardexAuthorFact a) {
            if (a == null || a.getId() == null) {
                return;
            }
            byId.put(a.getId(), a);
            if (a.getOrcidIds() != null) {
                for (String o : a.getOrcidIds()) {
                    byOrcid.merge(o, a, BulkContext::preferEstablished);
                }
            }
            if (a.getOpenAlexAuthorIds() != null) {
                for (String x : a.getOpenAlexAuthorIds()) {
                    byOpenAlex.merge(x, a, BulkContext::preferEstablished);
                }
            }
        }

        void markDirty(ScholardexAuthorFact a) {
            if (a != null && a.getId() != null) {
                dirty.put(a.getId(), a);
            }
        }

        /** Mirror {@link #preferEstablished(List)}: keep the Scopus-established author over a fresh OpenAlex twin. */
        private static ScholardexAuthorFact preferEstablished(ScholardexAuthorFact existing, ScholardexAuthorFact incoming) {
            boolean existingEstablished = existing.getScopusAuthorIds() != null && !existing.getScopusAuthorIds().isEmpty();
            boolean incomingEstablished = incoming.getScopusAuthorIds() != null && !incoming.getScopusAuthorIds().isEmpty();
            return (incomingEstablished && !existingEstablished) ? incoming : existing;
        }

        public int dirtyCount() {
            return dirty.size();
        }
    }

    /** Prime a bulk context from the full author collection (one read; in-memory thereafter). */
    public BulkContext primeBulkContext() {
        BulkContext ctx = new BulkContext();
        for (ScholardexAuthorFact a : authorRepository.findAll()) {
            ctx.index(a);
        }
        log.info("OpenAlex author resolver: primed bulk context with {} authors", ctx.byId.size());
        return ctx;
    }

    /** Batch-persist every author minted/enriched during the bulk run. */
    public void flushBulkContext(BulkContext ctx) {
        if (ctx == null || ctx.dirty.isEmpty()) {
            return;
        }
        int n = ctx.dirty.size();
        authorRepository.saveAll(ctx.dirty.values());
        ctx.dirty.clear();
        log.info("OpenAlex author resolver: flushed {} dirty authors", n);
    }

    /** Back-compat overload: resolve-or-mint with no affiliation hints, repo-backed. */
    public String resolveOrMint(String displayName, String orcidRaw, String openAlexAuthorIdRaw, String batchId, String correlationId) {
        return resolveOrMint(displayName, orcidRaw, openAlexAuthorIdRaw, List.of(), batchId, correlationId, null);
    }

    /** Repo-backed overload (per-researcher path). */
    public String resolveOrMint(String displayName, String orcidRaw, String openAlexAuthorIdRaw,
                                List<String> affiliationNames, String batchId, String correlationId) {
        return resolveOrMint(displayName, orcidRaw, openAlexAuthorIdRaw, affiliationNames, batchId, correlationId, null);
    }

    /**
     * Find-or-mint a canonical author by its id keys; {@code null} if it has no id-resolvable identity. H71:
     * {@code affiliationNames} are accumulated onto the author as the reconciler's cross-source dedup hint. When
     * {@code ctx} is non-null, all reads/writes go through the in-memory bulk index (H73 slice 3).
     */
    public String resolveOrMint(String displayName, String orcidRaw, String openAlexAuthorIdRaw,
                                List<String> affiliationNames, String batchId, String correlationId, BulkContext ctx) {
        String orcid = blankToNull(orcidRaw);
        String openAlexAuthorId = blankToNull(openAlexAuthorIdRaw);

        if (orcid != null) {
            ScholardexAuthorFact byOrcid = lookupByOrcid(orcid, ctx);
            if (byOrcid != null) {
                return enrich(byOrcid, orcid, openAlexAuthorId, affiliationNames, ctx);
            }
        }
        if (openAlexAuthorId != null) {
            ScholardexAuthorFact byOpenAlex = lookupByOpenAlex(openAlexAuthorId, ctx);
            if (byOpenAlex != null) {
                return enrich(byOpenAlex, orcid, openAlexAuthorId, affiliationNames, ctx);
            }
        }
        if (orcid == null && openAlexAuthorId == null) {
            return null; // name-only — not id-resolvable under the id-based model.
        }

        // MINT (mint + reconcile policy). Empty scopusAuthorIds keeps it out of the partial-filter unique index.
        String canonicalId = buildOpenAlexAuthorId(orcid, openAlexAuthorId);
        ScholardexAuthorFact author = lookupById(canonicalId, ctx);
        if (author == null) {
            author = new ScholardexAuthorFact();
        }
        Instant now = Instant.now();
        if (author.getCreatedAt() == null) {
            author.setCreatedAt(now);
        }
        author.setId(canonicalId);
        addUnique(author.getOrcidIds(), orcid);
        addUnique(author.getOpenAlexAuthorIds(), openAlexAuthorId);
        addAffiliationNames(author, affiliationNames);
        if (isBlank(author.getDisplayName()) && !isBlank(displayName)) {
            author.setDisplayName(displayName);
            author.setNameNormalized(CanonicalizationSupport.normalizeToken(displayName));
        }
        author.setSource(SOURCE_OPENALEX);
        author.setSourceRecordId(orcid != null ? "orcid:" + orcid : "openalex:" + openAlexAuthorId);
        author.setSourceBatchId(batchId);
        author.setSourceCorrelationId(correlationId);
        author.setUpdatedAt(now);
        persist(author, ctx, true);
        return canonicalId;
    }

    /** Seed an ORCID onto an existing canonical author (the syncing researcher's). Idempotent. */
    public void attachOrcid(String canonicalAuthorId, String orcid) {
        attachOrcid(canonicalAuthorId, orcid, null);
    }

    public void attachOrcid(String canonicalAuthorId, String orcid, BulkContext ctx) {
        if (isBlank(canonicalAuthorId) || isBlank(orcid)) {
            return;
        }
        ScholardexAuthorFact author = lookupById(canonicalAuthorId, ctx);
        if (author != null && addUnique(author.getOrcidIds(), orcid)) {
            author.setUpdatedAt(Instant.now());
            registerKey(ctx, orcid, null, author);
            persist(author, ctx, false);
        }
    }

    public int bridgeOrcidsByPosition(List<String> scopusAuthorIds, List<String> openAlexNames, List<String> openAlexOrcids) {
        return bridgeOrcidsByPosition(scopusAuthorIds, openAlexNames, openAlexOrcids, null);
    }

    /**
     * Positionally bridge OpenAlex ORCIDs onto the Scopus authors of a DOI-linked publication. Scopus and OpenAlex
     * agree on author order, so author {@code i} on each side is the same person. Guards: equal author count and a
     * per-position surname match. Returns ORCIDs seeded.
     */
    public int bridgeOrcidsByPosition(List<String> scopusAuthorIds, List<String> openAlexNames, List<String> openAlexOrcids,
                                      BulkContext ctx) {
        if (scopusAuthorIds == null || openAlexNames == null || openAlexOrcids == null) {
            return 0;
        }
        int n = scopusAuthorIds.size();
        if (n == 0 || openAlexNames.size() != n || openAlexOrcids.size() != n) {
            return 0; // count guard — when the lists differ in length, positions can't be trusted.
        }
        Map<String, ScholardexAuthorFact> byId = new HashMap<>();
        if (ctx != null) {
            for (String id : scopusAuthorIds) {
                ScholardexAuthorFact a = ctx.byId.get(id);
                if (a != null) {
                    byId.put(id, a);
                }
            }
        } else {
            authorRepository.findByIdIn(scopusAuthorIds).forEach(a -> byId.put(a.getId(), a));
        }
        int seeded = 0;
        int nameMismatch = 0;
        for (int i = 0; i < n; i++) {
            String orcid = blankToNull(openAlexOrcids.get(i));
            if (orcid == null) {
                continue;
            }
            ScholardexAuthorFact scopusAuthor = byId.get(scopusAuthorIds.get(i));
            if (scopusAuthor == null) {
                continue;
            }
            if (!surnameMatches(scopusAuthor.getDisplayName(), openAlexNames.get(i))) {
                nameMismatch++;
                continue; // per-position verification failed — don't seed a possibly-misaligned position.
            }
            if (addUnique(scopusAuthor.getOrcidIds(), orcid)) {
                scopusAuthor.setUpdatedAt(Instant.now());
                registerKey(ctx, orcid, null, scopusAuthor);
                persist(scopusAuthor, ctx, false);
                seeded++;
            }
        }
        if (seeded > 0 || nameMismatch > 0) {
            log.info("OpenAlex ORCID bridge: seeded {} of {} authors onto Scopus authors (positionMismatches={})",
                    seeded, n, nameMismatch);
        }
        return seeded;
    }

    /** Surname agreement: Scopus "Last, First" surname vs OpenAlex "First Last" final token, normalization-insensitive. */
    public static boolean surnameMatches(String scopusName, String openAlexName) {
        String a = normalizeSurname(scopusSurname(scopusName));
        String b = normalizeSurname(openAlexSurname(openAlexName));
        return !a.isEmpty() && a.equals(b);
    }

    private static String scopusSurname(String name) {
        if (isBlank(name)) {
            return "";
        }
        int comma = name.indexOf(',');
        if (comma >= 0) {
            return name.substring(0, comma);
        }
        String[] parts = name.trim().split("\\s+");
        return parts.length == 0 ? "" : parts[parts.length - 1];
    }

    private static String openAlexSurname(String name) {
        if (isBlank(name)) {
            return "";
        }
        String[] parts = name.trim().split("\\s+");
        return parts.length == 0 ? "" : parts[parts.length - 1];
    }

    private static String normalizeSurname(String value) {
        if (value == null) {
            return "";
        }
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD);
        normalized = normalized.replaceAll("\\p{M}+", "");
        return normalized.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ── lookup / persist (repo when ctx==null; in-memory cache otherwise) ─────

    private ScholardexAuthorFact lookupByOrcid(String orcid, BulkContext ctx) {
        if (ctx != null) {
            return ctx.byOrcid.get(orcid);
        }
        return preferEstablished(authorRepository.findByOrcidIdsContains(orcid));
    }

    private ScholardexAuthorFact lookupByOpenAlex(String openAlexAuthorId, BulkContext ctx) {
        if (ctx != null) {
            return ctx.byOpenAlex.get(openAlexAuthorId);
        }
        return preferEstablished(authorRepository.findByOpenAlexAuthorIdsContains(openAlexAuthorId));
    }

    private ScholardexAuthorFact lookupById(String canonicalId, BulkContext ctx) {
        if (ctx != null) {
            return ctx.byId.get(canonicalId);
        }
        return authorRepository.findById(canonicalId).orElse(null);
    }

    /** Persist a mint/enrich: defer to the bulk dirty set, or save immediately. {@code newlyIndexed} re-indexes a mint. */
    private void persist(ScholardexAuthorFact author, BulkContext ctx, boolean newlyIndexed) {
        if (ctx != null) {
            if (newlyIndexed) {
                ctx.index(author);
            }
            ctx.markDirty(author);
        } else {
            authorRepository.save(author);
        }
    }

    /** Keep the bulk index consistent when a new id key is attached to an existing author. */
    private void registerKey(BulkContext ctx, String orcid, String openAlexAuthorId, ScholardexAuthorFact author) {
        if (ctx == null) {
            return;
        }
        if (!isBlank(orcid)) {
            ctx.byOrcid.putIfAbsent(orcid, author);
        }
        if (!isBlank(openAlexAuthorId)) {
            ctx.byOpenAlex.putIfAbsent(openAlexAuthorId, author);
        }
    }

    /**
     * When an id key resolves to several authors (a transient duplicate awaiting reconcile), prefer the established
     * Scopus author (non-empty scopusAuthorIds); otherwise the first. {@code null} if the list is empty.
     */
    private static ScholardexAuthorFact preferEstablished(List<ScholardexAuthorFact> matches) {
        if (matches == null || matches.isEmpty()) {
            return null;
        }
        return matches.stream()
                .filter(a -> a.getScopusAuthorIds() != null && !a.getScopusAuthorIds().isEmpty())
                .findFirst()
                .orElse(matches.getFirst());
    }

    /** Attach any missing id keys to a matched author and persist if changed; returns its id. */
    private String enrich(ScholardexAuthorFact author, String orcid, String openAlexAuthorId,
                          List<String> affiliationNames, BulkContext ctx) {
        boolean orcidAdded = addUnique(author.getOrcidIds(), orcid);
        boolean openAlexAdded = addUnique(author.getOpenAlexAuthorIds(), openAlexAuthorId);
        boolean changed = orcidAdded || openAlexAdded;
        changed |= addAffiliationNames(author, affiliationNames);
        if (changed) {
            registerKey(ctx, orcidAdded ? orcid : null, openAlexAdded ? openAlexAuthorId : null, author);
            author.setUpdatedAt(Instant.now());
            persist(author, ctx, false);
        }
        return author.getId();
    }

    /** H71: accumulate OpenAlex institution display names onto the author (deduped, blanks skipped). */
    private boolean addAffiliationNames(ScholardexAuthorFact author, List<String> affiliationNames) {
        if (affiliationNames == null || affiliationNames.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (String name : affiliationNames) {
            if (!isBlank(name)) {
                changed |= addUnique(author.getOpenAlexAffiliationNames(), name.trim());
            }
        }
        return changed;
    }

    private String buildOpenAlexAuthorId(String orcid, String openAlexAuthorId) {
        String key = orcid != null
                ? "orcid|" + orcid.toLowerCase(Locale.ROOT)
                : "openalex|" + openAlexAuthorId;
        return "sauth_" + CanonicalizationSupport.shortHash(key);
    }

    private static boolean addUnique(List<String> list, String value) {
        if (isBlank(value) || list == null) {
            return false;
        }
        if (list.contains(value)) {
            return false;
        }
        list.add(value);
        return true;
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
