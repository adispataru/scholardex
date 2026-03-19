package ro.uvt.pokedex.core.service.importing.wos;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.wos.WosIdentityConflict;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.repository.reporting.WosIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.service.importing.wos.model.IdentityResolutionResult;
import ro.uvt.pokedex.core.service.importing.wos.model.WosIdentityResolutionStatus;
import ro.uvt.pokedex.core.service.importing.wos.model.WosIdentitySourceContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class WosIdentityResolutionService {

    private static final String CONFLICT_TYPE_AMBIGUOUS_MATCH = "AMBIGUOUS_MATCH";
    private static final String CONFLICT_REASON_ISSN_COLLISION = "ISSN overlap across multiple journal identities";

    private final WosJournalIdentityRepository journalIdentityRepository;
    private final WosIdentityConflictRepository identityConflictRepository;

    public WosIdentityResolutionService(
            WosJournalIdentityRepository journalIdentityRepository,
            WosIdentityConflictRepository identityConflictRepository
    ) {
        this.journalIdentityRepository = journalIdentityRepository;
        this.identityConflictRepository = identityConflictRepository;
    }

    public IdentityResolutionResult resolveIdentity(
            String rawIssn,
            String rawEIssn,
            String rawTitle,
            WosIdentitySourceContext sourceContext
    ) {
        WosIdentitySourceContext context = sourceContext == null ? WosIdentitySourceContext.empty() : sourceContext;
        Set<String> normalizedIssns = normalizedIssnSet(rawIssn, rawEIssn);
        if (normalizedIssns.isEmpty()) {
            return null;
        }

        String normalizedTitle = WosCanonicalContractSupport.normalizeTitleFingerprint(rawTitle);
        String identityKey = WosCanonicalContractSupport.buildIdentityKey(
                normalizedIssns,
                normalizedTitle,
                context.year(),
                context.editionRaw()
        );

        WosJournalIdentity matchedByKey = identityKey == null
                ? null
                : journalIdentityRepository.findByIdentityKey(identityKey).orElse(null);
        if (matchedByKey != null) {
            maybeUpdateAliases(matchedByKey, normalizedIssns, rawTitle, normalizedTitle);
            return new IdentityResolutionResult(matchedByKey.getId(), identityKey, WosIdentityResolutionStatus.MATCHED, null);
        }

        List<WosJournalIdentity> candidates = journalIdentityRepository.findCandidatesByIssnTokens(normalizedIssns);
        return resolveFromCandidates(rawIssn, rawEIssn, rawTitle, context, normalizedIssns, normalizedTitle, identityKey, candidates);
    }

    public java.util.Map<String, String> findJournalIdsByIdentityKeys(Set<String> identityKeys) {
        if (identityKeys == null || identityKeys.isEmpty()) {
            return java.util.Map.of();
        }
        return journalIdentityRepository.findAllByIdentityKeyIn(identityKeys).stream()
                .collect(Collectors.toMap(
                        WosJournalIdentity::getIdentityKey,
                        WosJournalIdentity::getId,
                        (left, right) -> left
                ));
    }

    public Map<String, List<WosJournalIdentity>> findCandidatesByIssnTokenSets(Map<String, Set<String>> tokenSetsByKey) {
        if (tokenSetsByKey == null || tokenSetsByKey.isEmpty()) {
            return Map.of();
        }
        Set<String> unionTokens = new LinkedHashSet<>();
        for (Set<String> tokenSet : tokenSetsByKey.values()) {
            if (tokenSet == null) {
                continue;
            }
            for (String token : tokenSet) {
                String normalized = WosCanonicalContractSupport.normalizeIssnToken(token);
                if (normalized != null) {
                    unionTokens.add(normalized);
                }
            }
        }
        if (unionTokens.isEmpty()) {
            return Map.of();
        }
        List<WosJournalIdentity> allCandidates = journalIdentityRepository.findCandidatesByIssnTokens(unionTokens);
        Map<String, List<WosJournalIdentity>> byKey = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : tokenSetsByKey.entrySet()) {
            String key = entry.getKey();
            Set<String> normalizedTokens = new LinkedHashSet<>();
            Set<String> rawTokens = entry.getValue();
            if (rawTokens != null) {
                for (String token : rawTokens) {
                    String normalized = WosCanonicalContractSupport.normalizeIssnToken(token);
                    if (normalized != null) {
                        normalizedTokens.add(normalized);
                    }
                }
            }
            if (normalizedTokens.isEmpty()) {
                byKey.put(key, List.of());
                continue;
            }
            List<WosJournalIdentity> matching = allCandidates.stream()
                    .filter(candidate -> hasIssnTokenOverlap(candidate, normalizedTokens))
                    .toList();
            byKey.put(key, matching);
        }
        return byKey;
    }

    public IdentityResolutionResult resolveIdentityWithPrefetchedCandidates(
            String rawIssn,
            String rawEIssn,
            String rawTitle,
            WosIdentitySourceContext sourceContext,
            Collection<WosJournalIdentity> prefetchedCandidates
    ) {
        WosIdentitySourceContext context = sourceContext == null ? WosIdentitySourceContext.empty() : sourceContext;
        Set<String> normalizedIssns = normalizedIssnSet(rawIssn, rawEIssn);
        if (normalizedIssns.isEmpty()) {
            return null;
        }
        String normalizedTitle = WosCanonicalContractSupport.normalizeTitleFingerprint(rawTitle);
        String identityKey = WosCanonicalContractSupport.buildIdentityKey(
                normalizedIssns,
                normalizedTitle,
                context.year(),
                context.editionRaw()
        );
        List<WosJournalIdentity> candidates = prefetchedCandidates == null ? List.of() : prefetchedCandidates.stream()
                .filter(candidate -> hasIssnTokenOverlap(candidate, normalizedIssns))
                .toList();
        return resolveFromCandidates(rawIssn, rawEIssn, rawTitle, context, normalizedIssns, normalizedTitle, identityKey, candidates);
    }

    private IdentityResolutionResult resolveFromCandidates(
            String rawIssn, String rawEIssn, String rawTitle,
            WosIdentitySourceContext context,
            Set<String> normalizedIssns, String normalizedTitle, String identityKey,
            List<WosJournalIdentity> candidates
    ) {
        if (candidates.size() > 1) {
            WosJournalIdentity resolved = resolveBestCandidate(candidates, normalizedIssns);
            if (resolved != null) {
                resolved.setIdentityKey(identityKey);
                maybeUpdateAliases(resolved, normalizedIssns, rawTitle, normalizedTitle);
                return new IdentityResolutionResult(resolved.getId(), identityKey, WosIdentityResolutionStatus.MATCHED, null);
            }
            return createConflictIdentity(rawIssn, rawEIssn, rawTitle, normalizedIssns, normalizedTitle, identityKey, context, candidates);
        }
        if (candidates.size() == 1) {
            WosJournalIdentity candidate = candidates.getFirst();
            candidate.setIdentityKey(identityKey);
            maybeUpdateAliases(candidate, normalizedIssns, rawTitle, normalizedTitle);
            return new IdentityResolutionResult(candidate.getId(), identityKey, WosIdentityResolutionStatus.MATCHED, null);
        }
        WosJournalIdentity created = createIdentity(rawIssn, rawEIssn, rawTitle, normalizedIssns, normalizedTitle, identityKey, null, null);
        return new IdentityResolutionResult(created.getId(), identityKey, WosIdentityResolutionStatus.CREATED, null);
    }

    private Set<String> normalizedIssnSet(String rawIssn, String rawEIssn) {
        Set<String> result = new LinkedHashSet<>();
        String normalizedIssn = WosCanonicalContractSupport.normalizeIssnToken(rawIssn);
        String normalizedEIssn = WosCanonicalContractSupport.normalizeIssnToken(rawEIssn);
        if (normalizedIssn != null) {
            result.add(normalizedIssn);
        }
        if (normalizedEIssn != null) {
            result.add(normalizedEIssn);
        }
        return result;
    }

    private void maybeUpdateAliases(WosJournalIdentity identity, Set<String> normalizedIssns, String rawTitle, String normalizedTitle) {
        boolean changed = false;
        if (identity.getPrimaryIssn() == null && !normalizedIssns.isEmpty()) {
            identity.setPrimaryIssn(normalizedIssns.iterator().next());
            changed = true;
        }
        if (identity.getEIssn() == null && normalizedIssns.size() > 1) {
            String eIssnCandidate = normalizedIssns.stream()
                    .filter(token -> !Objects.equals(token, identity.getPrimaryIssn()))
                    .findFirst()
                    .orElse(null);
            if (eIssnCandidate != null) {
                identity.setEIssn(eIssnCandidate);
                changed = true;
            }
        }

        if (identity.getAliasIssns() == null) {
            identity.setAliasIssns(new ArrayList<>());
            changed = true;
        }
        if (identity.getAlternativeNames() == null) {
            identity.setAlternativeNames(new ArrayList<>());
            changed = true;
        }

        for (String token : normalizedIssns) {
            if (!token.equals(identity.getPrimaryIssn())
                    && !token.equals(identity.getEIssn())
                    && !identity.getAliasIssns().contains(token)) {
                identity.getAliasIssns().add(token);
                changed = true;
            }
        }

        String incomingTitle = rawTitle == null ? null : rawTitle.trim();
        if (incomingTitle != null && incomingTitle.isBlank()) {
            incomingTitle = null;
        }
        String normalizedCanonicalTitle = WosCanonicalContractSupport.normalizeTitleFingerprint(identity.getTitle());

        if ((identity.getTitle() == null || identity.getTitle().isBlank()) && incomingTitle != null) {
            identity.setTitle(incomingTitle);
            identity.setNormalizedTitle(normalizedTitle);
            changed = true;
        } else if (incomingTitle != null) {
            String normalizedIncomingTitle = WosCanonicalContractSupport.normalizeTitleFingerprint(incomingTitle);
            if (normalizedIncomingTitle != null
                    && normalizedCanonicalTitle != null
                    && !normalizedIncomingTitle.equals(normalizedCanonicalTitle)
                    && !containsAlternativeName(identity.getAlternativeNames(), normalizedIncomingTitle)
                    && !normalizedIncomingTitle.equals(normalizedCanonicalTitle)) {
                identity.getAlternativeNames().add(incomingTitle);
                changed = true;
            }
        }

        if (changed) {
            identity.setUpdatedAt(Instant.now());
            journalIdentityRepository.save(identity);
        }
    }

    private boolean containsAlternativeName(List<String> alternativeNames, String normalizedIncomingTitle) {
        if (alternativeNames == null || alternativeNames.isEmpty()) {
            return false;
        }
        for (String alternativeName : alternativeNames) {
            String normalizedAlternative = WosCanonicalContractSupport.normalizeTitleFingerprint(alternativeName);
            if (normalizedIncomingTitle.equals(normalizedAlternative)) {
                return true;
            }
        }
        return false;
    }

    private WosJournalIdentity resolveBestCandidate(
            List<WosJournalIdentity> candidates,
            Set<String> normalizedIssns
    ) {
        int maxOverlap = candidates.stream()
                .mapToInt(candidate -> overlapScore(gatherIdentityIssnTokens(candidate), normalizedIssns))
                .max()
                .orElse(0);
        List<WosJournalIdentity> bestOverlap = candidates.stream()
                .filter(candidate -> overlapScore(gatherIdentityIssnTokens(candidate), normalizedIssns) == maxOverlap)
                .toList();
        if (bestOverlap.size() == 1) {
            return bestOverlap.getFirst();
        }

        Instant latestUpdatedAt = bestOverlap.stream()
                .map(this::effectiveUpdatedAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
        List<WosJournalIdentity> latestByUpdatedAt = bestOverlap.stream()
                .filter(candidate -> Objects.equals(effectiveUpdatedAt(candidate), latestUpdatedAt))
                .toList();
        if (latestByUpdatedAt.size() == 1) {
            return latestByUpdatedAt.getFirst();
        }

        Instant latestCreatedAt = latestByUpdatedAt.stream()
                .map(this::effectiveCreatedAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
        List<WosJournalIdentity> latestByCreatedAt = latestByUpdatedAt.stream()
                .filter(candidate -> Objects.equals(effectiveCreatedAt(candidate), latestCreatedAt))
                .toList();
        if (latestByCreatedAt.size() == 1) {
            return latestByCreatedAt.getFirst();
        }

        return null;
    }

    private int overlapScore(Set<String> leftIssns, Set<String> rightIssns) {
        if (leftIssns.isEmpty() || rightIssns.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (String token : leftIssns) {
            if (rightIssns.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private Instant effectiveUpdatedAt(WosJournalIdentity identity) {
        if (identity.getUpdatedAt() != null) {
            return identity.getUpdatedAt();
        }
        return effectiveCreatedAt(identity);
    }

    private Instant effectiveCreatedAt(WosJournalIdentity identity) {
        return identity.getCreatedAt() == null ? Instant.EPOCH : identity.getCreatedAt();
    }

    private Set<String> gatherIdentityIssnTokens(WosJournalIdentity identity) {
        Set<String> tokens = new LinkedHashSet<>();
        String primary = WosCanonicalContractSupport.normalizeIssnToken(identity.getPrimaryIssn());
        String eIssn = WosCanonicalContractSupport.normalizeIssnToken(identity.getEIssn());
        if (primary != null) {
            tokens.add(primary);
        }
        if (eIssn != null) {
            tokens.add(eIssn);
        }
        if (identity.getAliasIssns() != null) {
            identity.getAliasIssns().stream()
                    .map(WosCanonicalContractSupport::normalizeIssnToken)
                    .filter(Objects::nonNull)
                    .forEach(tokens::add);
        }
        return tokens;
    }

    private boolean hasIssnTokenOverlap(WosJournalIdentity identity, Set<String> normalizedIssns) {
        if (identity == null || normalizedIssns == null || normalizedIssns.isEmpty()) {
            return false;
        }
        Set<String> candidateTokens = gatherIdentityIssnTokens(identity);
        for (String token : normalizedIssns) {
            if (candidateTokens.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private IdentityResolutionResult createConflictIdentity(
            String rawIssn,
            String rawEIssn,
            String rawTitle,
            Set<String> normalizedIssns,
            String normalizedTitle,
            String identityKey,
            WosIdentitySourceContext context,
            List<WosJournalIdentity> candidates
    ) {
        WosIdentityConflict conflict = new WosIdentityConflict();
        conflict.setConflictType(CONFLICT_TYPE_AMBIGUOUS_MATCH);
        conflict.setConflictReason(CONFLICT_REASON_ISSN_COLLISION);
        conflict.setConflictDetectedAt(Instant.now());
        conflict.setSourceEventId(context.sourceEventId());
        conflict.setSourceFile(context.sourceFile());
        conflict.setSourceVersion(context.sourceVersion());
        conflict.setSourceRowItem(context.sourceRowItem());
        conflict.setInputIdentityKey(identityKey);
        conflict.setInputIssnTokens(new ArrayList<>(normalizedIssns));
        conflict.setInputTitle(rawTitle);
        conflict.setInputNormalizedTitle(normalizedTitle);
        conflict.setCandidateJournalIds(candidates.stream().map(WosJournalIdentity::getId).collect(Collectors.toList()));
        conflict.setCandidateIdentityKeys(candidates.stream().map(WosJournalIdentity::getIdentityKey).collect(Collectors.toList()));
        WosIdentityConflict savedConflict = identityConflictRepository.save(conflict);

        WosJournalIdentity created = createIdentity(
                rawIssn,
                rawEIssn,
                rawTitle,
                normalizedIssns,
                normalizedTitle,
                identityKey,
                CONFLICT_TYPE_AMBIGUOUS_MATCH,
                CONFLICT_REASON_ISSN_COLLISION
        );
        return new IdentityResolutionResult(created.getId(), identityKey, WosIdentityResolutionStatus.CONFLICT, savedConflict.getId());
    }

    private WosJournalIdentity createIdentity(
            String rawIssn,
            String rawEIssn,
            String rawTitle,
            Set<String> normalizedIssns,
            String normalizedTitle,
            String identityKey,
            String conflictType,
            String conflictReason
    ) {
        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId(buildJournalId(identityKey));
        identity.setIdentityKey(identityKey);
        identity.setPrimaryIssn(WosCanonicalContractSupport.normalizeIssnToken(rawIssn));
        identity.setEIssn(WosCanonicalContractSupport.normalizeIssnToken(rawEIssn));
        identity.setTitle(rawTitle == null ? null : rawTitle.trim());
        identity.setNormalizedTitle(normalizedTitle);
        identity.setAliasIssns(new ArrayList<>());
        identity.setAlternativeNames(new ArrayList<>());
        for (String token : normalizedIssns) {
            if (!token.equals(identity.getPrimaryIssn()) && !token.equals(identity.getEIssn())) {
                identity.getAliasIssns().add(token);
            }
        }
        if (conflictType != null) {
            identity.setConflictType(conflictType);
            identity.setConflictReason(conflictReason);
            identity.setConflictDetectedAt(Instant.now());
        }
        Instant now = Instant.now();
        identity.setCreatedAt(now);
        identity.setUpdatedAt(now);
        return journalIdentityRepository.save(identity);
    }

    private String buildJournalId(String identityKey) {
        return "jid_" + identityKey;
    }
}
