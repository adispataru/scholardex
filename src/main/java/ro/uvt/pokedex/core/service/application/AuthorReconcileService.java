package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * H66B Phase 4a — Tier-1 author reconcile (dedup). Merges canonical {@link ScholardexAuthorFact} records that are
 * the same person, mirroring {@link ScholardexForumDeduplicationService}: pick a survivor, fold the losers' id
 * lists / names / affiliations in, re-point every reference, delete the losers.
 *
 * <p>Stage A is the high-precision ORCID pass: cluster authors by shared ORCID and merge each cluster, since an
 * ORCID is unique to a person. A shared ORCID whose members have <i>incompatible</i> names (different given names —
 * the symptom of a bad ORCID seed or upstream error) is NOT auto-merged; it is quarantined as an
 * {@code AUTHOR_SHARED_ORCID_NAME_MISMATCH} conflict for review.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorReconcileService {

    static final String SOURCE_SCHOLARDEX = "SCHOLARDEX";
    static final String REASON_SHARED_ORCID_NAME_MISMATCH = "AUTHOR_SHARED_ORCID_NAME_MISMATCH";
    static final String REASON_FUZZY_MERGE_CANDIDATE = "AUTHOR_FUZZY_MERGE_CANDIDATE";
    static final String REASON_FUZZY_MERGE_REVIEW = "AUTHOR_FUZZY_MERGE_REVIEW";
    static final String REASON_OVERSPLIT_MERGE_CANDIDATE = "AUTHOR_OVERSPLIT_MERGE_CANDIDATE";
    static final String REASON_OVERSPLIT_MERGE_REVIEW = "AUTHOR_OVERSPLIT_MERGE_REVIEW";
    private static final String STATUS_OPEN = "OPEN";

    private final ScholardexAuthorFactRepository authorRepository;
    private final ScholardexAuthorshipFactRepository authorshipRepository;
    private final ScholardexPublicationAuthorAffiliationFactRepository pubAuthorAffiliationRepository;
    private final ScholardexAuthorAffiliationFactRepository authorAffiliationRepository;
    private final ScholardexPublicationFactRepository publicationRepository;
    private final ScholardexSourceLinkRepository sourceLinkRepository;
    private final UserRepository userRepository;
    private final OpenAlexPublicationFactRepository openAlexPublicationFactRepository;
    private final ScholardexIdentityConflictRepository identityConflictRepository;

    /**
     * Fuzzy pass: when {@code true} actually merge corroborated same-name clusters; when {@code false} (default)
     * run in dry-run mode — only emit {@code AUTHOR_FUZZY_MERGE_CANDIDATE} conflicts so the merges can be inspected
     * on real data before auto-merge is enabled (the false-merge risk at 216k authors warrants this).
     */
    @org.springframework.beans.factory.annotation.Value("${core.author-reconcile.fuzzy-apply:false}")
    private boolean fuzzyApply;

    /**
     * Shared-co-author count at/above which two same-name authors are confidently the same person (auto-merge).
     * The decisive corroborator: empirically, same name + same institution but zero co-author overlap is two
     * different people (e.g. "Chen, Xi" ×7), while a high overlap is the same person even for initials-only names
     * (e.g. "Bickley, A. A." sharing 127 co-authors).
     */
    @org.springframework.beans.factory.annotation.Value("${core.author-reconcile.coauthor-strong-threshold:3}")
    private int coauthorStrongThreshold;

    /** Same-name clusters larger than this are not auto-disambiguated (bounds the O(k²) cost) — reviewed instead. */
    @org.springframework.beans.factory.annotation.Value("${core.author-reconcile.max-cluster-size:50}")
    private int maxClusterSize;

    /**
     * H72 slice 2 over-split pass: when {@code true} merge same-name AU-IDs sharing a verified affiliation + ≥1
     * co-author; when {@code false} (default) dry-run — emit {@code AUTHOR_OVERSPLIT_MERGE_CANDIDATE} conflicts for
     * inspection before auto-merge is enabled.
     */
    @org.springframework.beans.factory.annotation.Value("${core.author-reconcile.affiliation-apply:false}")
    private boolean affiliationApply;

    /**
     * H72 slice 2: a co-author drawn from a paper with more than this many authors does not count toward the
     * over-split shared-co-author signal. Mega-author papers (HEP collaborations, hundreds–thousands of authors)
     * make everyone trivially "co-author" everyone, which manufactured false merges of common initials-only names
     * ("Wang, J." ×6 shared 96 → 0 once excluded). Real small-team collaboration is the meaningful identity signal.
     */
    @org.springframework.beans.factory.annotation.Value("${core.author-reconcile.coauthor-max-paper-authors:20}")
    private int coauthorMaxPaperAuthors;

    /**
     * H99: explicit two-author merge for a human-confirmed split identity that no automatic pass can reach —
     * e.g. a researcher's OpenAlex-half ghost (ORCID + OpenAlex ids, no Scopus id) beside their Scopus-half
     * canonical, where the display names parse incompatibly ("Fortis T.-F." vs "Teodor-Florin Fortiş") so the
     * ORCID pass would quarantine even after seeding. The admin asserts identity; no name gate is applied. The
     * winner is picked by the same deterministic rule as every reconcile pass (most Scopus ids → most id keys
     * → smallest id), and all references are repointed by the shared merge machinery.
     *
     * @return the surviving author's id
     * @throws IllegalArgumentException when the ids are equal or either author does not exist
     */
    public String mergePair(String idA, String idB) {
        if (idA == null || idB == null || idA.isBlank() || idB.isBlank() || idA.trim().equals(idB.trim())) {
            throw new IllegalArgumentException("two distinct author ids are required");
        }
        ScholardexAuthorFact a = authorRepository.findById(idA.trim())
                .orElseThrow(() -> new IllegalArgumentException("author not found: " + idA));
        ScholardexAuthorFact b = authorRepository.findById(idB.trim())
                .orElseThrow(() -> new IllegalArgumentException("author not found: " + idB));
        List<ScholardexAuthorFact> cluster = List.of(a, b);
        String winnerId = pickWinner(cluster).getId();
        ImportProcessingResult result = new ImportProcessingResult(20);
        mergeCluster(cluster, Instant.now(), result);
        log.info("Author explicit merge: {} + {} -> {}", idA, idB, winnerId);
        return winnerId;
    }

    /** ORCID pass: merge canonical authors that share an ORCID (name-compatible), quarantine the rest. */
    public ImportProcessingResult reconcileByOrcid(String batchId, String correlationId) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        Instant now = Instant.now();

        // Cluster authors by each ORCID they carry (a deterministic ordering keeps rebuilds convergent).
        Map<String, List<ScholardexAuthorFact>> byOrcid = new TreeMap<>();
        for (ScholardexAuthorFact author : authorRepository.findAll()) {
            if (author.getOrcidIds() == null) {
                continue;
            }
            for (String orcid : author.getOrcidIds()) {
                if (orcid != null && !orcid.isBlank()) {
                    byOrcid.computeIfAbsent(orcid.trim(), k -> new ArrayList<>()).add(author);
                }
            }
        }

        for (Map.Entry<String, List<ScholardexAuthorFact>> entry : byOrcid.entrySet()) {
            List<ScholardexAuthorFact> cluster = dedupById(entry.getValue());
            if (cluster.size() < 2) {
                continue;
            }
            result.markProcessed();
            if (clusterNamesCompatible(cluster)) {
                mergeCluster(cluster, now, result);
            } else {
                quarantine(cluster, REASON_SHARED_ORCID_NAME_MISMATCH, batchId, correlationId, now, result);
            }
        }
        log.info("Author reconcile (ORCID pass) complete: orcidClusters={} merged~={} quarantined={}",
                result.getProcessedCount(), result.getImportedCount(), result.getSkippedCount());
        return result;
    }

    /**
     * Fuzzy pass: cluster authors by normalized full name (order-insensitive), and within each cluster merge the
     * subgroups that <b>corroborate</b> (share ≥1 affiliation) and pass the <b>hard block</b> (no two members
     * co-appear on the same publication — that proves they are different people). Same-name authors with no
     * corroboration are left alone. Dry-run unless {@code core.author-reconcile.fuzzy-apply=true}: candidates are
     * reported as {@code AUTHOR_FUZZY_MERGE_CANDIDATE} conflicts instead of merged.
     */
    public ImportProcessingResult reconcileByName(String batchId, String correlationId) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        Instant now = Instant.now();

        Map<String, List<ScholardexAuthorFact>> byName = new TreeMap<>();
        for (ScholardexAuthorFact author : authorRepository.findAll()) {
            String key = nameKey(author.getDisplayName());
            if (!key.isEmpty()) {
                byName.computeIfAbsent(key, k -> new ArrayList<>()).add(author);
            }
        }

        int strongGroups = 0;
        int mediumPairs = 0;
        int oversized = 0;
        for (List<ScholardexAuthorFact> cluster : byName.values()) {
            List<ScholardexAuthorFact> members = dedupById(cluster);
            if (members.size() < 2) {
                continue;
            }
            if (members.size() > maxClusterSize) {
                // Too ambiguous (e.g. a very common name) to auto-disambiguate in one pass — flag for review.
                quarantine(members, REASON_FUZZY_MERGE_REVIEW, batchId, correlationId, now, result);
                oversized++;
                continue;
            }
            ClusterAnalysis analysis = analyzeCluster(members);
            // STRONG: high co-author overlap -> confidently the same person.
            for (List<ScholardexAuthorFact> group : analysis.strongGroups()) {
                result.markProcessed();
                strongGroups++;
                if (fuzzyApply) {
                    mergeCluster(group, now, result);
                } else {
                    quarantine(group, REASON_FUZZY_MERGE_CANDIDATE, batchId, correlationId, now, result);
                }
            }
            // MEDIUM: some overlap but below the bar -> always queued for human review, never auto-merged.
            for (List<ScholardexAuthorFact> pair : analysis.mediumPairs()) {
                mediumPairs++;
                quarantine(pair, REASON_FUZZY_MERGE_REVIEW, batchId, correlationId, now, result);
            }
            // REJECT (zero co-author overlap) -> dropped; same name + institution is not enough.
        }
        log.info("Author reconcile (fuzzy name pass, apply={}, coauthorStrongThreshold={}) complete: "
                        + "nameClusters={} strongGroups={} mediumPairs={} oversizedClusters={} merged~={} quarantined={}",
                fuzzyApply, coauthorStrongThreshold, byName.size(), strongGroups, mediumPairs, oversized,
                result.getImportedCount(), result.getSkippedCount());
        return result;
    }

    /**
     * H72 slice 2 — over-split merge pass. Scopus over-splits one researcher into an old + new AU-ID; after the
     * verified-only affiliation cut (slice 1) those ids reliably share a real institution. Cluster by normalized
     * name, then merge subgroups that share BOTH an affiliation AND ≥1 co-author.
     *
     * <p>Two deliberate departures from the fuzzy pass:
     * <ul>
     *   <li><b>No same-publication hard block.</b> Over-split ids co-appear on a paper (the same person listed
     *       twice — verified empirically: Megan/Cotăescu), which the hard block would misread as "different people".</li>
     *   <li><b>Affiliation + ≥1 co-author is the signal.</b> The ≥1 shared-co-author requirement excludes the
     *       "Chen, Xi ×7 at one institution, zero collaboration = different people" trap that name+affiliation alone
     *       falls into.</li>
     * </ul>
     * Dry-run unless {@code core.author-reconcile.affiliation-apply=true}: candidates are reported as
     * {@code AUTHOR_OVERSPLIT_MERGE_CANDIDATE} conflicts.
     */
    public ImportProcessingResult reconcileByNameAndAffiliation(String batchId, String correlationId) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        Instant now = Instant.now();

        Map<String, List<ScholardexAuthorFact>> byName = new TreeMap<>();
        for (ScholardexAuthorFact author : authorRepository.findAll()) {
            String key = nameKey(author.getDisplayName());
            if (!key.isEmpty()) {
                byName.computeIfAbsent(key, k -> new ArrayList<>()).add(author);
            }
        }

        int mergeGroups = 0;
        for (List<ScholardexAuthorFact> cluster : byName.values()) {
            List<ScholardexAuthorFact> members = dedupById(cluster);
            if (members.size() < 2 || members.size() > maxClusterSize) {
                continue;
            }
            for (List<ScholardexAuthorFact> group : overSplitGroups(members)) {
                result.markProcessed();
                mergeGroups++;
                if (!affiliationApply) {
                    quarantine(group, REASON_OVERSPLIT_MERGE_CANDIDATE, batchId, correlationId, now, result);
                } else if (isAutoMergeableOverSplit(group)) {
                    mergeCluster(group, now, result);
                } else {
                    // common initials-only name (e.g. "Wang, J.") shared by many in mega-author papers — name +
                    // affiliation + co-author can't safely disambiguate; leave for human review.
                    quarantine(group, REASON_OVERSPLIT_MERGE_REVIEW, batchId, correlationId, now, result);
                }
            }
        }
        log.info("Author reconcile (name+affiliation over-split pass, apply={}) complete: nameClusters={} "
                        + "mergeGroups={} merged~={} candidates={}",
                affiliationApply, byName.size(), mergeGroups, result.getImportedCount(), result.getSkippedCount());
        return result;
    }

    /**
     * Safety guard for auto-merge: only merge an over-split group when its identity is distinctive enough that
     * name+affiliation+co-author can't be coincidence. True when EITHER the members carry differing display strings
     * (a formatting / order / diacritic variant — a strong same-person signal, e.g. "Aguilar–Saavedra" vs
     * "Aguilar-Saavedra"), OR the shared name has ≥2 multi-letter tokens (a full given name or double surname, e.g.
     * "Cotăescu, Ion I."). Excludes identical single-surname + single-initial names ("Wang, J." ×6) that many
     * distinct people share in mega-author papers — those go to review, never auto-merge.
     */
    private boolean isAutoMergeableOverSplit(List<ScholardexAuthorFact> group) {
        long distinctNames = group.stream()
                .map(a -> a.getDisplayName() == null ? "" : a.getDisplayName().trim())
                .distinct().count();
        if (distinctNames > 1) {
            return true;
        }
        String name = group.getFirst().getDisplayName();
        if (name == null) {
            return false;
        }
        String folded = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        String[] tokens = folded.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", " ").trim().split("\\s+");
        int multiLetter = 0;
        for (String t : tokens) {
            if (t.length() >= 2) {
                multiLetter++;
            }
        }
        return multiLetter >= 2;
    }

    /** Subgroups of a same-name cluster whose members pairwise share ≥1 affiliation AND ≥1 co-author. */
    private List<List<ScholardexAuthorFact>> overSplitGroups(List<ScholardexAuthorFact> members) {
        int n = members.size();
        List<java.util.Set<String>> affSets = new ArrayList<>(n);
        List<java.util.Set<String>> coauthorSets = new ArrayList<>(n);
        java.util.Set<String> memberIds = new java.util.HashSet<>();
        members.forEach(m -> memberIds.add(m.getId()));
        for (ScholardexAuthorFact m : members) {
            affSets.add(new java.util.HashSet<>(safe(m.getAffiliationIds())));
            java.util.Set<String> coauthors = new java.util.HashSet<>();
            for (ScholardexPublicationFact pub : publicationRepository.findByAuthorIdsContains(m.getId())) {
                List<String> pubAuthors = safe(pub.getAuthorIds());
                if (coauthorMaxPaperAuthors > 0 && pubAuthors.size() > coauthorMaxPaperAuthors) {
                    continue; // mega-author paper: co-authorship here is not an identity signal
                }
                for (String authorId : pubAuthors) {
                    if (!memberIds.contains(authorId)) {
                        coauthors.add(authorId);
                    }
                }
            }
            coauthorSets.add(coauthors);
        }
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (intersects(affSets.get(i), affSets.get(j))
                        && intersectionSize(coauthorSets.get(i), coauthorSets.get(j)) >= 1) {
                    union(parent, i, j);
                }
            }
        }
        Map<Integer, List<ScholardexAuthorFact>> components = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            components.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(members.get(i));
        }
        List<List<ScholardexAuthorFact>> groups = new ArrayList<>();
        for (List<ScholardexAuthorFact> g : components.values()) {
            if (g.size() >= 2) {
                groups.add(g);
            }
        }
        return groups;
    }

    /**
     * Tier a same-name cluster by co-author overlap. For each pair that passes the same-publication hard block:
     * shared co-authors ≥ {@code coauthorStrongThreshold} is a STRONG link (union-find → merge group); 1..threshold-1
     * is a MEDIUM review pair; 0 is rejected (same name + institution but no collaboration ⇒ different people).
     * Affiliation is no longer a gate — co-author overlap is the decisive corroborator, and it also catches people
     * who changed institutions (no shared affiliation but high overlap).
     */
    private ClusterAnalysis analyzeCluster(List<ScholardexAuthorFact> members) {
        int n = members.size();
        List<java.util.Set<String>> pubSets = new ArrayList<>(n);
        List<java.util.Set<String>> coauthorSets = new ArrayList<>(n);
        java.util.Set<String> memberIds = new java.util.HashSet<>();
        members.forEach(m -> memberIds.add(m.getId()));
        for (ScholardexAuthorFact m : members) {
            java.util.Set<String> pubs = new java.util.HashSet<>();
            java.util.Set<String> coauthors = new java.util.HashSet<>();
            for (ScholardexPublicationFact pub : publicationRepository.findByAuthorIdsContains(m.getId())) {
                pubs.add(pub.getId());
                for (String authorId : safe(pub.getAuthorIds())) {
                    if (!memberIds.contains(authorId)) {
                        coauthors.add(authorId);
                    }
                }
            }
            pubSets.add(pubs);
            coauthorSets.add(coauthors);
        }

        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        List<int[]> mediumPairIndexes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (intersects(pubSets.get(i), pubSets.get(j))) {
                    continue; // hard block: co-appear on a paper => different people
                }
                int shared = intersectionSize(coauthorSets.get(i), coauthorSets.get(j));
                if (shared >= coauthorStrongThreshold) {
                    union(parent, i, j);
                } else if (shared >= 1) {
                    mediumPairIndexes.add(new int[]{i, j});
                }
            }
        }

        Map<Integer, List<ScholardexAuthorFact>> components = new java.util.LinkedHashMap<>();
        Map<Integer, List<Integer>> componentIndexes = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            components.computeIfAbsent(root, k -> new ArrayList<>()).add(members.get(i));
            componentIndexes.computeIfAbsent(root, k -> new ArrayList<>()).add(i);
        }
        List<List<ScholardexAuthorFact>> strongGroups = new ArrayList<>();
        for (Map.Entry<Integer, List<ScholardexAuthorFact>> e : components.entrySet()) {
            if (e.getValue().size() >= 2 && componentRespectsHardBlock(componentIndexes.get(e.getKey()), pubSets)) {
                strongGroups.add(e.getValue());
            }
        }
        List<List<ScholardexAuthorFact>> mediumGroups = new ArrayList<>();
        for (int[] pair : mediumPairIndexes) {
            if (find(parent, pair[0]) != find(parent, pair[1])) { // not already merging via a strong path
                mediumGroups.add(List.of(members.get(pair[0]), members.get(pair[1])));
            }
        }
        return new ClusterAnalysis(strongGroups, mediumGroups);
    }

    private record ClusterAnalysis(List<List<ScholardexAuthorFact>> strongGroups, List<List<ScholardexAuthorFact>> mediumPairs) {
    }

    private boolean componentRespectsHardBlock(List<Integer> idx, List<java.util.Set<String>> pubSets) {
        for (int i = 0; i < idx.size(); i++) {
            for (int j = i + 1; j < idx.size(); j++) {
                if (intersects(pubSets.get(idx.get(i)), pubSets.get(idx.get(j)))) {
                    return false; // two members on the same paper => different people => don't merge this group
                }
            }
        }
        return true;
    }

    private static boolean intersects(java.util.Set<String> a, java.util.Set<String> b) {
        java.util.Set<String> small = a.size() <= b.size() ? a : b;
        java.util.Set<String> large = small == a ? b : a;
        for (String x : small) {
            if (large.contains(x)) {
                return true;
            }
        }
        return false;
    }

    private static int intersectionSize(java.util.Set<String> a, java.util.Set<String> b) {
        java.util.Set<String> small = a.size() <= b.size() ? a : b;
        java.util.Set<String> large = small == a ? b : a;
        int count = 0;
        for (String x : small) {
            if (large.contains(x)) {
                count++;
            }
        }
        return count;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int i, int j) {
        parent[find(parent, i)] = find(parent, j);
    }

    /** Order-insensitive cluster key: normalized name tokens, sorted (so "Last, First" and "First Last" agree). */
    private static String nameKey(String displayName) {
        if (displayName == null) {
            return "";
        }
        String normalized = Normalizer.normalize(displayName, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        String[] tokens = normalized.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", " ").trim().split("\\s+");
        java.util.Arrays.sort(tokens);
        return String.join(" ", tokens).trim();
    }

    // ── merge ──────────────────────────────────────────────────────────────

    void mergeCluster(List<ScholardexAuthorFact> cluster, Instant now, ImportProcessingResult result) {
        ScholardexAuthorFact winner = pickWinner(cluster);
        List<ScholardexAuthorFact> losers = cluster.stream()
                .filter(a -> !a.getId().equals(winner.getId()))
                .sorted(Comparator.comparing(ScholardexAuthorFact::getId))
                .toList();

        LinkedHashSet<String> scopusIds = set(winner.getScopusAuthorIds());
        LinkedHashSet<String> wosIds = set(winner.getWosAuthorIds());
        LinkedHashSet<String> gsIds = set(winner.getGoogleScholarAuthorIds());
        LinkedHashSet<String> userIds = set(winner.getUserSourceAuthorIds());
        LinkedHashSet<String> orcidIds = set(winner.getOrcidIds());
        LinkedHashSet<String> openAlexIds = set(winner.getOpenAlexAuthorIds());
        LinkedHashSet<String> affiliationIds = set(winner.getAffiliationIds());
        LinkedHashSet<String> altNames = set(winner.getAlternativeNames());

        for (ScholardexAuthorFact loser : losers) {
            scopusIds.addAll(safe(loser.getScopusAuthorIds()));
            wosIds.addAll(safe(loser.getWosAuthorIds()));
            gsIds.addAll(safe(loser.getGoogleScholarAuthorIds()));
            userIds.addAll(safe(loser.getUserSourceAuthorIds()));
            orcidIds.addAll(safe(loser.getOrcidIds()));
            openAlexIds.addAll(safe(loser.getOpenAlexAuthorIds()));
            affiliationIds.addAll(safe(loser.getAffiliationIds()));
            altNames.addAll(safe(loser.getAlternativeNames()));
            if (loser.getDisplayName() != null && !loser.getDisplayName().isBlank()
                    && !loser.getDisplayName().equals(winner.getDisplayName())) {
                altNames.add(loser.getDisplayName());
            }
            repointReferences(loser.getId(), winner.getId(), now);
            authorRepository.deleteById(loser.getId());
            result.markImported(); // authors removed
        }

        winner.setScopusAuthorIds(new ArrayList<>(scopusIds));
        winner.setWosAuthorIds(new ArrayList<>(wosIds));
        winner.setGoogleScholarAuthorIds(new ArrayList<>(gsIds));
        winner.setUserSourceAuthorIds(new ArrayList<>(userIds));
        winner.setOrcidIds(new ArrayList<>(orcidIds));
        winner.setOpenAlexAuthorIds(new ArrayList<>(openAlexIds));
        winner.setAffiliationIds(new ArrayList<>(affiliationIds));
        altNames.remove(winner.getDisplayName());
        winner.setAlternativeNames(new ArrayList<>(altNames));
        winner.setUpdatedAt(now);
        authorRepository.save(winner);
        result.markUpdated();
    }

    /**
     * Deterministic winner: prefer the established author (most Scopus ids — real publications point there), then
     * most total id keys, then lexicographically smallest id. Order-independent so rebuilds converge identically.
     */
    private ScholardexAuthorFact pickWinner(List<ScholardexAuthorFact> cluster) {
        return cluster.stream()
                .min(Comparator
                        .comparingInt((ScholardexAuthorFact a) -> safe(a.getScopusAuthorIds()).size()).reversed()
                        .thenComparing(Comparator.comparingInt((ScholardexAuthorFact a) -> idKeyCount(a)).reversed())
                        .thenComparing(ScholardexAuthorFact::getId))
                .orElseThrow();
    }

    private int idKeyCount(ScholardexAuthorFact a) {
        return safe(a.getScopusAuthorIds()).size() + safe(a.getWosAuthorIds()).size()
                + safe(a.getOrcidIds()).size() + safe(a.getOpenAlexAuthorIds()).size()
                + safe(a.getUserSourceAuthorIds()).size();
    }

    private void repointReferences(String loserId, String winnerId, Instant now) {
        // Authorship edges: natural key (publicationId, authorId, source); a re-point can collide with an existing
        // (publicationId, winnerId, source) edge — then drop the loser edge, OR-ing its corresponding flag onto the winner.
        for (ScholardexAuthorshipFact edge : authorshipRepository.findByAuthorIdIn(List.of(loserId))) {
            var existing = authorshipRepository
                    .findByPublicationIdAndAuthorIdAndSource(edge.getPublicationId(), winnerId, edge.getSource());
            if (existing.isPresent()) {
                if (Boolean.TRUE.equals(edge.getCorresponding()) && !Boolean.TRUE.equals(existing.get().getCorresponding())) {
                    existing.get().setCorresponding(Boolean.TRUE);
                    existing.get().setUpdatedAt(now);
                    authorshipRepository.save(existing.get());
                }
                authorshipRepository.deleteById(edge.getId());
            } else {
                edge.setAuthorId(winnerId);
                edge.setUpdatedAt(now);
                authorshipRepository.save(edge);
            }
        }
        // Author→affiliation edges: natural key (authorId, affiliationId, source).
        for (ScholardexAuthorAffiliationFact edge : authorAffiliationRepository.findByAuthorIdIn(List.of(loserId))) {
            var existing = authorAffiliationRepository
                    .findByAuthorIdAndAffiliationIdAndSource(winnerId, edge.getAffiliationId(), edge.getSource());
            if (existing.isPresent()) {
                authorAffiliationRepository.deleteById(edge.getId());
            } else {
                edge.setAuthorId(winnerId);
                edge.setUpdatedAt(now);
                authorAffiliationRepository.save(edge);
            }
        }
        // Publication↔author-affiliation edges: natural key (publicationId, authorId, affiliationId, source).
        for (ScholardexPublicationAuthorAffiliationFact edge : pubAuthorAffiliationRepository.findByAuthorIdIn(List.of(loserId))) {
            var existing = pubAuthorAffiliationRepository.findByPublicationIdAndAuthorIdAndAffiliationIdAndSource(
                    edge.getPublicationId(), winnerId, edge.getAffiliationId(), edge.getSource());
            if (existing.isPresent()) {
                pubAuthorAffiliationRepository.deleteById(edge.getId());
            } else {
                edge.setAuthorId(winnerId);
                edge.setUpdatedAt(now);
                pubAuthorAffiliationRepository.save(edge);
            }
        }
        // Publication.authorIds[] — replace loser with winner (de-dup if winner already present).
        for (ScholardexPublicationFact pub : publicationRepository.findByAuthorIdsContains(loserId)) {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (String id : safe(pub.getAuthorIds())) {
                ids.add(loserId.equals(id) ? winnerId : id);
            }
            pub.setAuthorIds(new ArrayList<>(ids));
            publicationRepository.save(pub);
        }
        // AUTHOR source links — only the canonicalEntityId value changes (unique key untouched).
        for (ScholardexSourceLink link : sourceLinkRepository
                .findByEntityTypeAndCanonicalEntityId(ScholardexEntityType.AUTHOR, loserId)) {
            link.setCanonicalEntityId(winnerId);
            sourceLinkRepository.save(link);
        }
        // Researcher profiles pointing at the loser.
        for (User user : userRepository.findByResearcherProfilePrimaryScholardexAuthorId(loserId)) {
            if (user.getResearcherProfile() != null) {
                user.getResearcherProfile().setPrimaryScholardexAuthorId(winnerId);
                userRepository.save(user);
            }
        }
        // OpenAlex source-fact syncedResearchers — re-point the canonical author id (de-dup).
        for (OpenAlexPublicationFact fact : openAlexPublicationFactRepository.findBySyncedResearchersCanonicalAuthorId(loserId)) {
            if (fact.getSyncedResearchers() == null) {
                continue;
            }
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            List<OpenAlexPublicationFact.SyncedResearcher> kept = new ArrayList<>();
            for (OpenAlexPublicationFact.SyncedResearcher researcher : fact.getSyncedResearchers()) {
                if (loserId.equals(researcher.getCanonicalAuthorId())) {
                    researcher.setCanonicalAuthorId(winnerId);
                }
                if (seen.add(researcher.getCanonicalAuthorId())) {
                    kept.add(researcher);
                }
            }
            fact.setSyncedResearchers(kept);
            openAlexPublicationFactRepository.save(fact);
        }
    }

    // ── quarantine ─────────────────────────────────────────────────────────

    private void quarantine(List<ScholardexAuthorFact> cluster, String reason,
                            String batchId, String correlationId, Instant now, ImportProcessingResult result) {
        List<String> candidateIds = cluster.stream().map(ScholardexAuthorFact::getId).sorted().toList();
        String key = String.join("|", candidateIds); // stable across runs so the conflict de-dupes
        ScholardexIdentityConflict conflict = identityConflictRepository
                .findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                        ScholardexEntityType.AUTHOR, SOURCE_SCHOLARDEX, key, reason, STATUS_OPEN)
                .orElseGet(ScholardexIdentityConflict::new);
        conflict.setEntityType(ScholardexEntityType.AUTHOR);
        conflict.setIncomingSource(SOURCE_SCHOLARDEX);
        conflict.setIncomingSourceRecordId(key);
        conflict.setReasonCode(reason);
        conflict.setStatus(STATUS_OPEN);
        conflict.setCandidateCanonicalIds(new ArrayList<>(candidateIds));
        conflict.setSourceBatchId(batchId);
        conflict.setSourceCorrelationId(correlationId);
        if (conflict.getDetectedAt() == null) {
            conflict.setDetectedAt(now);
        }
        identityConflictRepository.save(conflict);
        result.markSkipped("author-reconcile-quarantine candidates=" + key);
    }

    // ── name compatibility ───────────────────────────────────────────────────

    /** A cluster is name-compatible iff every member is pairwise compatible (same surname, no conflicting given name). */
    boolean clusterNamesCompatible(List<ScholardexAuthorFact> cluster) {
        for (int i = 0; i < cluster.size(); i++) {
            for (int j = i + 1; j < cluster.size(); j++) {
                if (!namesCompatible(cluster.get(i).getDisplayName(), cluster.get(j).getDisplayName())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Two display names are compatible if surnames agree and the given names do not clearly conflict — i.e. an
     * initial vs a full name is fine ("Spataru, A." ~ "Adrian Spataru"), but two different full given names are not
     * ("Spataru, Adrian" vs "Spataru, Sergiu"). A missing name on either side is treated as compatible (no evidence).
     */
    boolean namesCompatible(String a, String b) {
        if (isBlank(a) || isBlank(b)) {
            return true;
        }
        String surnameA = normalize(surname(a));
        String surnameB = normalize(surname(b));
        if (!surnameA.isEmpty() && !surnameB.isEmpty() && !surnameA.equals(surnameB)) {
            return false;
        }
        String givenA = normalize(firstGiven(a));
        String givenB = normalize(firstGiven(b));
        if (givenA.isEmpty() || givenB.isEmpty()) {
            return true;
        }
        // Initial compatibility: one is the leading initial of the other.
        if (givenA.length() == 1 || givenB.length() == 1) {
            return givenA.charAt(0) == givenB.charAt(0);
        }
        return givenA.equals(givenB);
    }

    private static String surname(String name) {
        int comma = name.indexOf(',');
        if (comma >= 0) {
            return name.substring(0, comma);
        }
        String[] parts = name.trim().split("\\s+");
        return parts.length == 0 ? "" : parts[parts.length - 1];
    }

    private static String firstGiven(String name) {
        int comma = name.indexOf(',');
        if (comma >= 0) {
            String rest = name.substring(comma + 1).trim();
            String[] parts = rest.split("\\s+");
            return parts.length == 0 ? "" : parts[0];
        }
        String[] parts = name.trim().split("\\s+");
        return parts.length <= 1 ? "" : parts[0];
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String n = Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ── small helpers ────────────────────────────────────────────────────────

    private static List<ScholardexAuthorFact> dedupById(List<ScholardexAuthorFact> authors) {
        Map<String, ScholardexAuthorFact> byId = new java.util.LinkedHashMap<>();
        for (ScholardexAuthorFact a : authors) {
            byId.putIfAbsent(a.getId(), a);
        }
        return new ArrayList<>(byId.values());
    }

    private static LinkedHashSet<String> set(List<String> values) {
        return new LinkedHashSet<>(safe(values));
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
