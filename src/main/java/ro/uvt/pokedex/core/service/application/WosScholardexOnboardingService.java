package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.CanonicalPublicationConstants;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Drives forum onboarding from WoS journal identities and Scopus forum facts, then links publications to
 * their WoS source ids. The per-record forum find-or-create/merge core (identity index, ISSN token-hygiene,
 * candidate resolution, save-or-conflict, source-link batching) lives in {@link ForumMergeEngine} (H66B M1c);
 * this service builds the source-record lists and orchestrates the loop + flush, and owns the
 * publication→WoS source-link onboarding (which is not forum building).
 */
@Service
@RequiredArgsConstructor
public class WosScholardexOnboardingService {

    private static final String SOURCE_WOS = "WOS";

    private static final String REASON_SOURCE_ID_COLLISION = "SOURCE_ID_COLLISION";
    private static final String REASON_WOS_PUBLICATION_LINK = "wos-publication-link";

    private final WosJournalIdentityRepository journalIdentityRepository;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    private final ConflictRecorder conflictRecorder;
    private final ForumMergeEngine forumMergeEngine;

    public ImportProcessingResult runWosOnboarding(String batchId, String correlationId) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        // Read the stage-3 WoS journal-identity facts directly (the same source rebuildWosProjections
        // projects 1:1 into reporting_read.wos_ranking_view). Forum canonicalization is stage-3, so it
        // must not depend on the stage-4 projection: doing so created a backwards dependency that ran
        // onboarding before the view it read was built — leaving WoS canonical forums unrebuildable
        // (immortal/stale). Mapping mirrors WosProjectionBuilderService.toRankingView. Sorted by id for
        // deterministic processing order (was ORDER BY journal_id).
        List<WosRankingView> journals = journalIdentityRepository.findAll().stream()
                .sorted(Comparator.comparing(WosJournalIdentity::getId))
                .map(WosScholardexOnboardingService::toRankingView)
                .toList();

        ForumMergeEngine.Context ctx = forumMergeEngine.startWosRun(journals);
        Instant now = Instant.now();
        for (WosRankingView journal : journals) {
            result.markProcessed();
            forumMergeEngine.upsertForumFromWos(journal, ctx, batchId, correlationId, now, result);
        }
        forumMergeEngine.flush(ctx);

