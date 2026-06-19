package ro.uvt.pokedex.core.service.openalex;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
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
@Service
@RequiredArgsConstructor
public class OpenAlexAuthorResolver {

    static final String SOURCE_OPENALEX = "OPENALEX";

    private final ScholardexAuthorFactRepository authorRepository;

    /** Find-or-mint a canonical author for a corresponding-author ref; {@code null} if it has no id-resolvable identity. */
    public String resolveOrMint(OpenAlexPublicationFact.CorrespondingAuthorRef ref, String batchId, String correlationId) {
        if (ref == null) {
            return null;
        }
        String orcid = blankToNull(ref.getOrcid());
        String openAlexAuthorId = blankToNull(ref.getOpenAlexAuthorId());

        if (orcid != null) {
            Optional<ScholardexAuthorFact> byOrcid = authorRepository.findByOrcidIdsContains(orcid);
            if (byOrcid.isPresent()) {
                return enrich(byOrcid.get(), orcid, openAlexAuthorId);
            }
        }
        if (openAlexAuthorId != null) {
            Optional<ScholardexAuthorFact> byOpenAlex = authorRepository.findByOpenAlexAuthorIdsContains(openAlexAuthorId);
            if (byOpenAlex.isPresent()) {
                return enrich(byOpenAlex.get(), orcid, openAlexAuthorId);
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
        if (isBlank(author.getDisplayName()) && !isBlank(ref.getDisplayName())) {
            author.setDisplayName(ref.getDisplayName());
            author.setNameNormalized(CanonicalizationSupport.normalizeToken(ref.getDisplayName()));
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
