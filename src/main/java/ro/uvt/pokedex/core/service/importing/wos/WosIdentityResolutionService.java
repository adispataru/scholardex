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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class WosIdentityResolutionService {

    private static final String CONFLICT_TYPE_AMBIGUOUS_MATCH = "AMBIGUOUS_MATCH";
    private static final String CONFLICT_REASON_ISSN_TITLE_MISMATCH = "ISSN overlap with conflicting title fingerprint";

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
        String normalizedTitle = WosCanonicalContractSupport.normalizeTitleFingerprint(rawTitle);
        String identityKey = WosCanonicalContractSupport.buildIdentityKey(
                normalizedIssns,
                normalizedTitle,
                context.year(),
                context.editionRaw()
        );

        WosJournalIdentity matchedByKey = journalIdentityRepository.findByIdentityKey(identityKey).orElse(null);
        if (matchedByKey != null) {
            maybeUpdateAliases(matchedByKey, normalizedIssns, rawTitle, normalizedTitle);
            return new IdentityResolutionResult(matchedByKey.getId(), identityKey, WosIdentityResolutionStatus.MATCHED, null);
        }

        List<WosJournalIdentity> candidates = normalizedIssns.isEmpty()
                ? List.of()
                : journalIdentityRepository.findCandidatesByIssnTokens(normalizedIssns);
        if (candidates.size() > 1) {
            return createConflictIdentity(rawIssn, rawEIssn, rawTitle, normalizedIssns, normalizedTitle, identityKey, context, candidates);
        }
        if (candidates.size() == 1) {
            WosJournalIdentity candidate = candidates.get(0);
            if (isAmbiguousConflict(candidate, normalizedIssns, normalizedTitle)) {
                return createConflictIdentity(rawIssn, rawEIssn, rawTitle, normalizedIssns, normalizedTitle, identityKey, context, candidates);
            }
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
        for (String token : normalizedIssns) {
            if (!token.equals(identity.getPrimaryIssn())
                    && !token.equals(identity.getEIssn())
                    && !identity.getAliasIssns().contains(token)) {
                identity.getAliasIssns().add(token);
                changed = true;
            }
        }
        if (identity.getTitle() == null && rawTitle != null && !rawTitle.isBlank()) {
            identity.setTitle(rawTitle.trim());
            changed = true;
        }
        if (identity.getNormalizedTitle() == null && normalizedTitle != null) {
            identity.setNormalizedTitle(normalizedTitle);
            changed = true;
        }
        if (changed) {
            identity.setUpdatedAt(Instant.now());
            journalIdentityRepository.save(identity);
        }
    }

    private boolean isAmbiguousConflict(WosJournalIdentity candidate, Set<String> normalizedIssns, String normalizedTitle) {
        Set<String> candidateIssns = gatherIdentityIssnTokens(candidate);
        boolean hasOverlap = candidateIssns.stream().anyMatch(normalizedIssns::contains);
        boolean titleMismatch = normalizedTitle != null
                && candidate.getNormalizedTitle() != null
                && !normalizedTitle.equals(candidate.getNormalizedTitle());
        boolean nonExactIssnMatch = !candidateIssns.equals(normalizedIssns);
        return hasOverlap && titleMismatch && nonExactIssnMatch;
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
        conflict.setConflictReason(CONFLICT_REASON_ISSN_TITLE_MISMATCH);
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
                CONFLICT_REASON_ISSN_TITLE_MISMATCH
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
