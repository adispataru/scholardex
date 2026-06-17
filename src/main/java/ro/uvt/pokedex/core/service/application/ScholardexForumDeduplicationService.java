package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.service.importing.BuilderVersion;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * H55.5 — collapse canonical Scholardex forums that are the same journal split across multiple
 * {@code sforum_} ids (e.g. a full-name forum + an abbreviated-name WoS-only forum that share an ISSN
 * but hashed to different ids because their ISSN sets differed). Deterministic and idempotent.
 *
 * <p><b>Safe merge rule (validated against the full dataset, H55 doc):</b> a cluster of forums sharing
 * any normalised ISSN token is merged only when its members <i>either</i> share a single primary
 * (print) ISSN <i>or</i> their names are an abbreviation/expansion match. Clusters bridged only by a
 * shared eISSN/alias with mismatched names — journal continuations, or genuine cross-journal ISSN data
 * errors like SIAM J. Computing vs SIAM J. Mathematical Analysis — are <b>not</b> auto-merged; an
 * identity conflict is opened so they surface on {@code /admin/conflicts} for human resolution.
 */
@Service
@RequiredArgsConstructor
public class ScholardexForumDeduplicationService {

    private static final Logger log = LoggerFactory.getLogger(ScholardexForumDeduplicationService.class);

    private static final String STATUS_OPEN = "OPEN";
    private static final String SOURCE_SCHOLARDEX = "SCHOLARDEX";
    private static final String REASON_DEDUP_NAME_MISMATCH = "FORUM_DEDUP_NAME_MISMATCH";

    private static final Pattern ISSN_NON_ALNUM = Pattern.compile("[^0-9Xx]");

    private final ScholardexForumFactRepository forumRepository;
    private final ScholardexSourceLinkRepository sourceLinkRepository;
    private final ScholardexPublicationFactRepository publicationRepository;
    private final ScholardexIdentityConflictRepository identityConflictRepository;
    private final ForumMergeSafetyRule mergeSafetyRule;

    public ImportProcessingResult deduplicateForums(String batchId, String correlationId) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        List<ScholardexForumFact> forums = new ArrayList<>(forumRepository.findAll());
        List<List<ScholardexForumFact>> clusters = clusterBySharedIssnToken(forums);
        Instant now = Instant.now();

