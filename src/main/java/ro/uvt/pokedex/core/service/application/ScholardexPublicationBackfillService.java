package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ScholardexPublicationBackfillService {

    private static final String SOURCE_LEGACY_PUBLICATION_VIEW = "LEGACY_PUBLICATION_VIEW";

    private final JdbcTemplate jdbcTemplate;
    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexPublicationCanonicalizationService publicationCanonicalizationService;

    public ImportProcessingResult backfillFromLegacyProjection() {
        ImportProcessingResult result = new ImportProcessingResult(20);
        List<ScholardexPublicationView> rows = new ArrayList<>(jdbcTemplate.query(
                "SELECT id, doi, doi_normalized, eid, title, subtype, subtype_description, scopus_subtype, scopus_subtype_description, creator, cover_date, cover_display_date, volume, issue_identifier, description, author_count, corresponding_authors, open_access, freetoread, freetoread_label, funding_id, article_number, page_range, approved, author_ids, affiliation_ids, forum_id, citing_publication_ids, cited_by_count, wos_id, google_scholar_id, build_version, build_at, updated_at, scopus_lineage, wos_lineage, scholar_lineage, linker_version, linker_run_id, linked_at FROM reporting_read.scholardex_publication_view",
                (rs, rowNum) -> mapRow(rs)));
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

    private ScholardexPublicationView mapRow(java.sql.ResultSet rs) throws SQLException {
        ScholardexPublicationView v = new ScholardexPublicationView();
        v.setId(rs.getString("id"));
        v.setDoi(rs.getString("doi"));
        v.setDoiNormalized(rs.getString("doi_normalized"));
        v.setEid(rs.getString("eid"));
        v.setTitle(rs.getString("title"));
        v.setSubtype(rs.getString("subtype"));
        v.setSubtypeDescription(rs.getString("subtype_description"));
        v.setScopusSubtype(rs.getString("scopus_subtype"));
        v.setScopusSubtypeDescription(rs.getString("scopus_subtype_description"));
        v.setCreator(rs.getString("creator"));
        v.setCoverDate(rs.getString("cover_date"));
        v.setCoverDisplayDate(rs.getString("cover_display_date"));
        v.setVolume(rs.getString("volume"));
        v.setIssueIdentifier(rs.getString("issue_identifier"));
        v.setDescription(rs.getString("description"));
        v.setAuthorCount(rs.getObject("author_count", Integer.class));
        v.setCorrespondingAuthors(toList(rs.getArray("corresponding_authors")));
        v.setOpenAccess(rs.getBoolean("open_access"));
        v.setFreetoread(rs.getString("freetoread"));
        v.setFreetoreadLabel(rs.getString("freetoread_label"));
        v.setFundingId(rs.getString("funding_id"));
        v.setArticleNumber(rs.getString("article_number"));
        v.setPageRange(rs.getString("page_range"));
        v.setApproved(rs.getBoolean("approved"));
        v.setAuthorIds(toList(rs.getArray("author_ids")));
        v.setAffiliationIds(toList(rs.getArray("affiliation_ids")));
        v.setForumId(rs.getString("forum_id"));
        v.setCitingPublicationIds(toList(rs.getArray("citing_publication_ids")));
        v.setCitedByCount(rs.getObject("cited_by_count", Integer.class));
        v.setWosId(rs.getString("wos_id"));
        v.setGoogleScholarId(rs.getString("google_scholar_id"));
        Timestamp buildAt = rs.getTimestamp("build_at");
        v.setBuildAt(buildAt == null ? null : buildAt.toInstant());
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        v.setUpdatedAt(updatedAt == null ? null : updatedAt.toInstant());
        return v;
    }

    private List<String> toList(Array sqlArray) throws SQLException {
        if (sqlArray == null) return new ArrayList<>();
        String[] arr = (String[]) sqlArray.getArray();
        return arr == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(arr));
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
