package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationViewRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ScholardexPublicationBackfillService {

    private static final String SOURCE_LEGACY_PUBLICATION_VIEW = "LEGACY_PUBLICATION_VIEW";

    private final ScholardexPublicationViewRepository publicationViewRepository;
    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexPublicationCanonicalizationService publicationCanonicalizationService;

    public ImportProcessingResult backfillFromLegacyProjection() {
        ImportProcessingResult result = new ImportProcessingResult(20);
        List<ScholardexPublicationView> rows = new ArrayList<>(publicationViewRepository.findAll());
        rows.sort(Comparator.comparing(ScholardexPublicationView::getId, Comparator.nullsLast(String::compareTo)));
        for (ScholardexPublicationView row : rows) {
            result.markProcessed();
            upsertFromLegacyRow(row, result);
        }
        return result;
    }

    private void upsertFromLegacyRow(ScholardexPublicationView row, ImportProcessingResult result) {
        if (row == null) {
            result.markSkipped("null legacy publication row");
            return;
        }

        String doiNormalized = ScholardexPublicationCanonicalizationService.normalizeDoi(row.getDoi());
        String titleNormalized = ScholardexPublicationCanonicalizationService.normalizeTitle(row.getTitle());
        String canonicalId = publicationCanonicalizationService.buildCanonicalPublicationId(
                row.getEid(),
                row.getWosId(),
                row.getGoogleScholarId(),
                null,
                doiNormalized,
                titleNormalized,
                row.getCoverDate(),
                row.getCreator(),
                row.getForumId()
        );
        ScholardexPublicationFact fact = publicationFactRepository.findById(canonicalId).orElseGet(ScholardexPublicationFact::new);
        boolean created = fact.getId() == null;
        Instant now = Instant.now();
        if (fact.getCreatedAt() == null) {
            fact.setCreatedAt(now);
        }
        fact.setId(canonicalId);
        fact.setDoi(row.getDoi());
        fact.setDoiNormalized(doiNormalized);
        fact.setTitle(row.getTitle());
        fact.setTitleNormalized(titleNormalized);
        fact.setEid(row.getEid());
        fact.setWosId(row.getWosId());
        fact.setGoogleScholarId(row.getGoogleScholarId());
        fact.setSubtype(row.getSubtype());
        fact.setSubtypeDescription(row.getSubtypeDescription());
        fact.setScopusSubtype(row.getScopusSubtype());
        fact.setScopusSubtypeDescription(row.getScopusSubtypeDescription());
        fact.setCreator(row.getCreator());
        fact.setCoverDate(row.getCoverDate());
        fact.setCoverDisplayDate(row.getCoverDisplayDate());
        fact.setVolume(row.getVolume());
        fact.setIssueIdentifier(row.getIssueIdentifier());
        fact.setDescription(row.getDescription());
        fact.setAuthorCount(row.getAuthorCount());
        fact.setCorrespondingAuthors(row.getCorrespondingAuthors() == null ? List.of() : new ArrayList<>(row.getCorrespondingAuthors()));
        fact.setOpenAccess(row.isOpenAccess());
        fact.setFreetoread(row.getFreetoread());
        fact.setFreetoreadLabel(row.getFreetoreadLabel());
        fact.setFundingId(row.getFundingId());
        fact.setArticleNumber(row.getArticleNumber());
        fact.setPageRange(row.getPageRange());
        fact.setApproved(row.isApproved());
        if (fact.getSource() == null) {
            fact.setSource(SOURCE_LEGACY_PUBLICATION_VIEW);
        }
        if (fact.getSourceRecordId() == null) {
            fact.setSourceRecordId(row.getId());
        }
        ScholardexPublicationCanonicalizationService.AuthorBridgeResult authorBridge =
                publicationCanonicalizationService.bridgeAuthorIds(row.getAuthorIds(), fact.getSource());
        fact.setAuthorIds(authorBridge.canonicalAuthorIds());
        fact.setPendingAuthorSourceIds(authorBridge.pendingSourceIds());
        fact.setAffiliationIds(row.getAffiliationIds() == null ? List.of() : new ArrayList<>(row.getAffiliationIds()));
        fact.setForumId(row.getForumId());
        fact.setCitedByCount(row.getCitedByCount());
        fact.setUpdatedAt(now);
        publicationFactRepository.save(fact);
        publicationCanonicalizationService.syncAuthorshipEdges(fact, authorBridge);
        upsertSourceLink(fact, row.getId());
        if (created) {
            result.markImported();
        } else {
            result.markUpdated();
        }
    }

    private void upsertSourceLink(ScholardexPublicationFact fact, String fallbackRecordId) {
        String source = fact.getSource() == null ? SOURCE_LEGACY_PUBLICATION_VIEW : fact.getSource();
        String sourceRecordId = fact.getSourceRecordId() == null ? fallbackRecordId : fact.getSourceRecordId();
        if (sourceRecordId == null || sourceRecordId.isBlank()) {
            return;
        }
        sourceLinkService.link(
                ScholardexEntityType.PUBLICATION,
                source,
                sourceRecordId,
                fact.getId(),
                "legacy-backfill",
                fact.getSourceEventId(),
                fact.getSourceBatchId(),
                fact.getSourceCorrelationId(),
                false
        );
    }
}
