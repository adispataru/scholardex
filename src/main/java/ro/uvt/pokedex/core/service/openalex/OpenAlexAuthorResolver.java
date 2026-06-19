package ro.uvt.pokedex.core.service.openalex;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * H66B Phase 4a — resolve an OpenAlex corresponding author to a canonical {@link ScholardexAuthorFact} by its
 * cross-source id keys (ORCID, then OpenAlex author id), minting one when nothing matches (the "mint + reconcile
 * later" policy — OpenAlex becomes an author source). Also seeds an ORCID onto an existing canonical author (the
 * syncing researcher's Scopus-derived author) so corresponding-author resolution dedups against them instead of
 * minting a duplicate. Name-only refs (no ORCID, no OpenAlex id) are not id-resolvable and return {@code null}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAlexAuthorResolver {

    static final String SOURCE_OPENALEX = "OPENALEX";

    private final ScholardexAuthorFactRepository authorRepository;

    /** Find-or-mint a canonical author by its id keys; {@code null} if it has no id-resolvable identity. */
    public String resolveOrMint(String displayName, String orcidRaw, String openAlexAuthorIdRaw, String batchId, String correlationId) {
        String orcid = blankToNull(orcidRaw);
        String openAlexAuthorId = blankToNull(openAlexAuthorIdRaw);

        if (orcid != null) {
            ScholardexAuthorFact byOrcid = preferEstablished(authorRepository.findByOrcidIdsContains(orcid));
            if (byOrcid != null) {
                return enrich(byOrcid, orcid, openAlexAuthorId);
            }
        }
        if (openAlexAuthorId != null) {
            ScholardexAuthorFact byOpenAlex = preferEstablished(authorRepository.findByOpenAlexAuthorIdsContains(openAlexAuthorId));
            if (byOpenAlex != null) {
                return enrich(byOpenAlex, orcid, openAlexAuthorId);
            }
        }
        if (orcid == null && openAlexAuthorId == null) {
            return null; // name-only — not id-resolvable under the id-based model.
        }

        // MINT (mint + reconcile policy). Empty scopusAuthorIds keeps it out of the partial-filter unique index.
        String canonicalId = buildOpenAlexAuthorId(orcid, openAlexAuthorId);
        ScholardexAuthorFact author = authorRepository.findById(canonicalId).orElseGet(ScholardexAuthorFact::new);
        Instant now = Instant.now();
        if (author.getCreatedAt() == null) {
            author.setCreatedAt(now);
        }
        author.setId(canonicalId);
        addUnique(author.getOrcidIds(), orcid);
        addUnique(author.getOpenAlexAuthorIds(), openAlexAuthorId);
        if (isBlank(author.getDisplayName()) && !isBlank(displayName)) {
            author.setDisplayName(displayName);
            author.setNameNormalized(CanonicalizationSupport.normalizeToken(displayName));
        }
        author.setSource(SOURCE_OPENALEX);
        author.setSourceRecordId(orcid != null ? "orcid:" + orcid : "openalex:" + openAlexAuthorId);
        author.setSourceBatchId(batchId);
        author.setSourceCorrelationId(correlationId);
        author.setUpdatedAt(now);
        authorRepository.save(author);
        return canonicalId;
    }

    /**
     * Seed an ORCID onto an existing canonical author (the syncing researcher's). Idempotent; lets a later
     * corresponding-author resolution find this author by ORCID rather than mint a duplicate.
     */
    public void attachOrcid(String canonicalAuthorId, String orcid) {
        if (isBlank(canonicalAuthorId) || isBlank(orcid)) {
            return;
        }
        authorRepository.findById(canonicalAuthorId).ifPresent(author -> {
            if (addUnique(author.getOrcidIds(), orcid)) {
                author.setUpdatedAt(Instant.now());
                authorRepository.save(author);
            }
        });
    }

    /**
     * Positionally bridge OpenAlex ORCIDs onto the Scopus authors of a DOI-linked publication. Scopus and OpenAlex
     * agree on author order (validated 29/29), so author {@code i} on each side is the same person — seed
     * {@code openAlexOrcids[i]} onto the canonical author {@code scopusAuthorIds[i]}. Guards: equal author count
     * (catches "et al." truncation / differing indexing) and a per-position surname match (catches the rare reorder
     * and handles Scopus "Last, First" vs OpenAlex "First Last", diacritics, hyphen variants). Returns ORCIDs seeded.
     */
    public int bridgeOrcidsByPosition(List<String> scopusAuthorIds, List<String> openAlexNames, List<String> openAlexOrcids) {
        if (scopusAuthorIds == null || openAlexNames == null || openAlexOrcids == null) {
            return 0;
        }
        int n = scopusAuthorIds.size();
        if (n == 0 || openAlexNames.size() != n || openAlexOrcids.size() != n) {
            return 0; // count guard — when the lists differ in length, positions can't be trusted.
        }
        java.util.Map<String, ScholardexAuthorFact> byId = new java.util.HashMap<>();
        authorRepository.findByIdIn(scopusAuthorIds).forEach(a -> byId.put(a.getId(), a));
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
                authorRepository.save(scopusAuthor);
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
    static boolean surnameMatches(String scopusName, String openAlexName) {
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

    /**
     * When an id key resolves to several authors (a transient duplicate awaiting reconcile), prefer the established
     * Scopus author (non-empty scopusAuthorIds) so OpenAlex data attaches to the real canonical author rather than
     * a freshly-minted OpenAlex twin; otherwise the first. {@code null} if the list is empty.
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
    private String enrich(ScholardexAuthorFact author, String orcid, String openAlexAuthorId) {
        boolean changed = addUnique(author.getOrcidIds(), orcid);
        changed |= addUnique(author.getOpenAlexAuthorIds(), openAlexAuthorId);
        if (changed) {
            author.setUpdatedAt(Instant.now());
            authorRepository.save(author);
        }
        return author.getId();
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
