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

        int mergeGroups = 0;
        for (List<ScholardexAuthorFact> cluster : byName.values()) {
            List<ScholardexAuthorFact> members = dedupById(cluster);
            if (members.size() < 2) {
                continue;
            }
            for (List<ScholardexAuthorFact> group : corroboratedSubgroups(members)) {
                if (group.size() < 2) {
                    continue;
                }
                result.markProcessed();
                mergeGroups++;
                if (fuzzyApply) {
                    mergeCluster(group, now, result);
                } else {
                    quarantine(group, REASON_FUZZY_MERGE_CANDIDATE, batchId, correlationId, now, result);
                }
            }
        }
        log.info("Author reconcile (fuzzy name pass, apply={}) complete: nameClusters={} mergeGroups={} merged~={} reported={}",
                fuzzyApply, byName.size(), mergeGroups, result.getImportedCount(), result.getSkippedCount());
        return result;
    }

    /**
     * Partition a same-name cluster into mergeable subgroups via union-find on the relation "share an affiliation AND
     * do not co-appear on a publication". A connected component is emitted only if the hard block holds across ALL
     * its pairs (transitivity could otherwise pull two co-appearing members into one group).
     */
    private List<List<ScholardexAuthorFact>> corroboratedSubgroups(List<ScholardexAuthorFact> members) {
        int n = members.size();
        Map<String, java.util.Set<String>> pubsByAuthor = new java.util.HashMap<>();
        for (ScholardexAuthorFact a : members) {
            java.util.Set<String> pubs = new java.util.HashSet<>();
            for (ScholardexPublicationFact pub : publicationRepository.findByAuthorIdsContains(a.getId())) {
                pubs.add(pub.getId());
            }
            pubsByAuthor.put(a.getId(), pubs);
        }
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (sharesAffiliation(members.get(i), members.get(j)) && !coAppear(members.get(i), members.get(j), pubsByAuthor)) {
                    union(parent, i, j);
                }
            }
        }
        Map<Integer, List<ScholardexAuthorFact>> components = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            components.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(members.get(i));
        }
        List<List<ScholardexAuthorFact>> safe = new ArrayList<>();
        for (List<ScholardexAuthorFact> component : components.values()) {
            if (component.size() >= 2 && componentRespectsHardBlock(component, pubsByAuthor)) {
                safe.add(component);
            }
        }
        return safe;
    }

    private boolean componentRespectsHardBlock(List<ScholardexAuthorFact> component, Map<String, java.util.Set<String>> pubsByAuthor) {
        for (int i = 0; i < component.size(); i++) {
            for (int j = i + 1; j < component.size(); j++) {
                if (coAppear(component.get(i), component.get(j), pubsByAuthor)) {
                    return false; // two members on the same paper => different people => don't merge this group
                }
            }
        }
        return true;
    }

    private boolean sharesAffiliation(ScholardexAuthorFact a, ScholardexAuthorFact b) {
        java.util.Set<String> aff = new java.util.HashSet<>(safe(a.getAffiliationIds()));
        aff.retainAll(safe(b.getAffiliationIds()));
        return !aff.isEmpty();
    }

    private boolean coAppear(ScholardexAuthorFact a, ScholardexAuthorFact b, Map<String, java.util.Set<String>> pubsByAuthor) {
        java.util.Set<String> pubs = new java.util.HashSet<>(pubsByAuthor.getOrDefault(a.getId(), java.util.Set.of()));
        pubs.retainAll(pubsByAuthor.getOrDefault(b.getId(), java.util.Set.of()));
        return !pubs.isEmpty();
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
