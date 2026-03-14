package ro.uvt.pokedex.core.service.importing.scopus;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexEdgeWriterService;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.application.UserDefinedWizardOnboardingContract;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UserDefinedCanonicalizationService {

    private static final String SOURCE_USER_DEFINED = UserDefinedWizardOnboardingContract.SOURCE;
    private static final String SOURCE_SCOPUS = "SCOPUS";
    private static final String LINK_REASON_USER_DEFINED_PUBLICATION = "user-defined-fact-bridge";
    private static final String LINK_REASON_USER_DEFINED_FORUM = "user-defined-forum-fact-bridge";
    private static final String LINK_REASON_AUTHORSHIP_BRIDGE = "publication-authorship-bridge";
    private static final String LINK_REASON_AUTHOR_FALLBACK = "canonical-author-fallback";
    private static final String LINK_REASON_PUBLICATION_AUTHOR_AFFILIATION_BRIDGE = "publication-author-affiliation-bridge";
    private static final String REASON_FORUM_AMBIGUOUS = "USER_DEFINED_FORUM_AMBIGUOUS";
    private static final String REASON_PUBLICATION_DOI_AMBIGUOUS = "USER_DEFINED_PUBLICATION_DOI_AMBIGUOUS";
    private static final String REASON_PUBLICATION_AUTHOR_AFFILIATION_UNRESOLVED = "PUBLICATION_AUTHOR_AFFILIATION_UNRESOLVED";
    private static final Pattern ISSN_NON_ALNUM = Pattern.compile("[^0-9Xx]");
    private static final Pattern DOI_URL_PREFIX = Pattern.compile("^https?://(dx\\.)?doi\\.org/", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOI_PREFIX = Pattern.compile("^doi:", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_ALNUM_OR_SPACE = Pattern.compile("[^\\p{Alnum}\\s]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final UserDefinedPublicationFactRepository userDefinedPublicationFactRepository;
    private final UserDefinedForumFactRepository userDefinedForumFactRepository;
    private final ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    private final ScholardexForumFactRepository scholardexForumFactRepository;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexEdgeWriterService edgeWriterService;
    private final ScholardexPublicationCanonicalizationService publicationCanonicalizationService;

    public ImportProcessingResult rebuildCanonicalFacts() {
        ImportProcessingResult result = new ImportProcessingResult(20);
        Map<String, String> forumCanonicalBySourceRecordId = canonicalizeForums(result);
        canonicalizePublications(result, forumCanonicalBySourceRecordId);
        return result;
    }

    private Map<String, String> canonicalizeForums(ImportProcessingResult result) {
        Map<String, String> canonicalBySourceRecordId = new LinkedHashMap<>();
        List<UserDefinedForumFact> sourceForums = new ArrayList<>(userDefinedForumFactRepository.findAll());
        sourceForums.sort(Comparator.comparing(UserDefinedForumFact::getSourceRecordId, Comparator.nullsLast(String::compareTo)));
        List<ScholardexForumFact> canonicalForums = new ArrayList<>(scholardexForumFactRepository.findAll());

        for (UserDefinedForumFact sourceForum : sourceForums) {
            result.markProcessed();
            if (isBlank(sourceForum.getSourceRecordId())) {
                result.markSkipped("user-defined forum missing sourceRecordId");
                continue;
            }

            List<ScholardexForumFact> candidates = findForumCandidates(canonicalForums, sourceForum);
            if (candidates.size() > 1) {
                sourceLinkService.markConflict(
                        ScholardexEntityType.FORUM,
                        SOURCE_USER_DEFINED,
                        sourceForum.getSourceRecordId(),
                        REASON_FORUM_AMBIGUOUS,
                        sourceForum.getSourceEventId(),
                        sourceForum.getSourceBatchId(),
                        sourceForum.getSourceCorrelationId(),
                        false
                );
                result.markSkipped("user-defined forum ambiguous sourceRecordId=" + sourceForum.getSourceRecordId());
                continue;
            }

            ScholardexForumFact target = candidates.isEmpty() ? new ScholardexForumFact() : candidates.getFirst();
            boolean created = target.getId() == null;
            Instant now = Instant.now();
            if (target.getCreatedAt() == null) {
                target.setCreatedAt(now);
            }
            String canonicalId = target.getId();
            if (isBlank(canonicalId)) {
                canonicalId = buildCanonicalForumId(
                        sourceForum.getIssn(),
                        sourceForum.getEIssn(),
                        sourceForum.getPublicationName(),
                        sourceForum.getAggregationType()
                );
            }
            target.setId(canonicalId);
            target.setName(sourceForum.getPublicationName());
            target.setNameNormalized(normalizeName(sourceForum.getPublicationName()));
            target.setIssn(normalizeIssn(sourceForum.getIssn()));
            target.setEIssn(normalizeIssn(sourceForum.getEIssn()));
            target.setAggregationType(sourceForum.getAggregationType());
            target.setAggregationTypeNormalized(normalizeToken(sourceForum.getAggregationType()));
            target.setSourceEventId(sourceForum.getSourceEventId());
            target.setSource(SOURCE_USER_DEFINED);
            target.setSourceRecordId(sourceForum.getSourceRecordId());
            target.setSourceBatchId(sourceForum.getSourceBatchId());
            target.setSourceCorrelationId(sourceForum.getSourceCorrelationId());
            target.setReviewState(sourceForum.getReviewState());
            target.setReviewReason(sourceForum.getReviewReason());
            target.setReviewStateUpdatedAt(sourceForum.getReviewStateUpdatedAt());
            target.setReviewStateUpdatedBy(sourceForum.getReviewStateUpdatedBy());
            target.setModerationFlow(sourceForum.getModerationFlow());
            target.setWizardSubmitterEmail(sourceForum.getWizardSubmitterEmail());
            target.setWizardSubmitterResearcherId(sourceForum.getWizardSubmitterResearcherId());
            target.setWizardSubmittedAt(sourceForum.getWizardSubmittedAt());
            target.setUpdatedAt(now);
            target.setAliasIssns(buildAliasIssns(target.getIssn(), target.getEIssn(), target.getAliasIssns()));
            List<String> userSourceForumIds = new ArrayList<>(safeList(target.getUserSourceForumIds()));
            if (!userSourceForumIds.contains(sourceForum.getSourceRecordId())) {
                userSourceForumIds.add(sourceForum.getSourceRecordId());
            }
            target.setUserSourceForumIds(userSourceForumIds);
            scholardexForumFactRepository.save(target);
            if (created) {
                canonicalForums.add(target);
                result.markImported();
            } else {
                result.markUpdated();
            }
            canonicalBySourceRecordId.put(sourceForum.getSourceRecordId(), target.getId());
            sourceLinkService.link(
                    ScholardexEntityType.FORUM,
                    SOURCE_USER_DEFINED,
                    sourceForum.getSourceRecordId(),
                    target.getId(),
                    LINK_REASON_USER_DEFINED_FORUM,
                    sourceForum.getSourceEventId(),
                    sourceForum.getSourceBatchId(),
                    sourceForum.getSourceCorrelationId(),
                    false
            );
        }

        return canonicalBySourceRecordId;
    }

    private void canonicalizePublications(ImportProcessingResult result, Map<String, String> forumCanonicalBySourceRecordId) {
        List<UserDefinedPublicationFact> sourcePublications = new ArrayList<>(userDefinedPublicationFactRepository.findAll());
        sourcePublications.sort(Comparator.comparing(UserDefinedPublicationFact::getSourceRecordId, Comparator.nullsLast(String::compareTo)));

        for (UserDefinedPublicationFact sourcePublication : sourcePublications) {
            result.markProcessed();
            if (isBlank(sourcePublication.getSourceRecordId())) {
                result.markSkipped("user-defined publication missing sourceRecordId");
                continue;
            }
            String doiNormalized = normalizeDoi(sourcePublication.getDoi());
            ScholardexPublicationFact target = loadExistingCanonicalPublication(sourcePublication, doiNormalized, result);
            if (target == null) {
                continue;
            }

            String forumId = resolveCanonicalForumId(sourcePublication.getForumSourceRecordId(), forumCanonicalBySourceRecordId);
            Instant now = Instant.now();
            if (target.getCreatedAt() == null) {
                target.setCreatedAt(now);
            }
            String canonicalId = publicationCanonicalizationService.buildCanonicalPublicationId(
                    sourcePublication.getEid(),
                    target.getWosId(),
                    target.getGoogleScholarId(),
                    sourcePublication.getSourceRecordId(),
                    doiNormalized,
                    ScholardexPublicationCanonicalizationService.normalizeTitle(sourcePublication.getTitle()),
                    sourcePublication.getCoverDate(),
                    sourcePublication.getCreator(),
                    forumId
            );
            boolean created = target.getId() == null;
            target.setId(canonicalId);
            target.setDoi(sourcePublication.getDoi());
            target.setDoiNormalized(doiNormalized);
            target.setTitle(sourcePublication.getTitle());
            target.setTitleNormalized(ScholardexPublicationCanonicalizationService.normalizeTitle(sourcePublication.getTitle()));
            target.setEid(sourcePublication.getEid());
            target.setUserSourceId(sourcePublication.getSourceRecordId());
            target.setSubtype(sourcePublication.getSubtype());
            target.setSubtypeDescription(sourcePublication.getSubtypeDescription());
            target.setScopusSubtype(sourcePublication.getSubtype());
            target.setScopusSubtypeDescription(sourcePublication.getSubtypeDescription());
            target.setCreator(sourcePublication.getCreator());
            target.setAuthorCount(sourcePublication.getAuthorCount());
            target.setCorrespondingAuthors(new ArrayList<>(safeList(sourcePublication.getCorrespondingAuthors())));
            target.setAffiliationIds(new ArrayList<>(safeList(sourcePublication.getAffiliationIds())));
            target.setForumId(forumId);
            target.setVolume(sourcePublication.getVolume());
            target.setIssueIdentifier(sourcePublication.getIssueIdentifier());
            target.setCoverDate(sourcePublication.getCoverDate());
            target.setCoverDisplayDate(sourcePublication.getCoverDisplayDate());
            target.setDescription(sourcePublication.getDescription());
            target.setCitedByCount(sourcePublication.getCitedByCount());
            target.setOpenAccess(sourcePublication.getOpenAccess());
            target.setFreetoread(sourcePublication.getFreetoread());
            target.setFreetoreadLabel(sourcePublication.getFreetoreadLabel());
            target.setFundingId(sourcePublication.getFundingId());
            target.setArticleNumber(sourcePublication.getArticleNumber());
            target.setPageRange(sourcePublication.getPageRange());
            target.setApproved(sourcePublication.getApproved());
            target.setReviewState(sourcePublication.getReviewState());
            target.setReviewReason(sourcePublication.getReviewReason());
            target.setReviewStateUpdatedAt(sourcePublication.getReviewStateUpdatedAt());
            target.setReviewStateUpdatedBy(sourcePublication.getReviewStateUpdatedBy());
            target.setModerationFlow(sourcePublication.getModerationFlow());
            target.setWizardSubmitterEmail(sourcePublication.getWizardSubmitterEmail());
            target.setWizardSubmitterResearcherId(sourcePublication.getWizardSubmitterResearcherId());
            target.setWizardSubmittedAt(sourcePublication.getWizardSubmittedAt());
            target.setSourceEventId(sourcePublication.getSourceEventId());
            target.setSource(SOURCE_USER_DEFINED);
            target.setSourceRecordId(sourcePublication.getSourceRecordId());
            target.setSourceBatchId(sourcePublication.getSourceBatchId());
            target.setSourceCorrelationId(sourcePublication.getSourceCorrelationId());
            target.setUpdatedAt(now);

            AuthorBridgeResult authorBridgeResult = bridgeAuthors(sourcePublication.getAuthorIds());
            target.setAuthorIds(authorBridgeResult.canonicalAuthorIds());
            target.setPendingAuthorSourceIds(authorBridgeResult.pendingSourceIds());

            scholardexPublicationFactRepository.save(target);
            if (created) {
                result.markImported();
            } else {
                result.markUpdated();
            }

            sourceLinkService.link(
                    ScholardexEntityType.PUBLICATION,
                    SOURCE_USER_DEFINED,
                    sourcePublication.getSourceRecordId(),
                    target.getId(),
                    LINK_REASON_USER_DEFINED_PUBLICATION,
                    sourcePublication.getSourceEventId(),
                    sourcePublication.getSourceBatchId(),
                    sourcePublication.getSourceCorrelationId(),
                    false
            );
            upsertPublicationEdges(target, sourcePublication, authorBridgeResult);
        }
    }

    private ScholardexPublicationFact loadExistingCanonicalPublication(
            UserDefinedPublicationFact sourcePublication,
            String doiNormalized,
            ImportProcessingResult result
    ) {
        Optional<ScholardexPublicationFact> byUserSourceId =
                scholardexPublicationFactRepository.findByUserSourceId(sourcePublication.getSourceRecordId());
        if (byUserSourceId.isPresent()) {
            return byUserSourceId.get();
        }
        if (!isBlank(sourcePublication.getEid())) {
            Optional<ScholardexPublicationFact> byEid = scholardexPublicationFactRepository.findByEid(sourcePublication.getEid());
            if (byEid.isPresent()) {
                return byEid.get();
            }
        }
        if (!isBlank(doiNormalized)) {
            List<ScholardexPublicationFact> byDoi =
                    new ArrayList<>(scholardexPublicationFactRepository.findAllByDoiNormalized(doiNormalized));
            if (byDoi.size() > 1) {
                sourceLinkService.markConflict(
                        ScholardexEntityType.PUBLICATION,
                        SOURCE_USER_DEFINED,
                        sourcePublication.getSourceRecordId(),
                        REASON_PUBLICATION_DOI_AMBIGUOUS,
                        sourcePublication.getSourceEventId(),
                        sourcePublication.getSourceBatchId(),
                        sourcePublication.getSourceCorrelationId(),
                        false
                );
                result.markSkipped("user-defined publication ambiguous doi sourceRecordId=" + sourcePublication.getSourceRecordId());
                return null;
            }
            if (byDoi.size() == 1) {
                return byDoi.getFirst();
            }
        }
        return new ScholardexPublicationFact();
    }

    private String resolveCanonicalForumId(String forumSourceRecordId, Map<String, String> forumCanonicalBySourceRecordId) {
        String normalized = normalizeBlank(forumSourceRecordId);
        if (normalized == null) {
            return null;
        }
        if (!normalized.startsWith(UserDefinedWizardOnboardingContract.FORUM_SOURCE_RECORD_PREFIX)) {
            return normalized;
        }
        String mapped = forumCanonicalBySourceRecordId.get(normalized);
        if (!isBlank(mapped)) {
            return mapped;
        }
        Optional<ScholardexSourceLink> existing = sourceLinkService.findByKey(ScholardexEntityType.FORUM, SOURCE_USER_DEFINED, normalized);
        return existing.map(ScholardexSourceLink::getCanonicalEntityId).orElse(normalized);
    }

    private void upsertPublicationEdges(
            ScholardexPublicationFact canonicalPublication,
            UserDefinedPublicationFact sourcePublication,
            AuthorBridgeResult authorBridgeResult
    ) {
        List<AuthorBridgeEntry> entries = authorBridgeResult.entries();
        List<String> authorAffiliationSourceIds = safeList(sourcePublication.getAuthorAffiliationSourceIds());
        LinkedHashSet<String> dedup = new LinkedHashSet<>();

        for (int index = 0; index < entries.size(); index++) {
            AuthorBridgeEntry entry = entries.get(index);
            String authorshipSourceRecordId = buildAuthorshipSourceRecordId(sourcePublication.getSourceRecordId(), entry.sourceAuthorId());
            edgeWriterService.upsertAuthorshipEdge(new ScholardexEdgeWriterService.EdgeWriteCommand(
                    canonicalPublication.getId(),
                    entry.canonicalAuthorId(),
                    SOURCE_USER_DEFINED,
                    authorshipSourceRecordId,
                    sourcePublication.getSourceEventId(),
                    sourcePublication.getSourceBatchId(),
                    sourcePublication.getSourceCorrelationId(),
                    entry.pendingResolution()
                            ? ScholardexSourceLinkService.STATE_UNMATCHED
                            : ScholardexSourceLinkService.STATE_LINKED,
                    entry.pendingResolution()
                            ? LINK_REASON_AUTHOR_FALLBACK
                            : LINK_REASON_AUTHORSHIP_BRIDGE,
                    false
            ));

            if (index >= authorAffiliationSourceIds.size()) {
                continue;
            }
            List<String> perAuthorAffiliations = splitDash(authorAffiliationSourceIds.get(index));
            for (String sourceAffiliationId : perAuthorAffiliations) {
                String canonicalAffiliationId = resolveCanonicalAffiliationId(sourceAffiliationId);
                String sourceRecordId = buildPublicationAuthorAffiliationSourceRecordId(
                        sourcePublication.getSourceRecordId(),
                        entry.sourceAuthorId(),
                        sourceAffiliationId
                );
                if (isBlank(canonicalAffiliationId)) {
                    sourceLinkService.markConflict(
                            ScholardexEntityType.PUBLICATION_AUTHOR_AFFILIATION,
                            SOURCE_USER_DEFINED,
                            sourceRecordId,
                            REASON_PUBLICATION_AUTHOR_AFFILIATION_UNRESOLVED,
                            sourcePublication.getSourceEventId(),
                            sourcePublication.getSourceBatchId(),
                            sourcePublication.getSourceCorrelationId(),
                            false
                    );
                    continue;
                }
                String dedupKey = canonicalPublication.getId() + "|" + entry.canonicalAuthorId() + "|" + canonicalAffiliationId;
                if (!dedup.add(dedupKey)) {
                    continue;
                }
                edgeWriterService.upsertPublicationAuthorAffiliationEdge(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        canonicalPublication.getId(),
                        entry.canonicalAuthorId(),
                        canonicalAffiliationId,
                        SOURCE_USER_DEFINED,
                        sourceRecordId,
                        sourcePublication.getSourceEventId(),
                        sourcePublication.getSourceBatchId(),
                        sourcePublication.getSourceCorrelationId(),
                        ScholardexSourceLinkService.STATE_LINKED,
                        LINK_REASON_PUBLICATION_AUTHOR_AFFILIATION_BRIDGE,
                        false
                ));
            }
        }
    }

    private AuthorBridgeResult bridgeAuthors(List<String> sourceAuthorIds) {
        if (sourceAuthorIds == null || sourceAuthorIds.isEmpty()) {
            return new AuthorBridgeResult(List.of(), List.of(), List.of());
        }
        Map<String, AuthorBridgeEntry> byCanonicalId = new LinkedHashMap<>();
        for (String sourceAuthorId : sourceAuthorIds) {
            String normalized = normalizeBlank(sourceAuthorId);
            if (normalized == null) {
                continue;
            }
            String canonicalId = normalizeBlank(resolveCanonicalAuthorId(normalized));
            boolean pendingResolution = false;
            if (canonicalId == null) {
                canonicalId = buildCanonicalAuthorFallbackId(SOURCE_USER_DEFINED, normalized);
                pendingResolution = true;
            }
            byCanonicalId.putIfAbsent(canonicalId, new AuthorBridgeEntry(canonicalId, normalized, pendingResolution));
        }
        List<AuthorBridgeEntry> entries = new ArrayList<>(byCanonicalId.values());
        List<String> canonicalAuthorIds = entries.stream().map(AuthorBridgeEntry::canonicalAuthorId).toList();
        List<String> pendingSourceIds = entries.stream()
                .filter(AuthorBridgeEntry::pendingResolution)
                .map(AuthorBridgeEntry::sourceAuthorId)
                .toList();
        return new AuthorBridgeResult(canonicalAuthorIds, pendingSourceIds, entries);
    }

    private String resolveCanonicalAuthorId(String sourceAuthorId) {
        if (sourceAuthorId.startsWith("sauth_")) {
            return sourceAuthorId;
        }
        Optional<ScholardexSourceLink> direct = sourceLinkService.findByKey(
                ScholardexEntityType.AUTHOR,
                SOURCE_USER_DEFINED,
                sourceAuthorId
        );
        if (direct.isPresent() && !isBlank(direct.get().getCanonicalEntityId())) {
            return direct.get().getCanonicalEntityId();
        }
        Optional<ScholardexSourceLink> scopus = sourceLinkService.findByKey(
                ScholardexEntityType.AUTHOR,
                SOURCE_SCOPUS,
                sourceAuthorId
        );
        return scopus.map(ScholardexSourceLink::getCanonicalEntityId).orElse(null);
    }

    private String resolveCanonicalAffiliationId(String sourceAffiliationId) {
        String normalized = normalizeBlank(sourceAffiliationId);
        if (normalized == null) {
            return null;
        }
        if (normalized.startsWith("saff_")) {
            return normalized;
        }
        Optional<ScholardexSourceLink> direct = sourceLinkService.findByKey(
                ScholardexEntityType.AFFILIATION,
                SOURCE_USER_DEFINED,
                normalized
        );
        if (direct.isPresent() && !isBlank(direct.get().getCanonicalEntityId())) {
            return direct.get().getCanonicalEntityId();
        }
        Optional<ScholardexSourceLink> scopus = sourceLinkService.findByKey(
                ScholardexEntityType.AFFILIATION,
                SOURCE_SCOPUS,
                normalized
        );
        return scopus.map(ScholardexSourceLink::getCanonicalEntityId).orElse(null);
    }

    private String buildAuthorshipSourceRecordId(String publicationSourceRecordId, String sourceAuthorId) {
        return normalizeToken(publicationSourceRecordId) + "::author::" + normalizeToken(sourceAuthorId);
    }

    private String buildPublicationAuthorAffiliationSourceRecordId(
            String publicationSourceRecordId,
            String sourceAuthorId,
            String sourceAffiliationId
    ) {
        return normalizeToken(publicationSourceRecordId)
                + "::author::" + normalizeToken(sourceAuthorId)
                + "::affiliation::" + normalizeToken(sourceAffiliationId);
    }

    private List<ScholardexForumFact> findForumCandidates(List<ScholardexForumFact> canonicalForums, UserDefinedForumFact sourceForum) {
        LinkedHashSet<String> issnTokens = new LinkedHashSet<>();
        addIssn(issnTokens, sourceForum.getIssn());
        addIssn(issnTokens, sourceForum.getEIssn());
        List<ScholardexForumFact> candidates = new ArrayList<>();
        if (!issnTokens.isEmpty()) {
            for (ScholardexForumFact canonicalForum : canonicalForums) {
                if (matchesIssn(canonicalForum, issnTokens)) {
                    candidates.add(canonicalForum);
                }
            }
            return candidates;
        }
        String targetName = normalizeName(sourceForum.getPublicationName());
        String targetAgg = normalizeToken(sourceForum.getAggregationType());
        for (ScholardexForumFact canonicalForum : canonicalForums) {
            if (targetName != null
                    && targetName.equals(normalizeName(canonicalForum.getName()))
                    && targetAgg.equals(normalizeToken(canonicalForum.getAggregationType()))) {
                candidates.add(canonicalForum);
            }
        }
        return candidates;
    }

    private boolean matchesIssn(ScholardexForumFact canonicalForum, LinkedHashSet<String> issnTokens) {
        if (issnTokens.contains(normalizeIssn(canonicalForum.getIssn()))
                || issnTokens.contains(normalizeIssn(canonicalForum.getEIssn()))) {
            return true;
        }
        for (String aliasIssn : safeList(canonicalForum.getAliasIssns())) {
            if (issnTokens.contains(normalizeIssn(aliasIssn))) {
                return true;
            }
        }
        return false;
    }

    private List<String> buildAliasIssns(String issn, String eIssn, List<String> existingAliases) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        for (String alias : safeList(existingAliases)) {
            String normalized = normalizeIssn(alias);
            if (normalized != null) {
                aliases.add(normalized);
            }
        }
        aliases.remove(normalizeIssn(issn));
        aliases.remove(normalizeIssn(eIssn));
        return new ArrayList<>(aliases);
    }

    private String buildCanonicalForumId(String issn, String eIssn, String name, String aggregationType) {
        LinkedHashSet<String> issnSet = new LinkedHashSet<>();
        addIssn(issnSet, issn);
        addIssn(issnSet, eIssn);
        String material;
        if (!issnSet.isEmpty()) {
            List<String> sorted = issnSet.stream().sorted().toList();
            material = "issn|" + String.join("|", sorted);
        } else {
            material = "nameAgg|" + normalizeToken(normalizeName(name)) + "|" + normalizeToken(aggregationType);
        }
        return "sforum_" + shortHash(material);
    }

    private void addIssn(LinkedHashSet<String> out, String rawIssn) {
        String normalized = normalizeIssn(rawIssn);
        if (normalized != null) {
            out.add(normalized);
        }
    }

    private String buildCanonicalAuthorFallbackId(String sourceToken, String sourceAuthorId) {
        String normalizedSource = isBlank(sourceToken) ? "unknown" : sourceToken;
        return "sauth_" + shortHash("source|" + normalizedSource + "|author|" + normalizeToken(sourceAuthorId));
    }

    private String normalizeIssn(String rawIssn) {
        String value = normalizeBlank(rawIssn);
        if (value == null) {
            return null;
        }
        String compact = ISSN_NON_ALNUM.matcher(value).replaceAll("").toUpperCase(Locale.ROOT);
        if (compact.length() != 8) {
            return null;
        }
        return compact.substring(0, 4) + "-" + compact.substring(4);
    }

    private String normalizeName(String rawName) {
        String value = normalizeBlank(rawName);
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKD);
        normalized = COMBINING_MARKS.matcher(normalized).replaceAll("");
        normalized = normalized.toLowerCase(Locale.ROOT);
        normalized = NON_ALNUM_OR_SPACE.matcher(normalized).replaceAll(" ");
        normalized = MULTI_SPACE.matcher(normalized).replaceAll(" ").trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeDoi(String rawValue) {
        String value = normalizeBlank(rawValue);
        if (value == null) {
            return null;
        }
        value = DOI_URL_PREFIX.matcher(value).replaceFirst("");
        value = DOI_PREFIX.matcher(value).replaceFirst("");
        value = value.trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() ? null : value;
    }

    private static String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeToken(String value) {
        String normalized = normalizeBlank(value);
        return normalized == null ? "" : normalized.toLowerCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private List<String> splitDash(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String token : value.split("-")) {
            String normalized = normalizeBlank(token);
            if (normalized != null) {
                out.add(normalized);
            }
        }
        return out;
    }

    private String shortHash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12 && i < bytes.length; i++) {
                out.append(String.format("%02x", bytes[i]));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    public record AuthorBridgeResult(
            List<String> canonicalAuthorIds,
            List<String> pendingSourceIds,
            List<AuthorBridgeEntry> entries
    ) {
    }

    public record AuthorBridgeEntry(
            String canonicalAuthorId,
            String sourceAuthorId,
            boolean pendingResolution
    ) {
    }
}