        for (List<ScholardexForumFact> cluster : clusters) {
            if (cluster.size() < 2) {
                continue;
            }
            result.markProcessed();
            if (mergeSafetyRule.isSafeToMergeCluster(cluster)) {
                mergeCluster(cluster, now, result);
            } else {
                quarantineCluster(cluster, batchId, correlationId, now, result);
            }
        }
        log.info("Forum dedup complete: clusters={} merged(updated)={} quarantined(skipped)={} forumsDeleted~={}",
                result.getProcessedCount(), result.getUpdatedCount(), result.getSkippedCount(), result.getImportedCount());
        return result;
    }

    // ---- clustering -------------------------------------------------------------------------------

    private List<List<ScholardexForumFact>> clusterBySharedIssnToken(List<ScholardexForumFact> forums) {
        Map<String, String> parent = new LinkedHashMap<>();
        for (ScholardexForumFact f : forums) {
            parent.put(f.getId(), f.getId());
        }
        Map<String, String> tokenOwner = new LinkedHashMap<>();
        for (ScholardexForumFact f : forums) {
            for (String token : identityTokens(f)) {
                String prior = tokenOwner.get(token);
                if (prior != null) {
                    union(parent, prior, f.getId());
                } else {
                    tokenOwner.put(token, f.getId());
                }
            }
        }
        Map<String, List<ScholardexForumFact>> byRoot = new LinkedHashMap<>();
        for (ScholardexForumFact f : forums) {
            byRoot.computeIfAbsent(find(parent, f.getId()), k -> new ArrayList<>()).add(f);
        }
        return new ArrayList<>(byRoot.values());
    }

    private String find(Map<String, String> parent, String x) {
        String r = x;
        while (!parent.get(r).equals(r)) {
            r = parent.get(r);
        }
        // path-compress
        while (!parent.get(x).equals(r)) {
            String next = parent.get(x);
            parent.put(x, r);
            x = next;
        }
        return r;
    }

    private void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a);
        String rb = find(parent, b);
        if (!ra.equals(rb)) {
            parent.put(ra, rb);
        }
    }

    // ---- safe-merge decision: see ForumMergeSafetyRule (shared with fold-time canonicalization) -----

    // ---- merge ------------------------------------------------------------------------------------

    private void mergeCluster(List<ScholardexForumFact> cluster, Instant now, ImportProcessingResult result) {
        ScholardexForumFact winner = pickWinner(cluster);
        List<ScholardexForumFact> losers = cluster.stream()
                .filter(f -> !f.getId().equals(winner.getId()))
                .sorted(Comparator.comparing(ScholardexForumFact::getId))
                .toList();

        LinkedHashSet<String> scopusIds = new LinkedHashSet<>(safe(winner.getScopusForumIds()));
        LinkedHashSet<String> wosIds = new LinkedHashSet<>(safe(winner.getWosForumIds()));
        LinkedHashSet<String> gsIds = new LinkedHashSet<>(safe(winner.getGoogleScholarForumIds()));
        LinkedHashSet<String> userIds = new LinkedHashSet<>(safe(winner.getUserSourceForumIds()));
        LinkedHashSet<String> erihIds = new LinkedHashSet<>(safe(winner.getErihIds()));
        // alias ISSNs retain their original (hyphenated) string; we de-duplicate by normalized form so
        // we never store the same ISSN twice or drop the winner's own primary/eISSN.
        Map<String, String> aliasByNorm = new LinkedHashMap<>();
        for (String a : safe(winner.getAliasIssns())) {
            putAlias(aliasByNorm, a);
        }

        for (ScholardexForumFact loser : losers) {
            scopusIds.addAll(safe(loser.getScopusForumIds()));
            wosIds.addAll(safe(loser.getWosForumIds()));
            gsIds.addAll(safe(loser.getGoogleScholarForumIds()));
            userIds.addAll(safe(loser.getUserSourceForumIds()));
            erihIds.addAll(safe(loser.getErihIds()));
            // fold the loser's ISSNs (the user-requested alias retention) into the winner aliases
            putAlias(aliasByNorm, loser.getIssn());
            putAlias(aliasByNorm, loser.getEIssn());
            for (String a : safe(loser.getAliasIssns())) {
                putAlias(aliasByNorm, a);
            }
            repointReferences(loser.getId(), winner.getId());
            forumRepository.deleteById(loser.getId());
            result.markImported(); // count of forums removed
        }

        // never let the winner's own primary issn/eIssn sit in its alias list
        aliasByNorm.remove(normalizeIssn(winner.getIssn()));
        aliasByNorm.remove(normalizeIssn(winner.getEIssn()));

        winner.setScopusForumIds(sorted(scopusIds));
        winner.setWosForumIds(sorted(wosIds));
        winner.setGoogleScholarForumIds(sorted(gsIds));
        winner.setUserSourceForumIds(sorted(userIds));
        winner.setErihIds(sorted(erihIds));
        winner.setAliasIssns(sorted(aliasByNorm.values()));
        winner.setBuilderVersion(BuilderVersion.SCHOLARDEX_FORUM);
        winner.setUpdatedAt(now);
        forumRepository.save(winner);
        result.markUpdated();
    }

    /**
     * Deterministic winner: most Scopus links (publications point there), then most ISSN tokens, then
     * lexicographically smallest id. Order-independent so rebuilds converge identically.
     */
    private ScholardexForumFact pickWinner(List<ScholardexForumFact> cluster) {
        return cluster.stream()
                .min(Comparator
                        .comparingInt((ScholardexForumFact f) -> safe(f.getScopusForumIds()).size()).reversed()
                        .thenComparing(Comparator.comparingInt((ScholardexForumFact f) -> issnTokens(f).size()).reversed())
                        .thenComparing(ScholardexForumFact::getId))
                .orElseThrow();
    }

    private void repointReferences(String loserId, String winnerId) {
        // Source links: the unique key is (entityType, source, sourceRecordId); only the value changes.
        List<ScholardexSourceLink> links = sourceLinkRepository
                .findByEntityTypeAndCanonicalEntityId(ScholardexEntityType.FORUM, loserId);
        for (ScholardexSourceLink link : links) {
            link.setCanonicalEntityId(winnerId);
            sourceLinkRepository.save(link);
        }
        // Publications (expected 0 for WoS-only losers, but handle generally).
        List<ScholardexPublicationFact> pubs = publicationRepository.findByForumId(loserId);
        for (ScholardexPublicationFact pub : pubs) {
            pub.setForumId(winnerId);
            publicationRepository.save(pub);
        }
        if (!pubs.isEmpty()) {
            log.info("Forum dedup re-pointed {} publication(s) from {} to {}", pubs.size(), loserId, winnerId);
        }
    }

    // ---- quarantine -------------------------------------------------------------------------------

    private void quarantineCluster(
            List<ScholardexForumFact> cluster,
            String batchId,
            String correlationId,
            Instant now,
            ImportProcessingResult result
    ) {
        List<String> candidateIds = cluster.stream()
                .map(ScholardexForumFact::getId)
                .sorted()
                .toList();
        String key = String.join("|", candidateIds); // stable across runs so the conflict de-dupes
        ScholardexIdentityConflict conflict = identityConflictRepository
                .findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                        ScholardexEntityType.FORUM, SOURCE_SCHOLARDEX, key, REASON_DEDUP_NAME_MISMATCH, STATUS_OPEN)
                .orElseGet(ScholardexIdentityConflict::new);
        conflict.setEntityType(ScholardexEntityType.FORUM);
        conflict.setIncomingSource(SOURCE_SCHOLARDEX);
        conflict.setIncomingSourceRecordId(key);
        conflict.setReasonCode(REASON_DEDUP_NAME_MISMATCH);
        conflict.setStatus(STATUS_OPEN);
        conflict.setCandidateCanonicalIds(new ArrayList<>(candidateIds));
        conflict.setSourceBatchId(batchId);
        conflict.setSourceCorrelationId(correlationId);
        if (conflict.getDetectedAt() == null) {
            conflict.setDetectedAt(now);
        }
        identityConflictRepository.save(conflict);
        result.markSkipped("forum-dedup-quarantine candidates=" + key);
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /**
     * Identity tokens that bind forums into a dedup cluster: normalized ISSN tokens plus prefixed external
     * ids (ERIH+, H66 C1 part 2). Two forums sharing an erihId — the split-journal signal written by
     * {@code ErihOnboardingService} — are clustered and then safe-merged via {@link ForumMergeSafetyRule}.
     * The {@code erih:} prefix keeps the namespaces disjoint from ISSN tokens.
     */
    private LinkedHashSet<String> identityTokens(ScholardexForumFact f) {
        LinkedHashSet<String> out = issnTokens(f);
        if (f.getErihIds() != null) {
            for (String erihId : f.getErihIds()) {
                if (erihId != null && !erihId.isBlank()) {
                    out.add("erih:" + erihId.trim());
                }
            }
        }
        return out;
    }

    private LinkedHashSet<String> issnTokens(ScholardexForumFact f) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        addIssn(out, f.getIssn());
        addIssn(out, f.getEIssn());
        for (String a : safe(f.getAliasIssns())) {
            addIssn(out, a);
        }
        return out;
    }

    private void addIssn(LinkedHashSet<String> out, String raw) {
        String n = normalizeIssn(raw);
        if (n != null) {
            out.add(n);
        }
    }

    /** Record an alias by its normalized key while preserving the original (hyphenated) string. */
    private void putAlias(Map<String, String> aliasByNorm, String raw) {
        String n = normalizeIssn(raw);
        if (n != null && raw != null && !raw.isBlank()) {
            aliasByNorm.putIfAbsent(n, raw.trim());
        }
    }

    private String normalizeIssn(String raw) {
        if (raw == null) {
            return null;
        }
        String compact = ISSN_NON_ALNUM.matcher(raw).replaceAll("").toUpperCase(Locale.ROOT);
        return compact.length() == 8 ? compact : (compact.isEmpty() ? null : compact);
    }

    private List<String> safe(List<String> in) {
        return in == null ? List.of() : in;
    }

    private List<String> sorted(Collection<String> in) {
        List<String> out = new ArrayList<>(in);
        out.sort(Comparator.naturalOrder());
        return out;
    }
}