        onboardPublicationWosLinks(batchId, correlationId, result);
        return result;
    }

    /**
     * Canonicalize every Scopus forum so it has exactly one canonical Scholardex forum, linked via a
     * {@code FORUM/SCOPUS} source link (the resolution surface H55.2 uses to re-point publication
     * {@code forumId} from the raw Scopus forum id to the canonical {@code sforum_…} id).
     *
     * <p>Symmetric to {@link #runWosOnboarding}. Scopus forums already folded into a canonical forum by
     * WoS onboarding (their {@code sourceId} already appears in some canonical's {@code scopusForumIds})
     * are not re-merged — only their source link is ensured. The remaining orphan Scopus forums (no
     * canonical at all) are resolved by ISSN/name against existing canonical forums (deduping orphans
     * that share an ISSN) or, failing that, materialize a new canonical forum. Run before publication
     * canonicalization so the FORUM source links exist when publications are re-pointed.
     */
    public ImportProcessingResult runScopusForumCanonicalization(String batchId, String correlationId) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        ForumMergeEngine.Context ctx = forumMergeEngine.startScopusRun();
        Instant now = Instant.now();
        for (var scopusForum : ctx.scopusForums()) {
            result.markProcessed();
            forumMergeEngine.upsertForumFromScopus(scopusForum, ctx, batchId, correlationId, now, result);
        }
        forumMergeEngine.flush(ctx);
        return result;
    }

    /**
     * Map a stage-3 WoS journal identity to the internal {@link WosRankingView} DTO consumed by forum
     * onboarding. Mirrors {@code WosProjectionBuilderService.toRankingView} field-for-field (the
     * onboarding-relevant subset), so reading identities here is equivalent to reading the projection
     * that is built 1:1 from them — minus the backwards stage-4 dependency.
     */
    private static WosRankingView toRankingView(WosJournalIdentity identity) {
        WosRankingView view = new WosRankingView();
        view.setId(identity.getId());
        view.setName(identity.getTitle());
        view.setIssn(identity.getPrimaryIssn());
        view.setEIssn(identity.getEIssn());
        view.setAlternativeIssns(identity.getAliasIssns() == null ? List.of() : new ArrayList<>(identity.getAliasIssns()));
        view.setAlternativeNames(identity.getAlternativeNames() == null ? List.of() : new ArrayList<>(identity.getAlternativeNames()));
        return view;
    }

    private void onboardPublicationWosLinks(
            String batchId,
            String correlationId,
            ImportProcessingResult result
    ) {
        List<ScholardexPublicationFact> publications = new ArrayList<>(scholardexPublicationFactRepository.findAll());
        publications.sort(Comparator.comparing(ScholardexPublicationFact::getId, Comparator.nullsLast(String::compareTo)));
        for (ScholardexPublicationFact publication : publications) {
            String wosId = normalizeBlank(publication.getWosId());
            if (wosId == null || CanonicalPublicationConstants.NON_WOS_ID.equalsIgnoreCase(wosId)) {
                continue;
            }
            List<ScholardexSourceLink> existing = sourceLinkService
                    .findByEntityTypeAndSourceRecordId(ScholardexEntityType.PUBLICATION, wosId);
            List<String> distinctCanonicalIds = existing.stream()
                    .map(ScholardexSourceLink::getCanonicalEntityId)
                    .filter(id -> id != null && !id.isBlank())
                    .distinct()
                    .toList();
            if (distinctCanonicalIds.size() > 1
                    || (distinctCanonicalIds.size() == 1 && !distinctCanonicalIds.getFirst().equals(publication.getId()))) {
                upsertConflictSourceLink(ScholardexEntityType.PUBLICATION, SOURCE_WOS, wosId, REASON_SOURCE_ID_COLLISION, batchId, correlationId);
                openConflict(
                        ScholardexEntityType.PUBLICATION,
                        SOURCE_WOS,
                        wosId,
                        REASON_SOURCE_ID_COLLISION,
                        distinctCanonicalIds,
                        batchId,
                        correlationId
                );
                result.markSkipped("wos-publication-source-link-collision wosId=" + wosId);
                continue;
            }
            upsertLinkedSourceLink(
                    ScholardexEntityType.PUBLICATION,
                    SOURCE_WOS,
                    wosId,
                    publication.getId(),
                    REASON_WOS_PUBLICATION_LINK,
                    batchId,
                    correlationId,
                    false
            );
            result.markUpdated();
        }
    }

    /**
     * @param explicitReplayAttempt forum canonicalization passes {@code true}: it only reaches a link
     *     once it has resolved a single canonical forum (incl. H55.6 primary-ISSN disambiguation), so a
     *     definitive link must override a stale CONFLICT/SKIPPED source link left by an earlier ambiguous
     *     run — otherwise the CONFLICT-&gt;LINKED transition is rejected and the forum never resolves.
     *     Publication links pass {@code false} so they do not silently override their own collision
     *     quarantines.
     */
    private void upsertLinkedSourceLink(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String canonicalEntityId,
            String reason,
            String batchId,
            String correlationId,
            boolean explicitReplayAttempt
    ) {
        sourceLinkService.link(
                entityType,
                source,
                sourceRecordId,
                canonicalEntityId,
                reason,
                null,
                batchId,
                correlationId,
                explicitReplayAttempt
        );
    }

    private void upsertConflictSourceLink(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String reason,
            String batchId,
            String correlationId
    ) {
        conflictRecorder.markConflictLink(entityType, source, sourceRecordId, reason, batchId, correlationId);
    }

    private void openConflict(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String reasonCode,
            List<String> candidateIds,
            String batchId,
            String correlationId
    ) {
        conflictRecorder.openConflict(entityType, source, sourceRecordId, reasonCode, candidateIds, batchId, correlationId);
    }

    private String normalizeBlank(String value) {
        return ForumIdentityNormalization.normalizeBlank(value);
    }
}
