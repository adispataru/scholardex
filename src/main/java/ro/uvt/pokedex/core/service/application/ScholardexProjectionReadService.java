package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ScholardexProjectionReadService {

    private final JdbcTemplate jdbcTemplate;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexAuthorFactRepository canonicalAuthorFactRepository;
    private final ScholardexAffiliationFactRepository canonicalAffiliationFactRepository;
    private final ScholardexForumFactRepository canonicalForumFactRepository;
    private final ScholardexEdgeWriterService edgeWriterService;
    private final PostgresScholardexProjectionReadPort postgresProjectionReadPort;

    public List<ScholardexPublicationView> findAllPublicationsByAuthorsIn(Collection<String> authorIds) {
        List<String> resolvedAuthorIds = resolveCanonicalIds(ScholardexEntityType.AUTHOR, authorIds);
        if (resolvedAuthorIds.isEmpty()) {
            return List.of();
        }
        Set<String> publicationIds = postgresProjectionReadPort.findPublicationIdsByAuthorIdIn(resolvedAuthorIds);
        if (publicationIds.isEmpty()) {
            return List.of();
        }
        return dedupeAndSortPublications(postgresProjectionReadPort.findPublicationsByIdIn(publicationIds));
    }

    public List<ScholardexPublicationView> findAllPublicationsByAuthorsContaining(String authorId) {
        return findAllPublicationsByAuthorsIn(List.of(authorId));
    }

    public List<ScholardexPublicationView> findAllPublicationsByAffiliationsContaining(String affiliationId) {
        List<String> resolvedAffiliationIds = resolveCanonicalIds(ScholardexEntityType.AFFILIATION, List.of(affiliationId));
        if (resolvedAffiliationIds.isEmpty()) {
            return List.of();
        }
        Set<String> authorIds = new LinkedHashSet<>();
        for (String canonicalAffiliationId : resolvedAffiliationIds) {
            authorIds.addAll(postgresProjectionReadPort.findAuthorIdsByAffiliationId(canonicalAffiliationId));
        }
        if (authorIds.isEmpty()) {
            return List.of();
        }
        Set<String> publicationIds = postgresProjectionReadPort.findPublicationIdsByAuthorIdIn(authorIds);
        if (publicationIds.isEmpty()) {
            return List.of();
        }
        return dedupeAndSortPublications(postgresProjectionReadPort.findPublicationsByIdIn(publicationIds));
    }

    public List<ScholardexPublicationView> findAllPublicationsByIdIn(Collection<String> ids) {
        Map<String, ScholardexPublicationView> out = new LinkedHashMap<>();
        postgresProjectionReadPort.findPublicationsByIdIn(ids).forEach(pub -> out.putIfAbsent(pub.getId(), pub));
        if (!ids.isEmpty()) {
            postgresProjectionReadPort.findPublicationsByEidIn(ids).forEach(pub -> out.putIfAbsent(pub.getId(), pub));
        }
        List<ScholardexPublicationView> publications = new ArrayList<>(out.values());
        PublicationOrderingSupport.sortPublicationsInPlace(publications);
        return publications;
    }

    public Optional<ScholardexPublicationView> findPublicationById(String id) {
        return postgresProjectionReadPort.findPublicationById(id);
    }

    public Optional<ScholardexPublicationView> findPublicationByEid(String eid) {
        return postgresProjectionReadPort.findPublicationByEid(eid);
    }

    public Optional<ScholardexPublicationView> findPublicationByAnyId(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return postgresProjectionReadPort.findPublicationByAnyId(key);
    }

    public List<ScholardexPublicationView> findPublicationsByTitleContainingIgnoreCaseOrderByCoverDateDesc(String title) {
        List<ScholardexPublicationView> out = new ArrayList<>(postgresProjectionReadPort.findPublicationsByTitleContainingIgnoreCase(title));
        PublicationOrderingSupport.sortPublicationsInPlace(out);
        return out;
    }

    public List<ScoringPublicationReadModel> findAllScoringPublicationsByAuthorsIn(Collection<String> authorIds) {
        List<String> resolvedAuthorIds = resolveCanonicalIds(ScholardexEntityType.AUTHOR, authorIds);
        if (resolvedAuthorIds.isEmpty()) {
            return List.of();
        }
        Set<String> publicationIds = postgresProjectionReadPort.findPublicationIdsByAuthorIdIn(resolvedAuthorIds);
        if (publicationIds.isEmpty()) {
            return List.of();
        }
        Map<String, ScoringPublicationReadModel> byId = new LinkedHashMap<>();
        for (ScoringPublicationReadModel pub : postgresProjectionReadPort.findScoringPublicationsByIdIn(publicationIds)) {
            byId.putIfAbsent(pub.getId(), pub);
        }
        return new ArrayList<>(byId.values());
    }

    public Optional<ScoringPublicationReadModel> findScoringPublicationById(String id) {
        return postgresProjectionReadPort.findScoringPublicationById(id);
    }

    public List<ScholardexCitationView> findAllCitationsByCitedIdIn(Collection<String> citedIds) {
        List<String> publicationIds = resolvePublicationIdsByAnyKeys(citedIds);
        if (publicationIds.isEmpty()) {
            return List.of();
        }
        List<ScholardexCitationView> citations = postgresProjectionReadPort.findCitationsByCitedPublicationIdIn(publicationIds);
        return filterValidCitations(citations);
    }

    public List<ScholardexCitationView> findAllCitationsByCitedId(String citedId) {
        Optional<ScholardexPublicationView> cited = findPublicationByAnyId(citedId);
        if (cited.isEmpty() || cited.get().getId() == null) {
            return List.of();
        }
        List<ScholardexCitationView> citations = postgresProjectionReadPort.findCitationsByCitedPublicationId(cited.get().getId());
        return filterValidCitations(citations);
    }

    public long countCitationsByCitedId(String citedId) {
        return findAllCitationsByCitedId(citedId).size();
    }

    public List<ScholardexForumView> findForumsByIdIn(Collection<String> forumIds) {
        List<String> resolvedForumIds = resolveCanonicalIds(ScholardexEntityType.FORUM, forumIds);
        return postgresProjectionReadPort.findForumsByIdIn(resolvedForumIds);
    }

    public Optional<ScholardexForumView> findForumById(String id) {
        List<String> resolvedForumIds = resolveCanonicalIds(ScholardexEntityType.FORUM, List.of(id));
        return postgresProjectionReadPort.findForumsByIdIn(resolvedForumIds).stream().findFirst();
    }

    public List<ScholardexForumView> findAllForums() {
        return postgresProjectionReadPort.findAllForums();
    }

    public List<ScholardexAuthorView> findAuthorsByIdIn(Collection<String> authorIds) {
        List<String> resolvedAuthorIds = resolveCanonicalIds(ScholardexEntityType.AUTHOR, authorIds);
        return postgresProjectionReadPort.findAuthorsByIdIn(resolvedAuthorIds);
    }

    public List<ScholardexAuthorView> findAllAuthors() {
        return postgresProjectionReadPort.findAllAuthors();
    }

    public List<ScholardexAuthorView> findAuthorsByAffiliationId(String affiliationId) {
        List<String> resolvedAffiliationIds = resolveCanonicalIds(ScholardexEntityType.AFFILIATION, List.of(affiliationId));
        Set<String> authorIds = new LinkedHashSet<>();
        for (String canonicalAffiliationId : resolvedAffiliationIds) {
            authorIds.addAll(postgresProjectionReadPort.findAuthorIdsByAffiliationId(canonicalAffiliationId));
        }
        return postgresProjectionReadPort.findAuthorsByIdIn(authorIds);
    }

    public Optional<ScholardexAuthorView> findAuthorById(String id) {
        List<String> resolvedAuthorIds = resolveCanonicalIds(ScholardexEntityType.AUTHOR, List.of(id));
        return postgresProjectionReadPort.findAuthorsByIdIn(resolvedAuthorIds).stream().findFirst();
    }

    public List<ScholardexAuthorView> findAuthorsByNameContainsIgnoreCase(String authorName) {
        return postgresProjectionReadPort.findAuthorsByNameContainsIgnoreCase(authorName);
    }

    public List<ScholardexAffiliationView> findAllAffiliations() {
        return postgresProjectionReadPort.findAllAffiliations();
    }

    public Optional<ScholardexAffiliationView> findAffiliationById(String id) {
        List<String> resolvedAffiliationIds = resolveCanonicalIds(ScholardexEntityType.AFFILIATION, List.of(id));
        return postgresProjectionReadPort.findAffiliationsByIdIn(resolvedAffiliationIds).stream().findFirst();
    }

    public List<ScholardexAffiliationView> findAffiliationsByCountry(String country) {
        return postgresProjectionReadPort.findAffiliationsByCountry(country);
    }

    public List<ScholardexAffiliationView> findAffiliationsByNameContains(String name) {
        return postgresProjectionReadPort.findAffiliationsByNameContains(name);
    }

    public List<ScholardexAffiliationView> findAffiliationsByIdIn(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return postgresProjectionReadPort.findAffiliationsByIdIn(ids);
    }

    public Optional<ScholardexPublicationView> findPublicationViewById(String id) {
        List<ScholardexPublicationView> rows = jdbcTemplate.query(
                "SELECT id, doi, doi_normalized, eid, title, subtype, subtype_description, scopus_subtype, scopus_subtype_description, creator, cover_date, cover_display_date, volume, issue_identifier, description, author_count, corresponding_authors, open_access, freetoread, freetoread_label, funding_id, article_number, page_range, approved, author_ids, affiliation_ids, forum_id, citing_publication_ids, cited_by_count, wos_id, google_scholar_id, build_version, build_at, updated_at, scopus_lineage, wos_lineage, scholar_lineage, linker_version, linker_run_id, linked_at FROM reporting_read.scholardex_publication_view WHERE id = ?",
                (rs, rowNum) -> mapPublicationViewRow(rs),
                id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void savePublicationView(ScholardexPublicationView view) {
        jdbcTemplate.update(
                "UPDATE reporting_read.scholardex_publication_view SET subtype = ?, subtype_description = ?, updated_at = ? WHERE id = ?",
                view.getSubtype(), view.getSubtypeDescription(), java.sql.Timestamp.from(java.time.Instant.now()), view.getId());
    }

    private ScholardexPublicationView mapPublicationViewRow(java.sql.ResultSet rs) throws java.sql.SQLException {
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
        v.setCorrespondingAuthors(toStringList(rs.getArray("corresponding_authors")));
        v.setOpenAccess(rs.getBoolean("open_access"));
        v.setFreetoread(rs.getString("freetoread"));
        v.setFreetoreadLabel(rs.getString("freetoread_label"));
        v.setFundingId(rs.getString("funding_id"));
        v.setArticleNumber(rs.getString("article_number"));
        v.setPageRange(rs.getString("page_range"));
        v.setApproved(rs.getBoolean("approved"));
        v.setAuthorIds(toStringList(rs.getArray("author_ids")));
        v.setAffiliationIds(toStringList(rs.getArray("affiliation_ids")));
        v.setForumId(rs.getString("forum_id"));
        v.setCitingPublicationIds(new LinkedHashSet<>(toStringList(rs.getArray("citing_publication_ids"))));
        v.setCitedByCount(rs.getObject("cited_by_count", Integer.class));
        v.setWosId(rs.getString("wos_id"));
        v.setGoogleScholarId(rs.getString("google_scholar_id"));
        v.setBuildVersion(rs.getString("build_version"));
        java.sql.Timestamp buildAt = rs.getTimestamp("build_at");
        v.setBuildAt(buildAt == null ? null : buildAt.toInstant());
        java.sql.Timestamp updatedAt = rs.getTimestamp("updated_at");
        v.setUpdatedAt(updatedAt == null ? null : updatedAt.toInstant());
        v.setScopusLineage(rs.getString("scopus_lineage"));
        v.setWosLineage(rs.getString("wos_lineage"));
        v.setScholarLineage(rs.getString("scholar_lineage"));
        v.setLinkerVersion(rs.getString("linker_version"));
        v.setLinkerRunId(rs.getString("linker_run_id"));
        java.sql.Timestamp linkedAt = rs.getTimestamp("linked_at");
        v.setLinkedAt(linkedAt == null ? null : linkedAt.toInstant());
        return v;
    }

    private List<String> toStringList(java.sql.Array sqlArray) throws java.sql.SQLException {
        if (sqlArray == null) return new ArrayList<>();
        String[] arr = (String[]) sqlArray.getArray();
        return arr == null ? new ArrayList<>() : new ArrayList<>(java.util.Arrays.asList(arr));
    }

    public ScholardexForumView saveForum(ScholardexForumView forum) {
        String sourceRecordId = normalizeBlank(forum.getId());
        String canonicalId = resolveCanonicalId(ScholardexEntityType.FORUM, sourceRecordId)
                .orElse(sourceRecordId == null
                        ? "sforum_manual_" + Integer.toHexString(Objects.hash(forum.getPublicationName(), forum.getIssn(), forum.getEIssn(), forum.getAggregationType()))
                        : sourceRecordId);
        java.time.Instant now = java.time.Instant.now();
        ScholardexForumFact canonicalFact = canonicalForumFactRepository.findById(canonicalId).orElseGet(ScholardexForumFact::new);
        if (canonicalFact.getCreatedAt() == null) {
            canonicalFact.setCreatedAt(now);
        }
        canonicalFact.setId(canonicalId);
        canonicalFact.setName(forum.getPublicationName());
        canonicalFact.setNameNormalized(normalizeName(forum.getPublicationName()));
        canonicalFact.setIssn(normalizeBlank(forum.getIssn()));
        canonicalFact.setEIssn(normalizeBlank(forum.getEIssn()));
        canonicalFact.setAggregationType(normalizeBlank(forum.getAggregationType()));
        canonicalFact.setAggregationTypeNormalized(normalizeName(forum.getAggregationType()));
        canonicalFact.setSource("MANUAL_FORUM_EDIT");
        canonicalFact.setSourceRecordId(sourceRecordId);
        canonicalFact.setUpdatedAt(now);
        canonicalForumFactRepository.save(canonicalFact);

        if (sourceRecordId != null) {
            upsertSourceLink(ScholardexEntityType.FORUM, "MANUAL_FORUM_EDIT", sourceRecordId, canonicalId, "manual-forum-save");
        }

        ScholardexForumView out = new ScholardexForumView();
        out.setId(canonicalId);
        out.setPublicationName(forum.getPublicationName());
        out.setIssn(forum.getIssn());
        out.setEIssn(forum.getEIssn());
        out.setAggregationType(forum.getAggregationType());
        return out;
    }

    public ScholardexAuthorView saveAuthor(ScholardexAuthorView author) {
        String sourceRecordId = normalizeBlank(author.getId());
        String canonicalId = resolveCanonicalId(ScholardexEntityType.AUTHOR, sourceRecordId)
                .orElse(sourceRecordId == null ? "sauth_manual_" + Integer.toHexString(Objects.hash(author.getName())) : sourceRecordId);
        List<String> affiliationSourceIds = author.getAffiliations() == null
                ? List.of()
                : author.getAffiliations().stream().map(ScholardexAffiliationView::getAfid).filter(Objects::nonNull).toList();
        List<String> affiliationIds = resolveCanonicalIds(ScholardexEntityType.AFFILIATION, affiliationSourceIds);

        ScholardexAuthorFact canonicalFact = canonicalAuthorFactRepository.findById(canonicalId).orElseGet(ScholardexAuthorFact::new);
        java.time.Instant now = java.time.Instant.now();
        if (canonicalFact.getCreatedAt() == null) {
            canonicalFact.setCreatedAt(now);
        }
        canonicalFact.setId(canonicalId);
        canonicalFact.setDisplayName(author.getName());
        canonicalFact.setNameNormalized(normalizeName(author.getName()));
        canonicalFact.setAffiliationIds(new ArrayList<>(affiliationIds));
        canonicalFact.setSource("MANUAL_AUTHOR_EDIT");
        canonicalFact.setSourceRecordId(sourceRecordId);
        canonicalFact.setUpdatedAt(now);
        canonicalAuthorFactRepository.save(canonicalFact);

        if (sourceRecordId != null) {
            upsertSourceLink(ScholardexEntityType.AUTHOR, "MANUAL_AUTHOR_EDIT", sourceRecordId, canonicalId, "manual-author-save");
        }

        for (String affiliationId : affiliationIds) {
            edgeWriterService.upsertAuthorAffiliationEdge(new ScholardexEdgeWriterService.EdgeWriteCommand(
                    canonicalId,
                    affiliationId,
                    "MANUAL_AUTHOR_EDIT",
                    canonicalId + "::affiliation::" + affiliationId,
                    null,
                    null,
                    null,
                    ScholardexSourceLinkService.STATE_LINKED,
                    "manual-author-save",
                    false
            ));
        }

        ScholardexAuthorView out = new ScholardexAuthorView();
        out.setId(canonicalId);
        out.setName(author.getName());
        out.setAlternativeNames(author.getAlternativeNames() == null ? List.of() : new ArrayList<>(author.getAlternativeNames()));
        out.setAffiliations(affiliationIds.stream().map(id -> {
            ScholardexAffiliationView affiliation = new ScholardexAffiliationView();
            affiliation.setAfid(id);
            return affiliation;
        }).toList());
        return out;
    }

    public ScholardexAffiliationView saveAffiliation(ScholardexAffiliationView affiliation) {
        String sourceRecordId = normalizeBlank(affiliation.getAfid());
        String canonicalId = resolveCanonicalId(ScholardexEntityType.AFFILIATION, sourceRecordId)
                .orElse(sourceRecordId == null ? "saff_manual_" + Integer.toHexString(Objects.hash(affiliation.getName(), affiliation.getCity(), affiliation.getCountry())) : sourceRecordId);
        java.time.Instant now = java.time.Instant.now();

        ScholardexAffiliationFact canonicalFact = canonicalAffiliationFactRepository.findById(canonicalId).orElseGet(ScholardexAffiliationFact::new);
        if (canonicalFact.getCreatedAt() == null) {
            canonicalFact.setCreatedAt(now);
        }
        canonicalFact.setId(canonicalId);
        canonicalFact.setName(affiliation.getName());
        canonicalFact.setNameNormalized(normalizeName(affiliation.getName()));
        canonicalFact.setCity(affiliation.getCity());
        canonicalFact.setCountry(affiliation.getCountry());
        canonicalFact.setSource("MANUAL_AFFILIATION_EDIT");
        canonicalFact.setSourceRecordId(sourceRecordId);
        canonicalFact.setUpdatedAt(now);
        canonicalAffiliationFactRepository.save(canonicalFact);

        if (sourceRecordId != null) {
            upsertSourceLink(ScholardexEntityType.AFFILIATION, "MANUAL_AFFILIATION_EDIT", sourceRecordId, canonicalId, "manual-affiliation-save");
        }

        ScholardexAffiliationView out = new ScholardexAffiliationView();
        out.setAfid(canonicalId);
        out.setName(affiliation.getName());
        out.setCity(affiliation.getCity());
        out.setCountry(affiliation.getCountry());
        return out;
    }

    private Optional<String> resolveCanonicalId(ScholardexEntityType entityType, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Optional.empty();
        }
        List<ScholardexSourceLink> mapped = sourceLinkService.findByEntityTypeAndSourceRecordId(entityType, candidate);
        if (mapped != null) {
            return mapped.stream()
                    .map(ScholardexSourceLink::getCanonicalEntityId)
                    .filter(id -> id != null && !id.isBlank())
                    .findFirst();
        }
        return Optional.empty();
    }

    private List<String> resolveCanonicalIds(ScholardexEntityType entityType, Collection<String> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalizedCandidates = new LinkedHashSet<>();
        for (String id : candidateIds) {
            String normalized = normalizeBlank(id);
            if (normalized != null) {
                normalizedCandidates.add(normalized);
            }
        }
        if (normalizedCandidates.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> resolved = new LinkedHashSet<>(normalizedCandidates);
        List<ScholardexSourceLink> mapped = sourceLinkService.findByEntityTypeAndSourceRecordIds(entityType, normalizedCandidates);
        if (mapped != null && !mapped.isEmpty()) {
            mapped.stream()
                    .map(ScholardexSourceLink::getCanonicalEntityId)
                    .filter(candidate -> candidate != null && !candidate.isBlank())
                    .forEach(resolved::add);
        }
        return new ArrayList<>(resolved);
    }

    private void upsertSourceLink(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String canonicalId,
            String reason
    ) {
        sourceLinkService.link(
                entityType,
                source,
                sourceRecordId,
                canonicalId,
                reason,
                null,
                null,
                null,
                false
        );
    }

    private String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeName(String value) {
        String normalized = normalizeBlank(value);
        return normalized == null ? null : normalized.toLowerCase(java.util.Locale.ROOT);
    }

    private List<ScholardexPublicationView> dedupeAndSortPublications(List<ScholardexPublicationView> publications) {
        Map<String, ScholardexPublicationView> byId = new LinkedHashMap<>();
        for (ScholardexPublicationView publication : publications) {
            byId.putIfAbsent(publication.getId(), publication);
        }
        List<ScholardexPublicationView> out = new ArrayList<>(byId.values());
        PublicationOrderingSupport.sortPublicationsInPlace(out);
        return out;
    }

    private List<String> resolvePublicationIdsByAnyKeys(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> normalizedKeys = keys.stream()
                .map(this::normalizeBlank)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (normalizedKeys.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> publicationIds = new LinkedHashSet<>();
        LinkedHashSet<String> unresolvedKeys = new LinkedHashSet<>();

        for (String key : normalizedKeys) {
            if (isCanonicalPublicationId(key)) {
                publicationIds.add(key);
            } else {
                unresolvedKeys.add(key);
            }
        }

        if (!unresolvedKeys.isEmpty()) {
            List<ScholardexPublicationView> idMatches = postgresProjectionReadPort.findPublicationsByIdIn(unresolvedKeys);
            for (ScholardexPublicationView match : idMatches) {
                publicationIds.add(match.getId());
                String matchedKey = normalizeBlank(match.getId());
                if (matchedKey != null) {
                    unresolvedKeys.remove(matchedKey);
                }
            }
        }

        if (!unresolvedKeys.isEmpty()) {
            List<ScholardexPublicationView> eidMatches = postgresProjectionReadPort.findPublicationsByEidIn(unresolvedKeys);
            for (ScholardexPublicationView match : eidMatches) {
                publicationIds.add(match.getId());
                String matchedEid = normalizeBlank(match.getEid());
                if (matchedEid != null) {
                    unresolvedKeys.remove(matchedEid);
                }
            }
        }

        for (String unresolvedKey : unresolvedKeys) {
            findPublicationByAnyId(unresolvedKey).map(ScholardexPublicationView::getId).ifPresent(publicationIds::add);
        }
        return new ArrayList<>(publicationIds);
    }

    private boolean isCanonicalPublicationId(String key) {
        return key != null && key.regionMatches(true, 0, "spub_", 0, "spub_".length());
    }

    private List<ScholardexCitationView> filterValidCitations(List<ScholardexCitationView> citations) {
        if (citations.isEmpty()) {
            return List.of();
        }
        Set<String> publicationIds = new LinkedHashSet<>();
        for (ScholardexCitationView citation : citations) {
            if (!isBlank(citation.getCitedId())) publicationIds.add(citation.getCitedId());
            if (!isBlank(citation.getCitingId())) publicationIds.add(citation.getCitingId());
        }
        Set<String> existingIds = postgresProjectionReadPort.findExistingPublicationIdsByIdIn(publicationIds);
        List<ScholardexCitationView> out = new ArrayList<>();
        for (ScholardexCitationView citation : citations) {
            if (isBlank(citation.getCitedId()) || isBlank(citation.getCitingId())) {
                continue;
            }
            if (!existingIds.contains(citation.getCitedId()) || !existingIds.contains(citation.getCitingId())) {
                continue;
            }
            out.add(citation);
        }
        out.sort(Comparator.comparing(ScholardexCitationView::getCitedId, Comparator.nullsLast(String::compareTo))
                .thenComparing(ScholardexCitationView::getCitingId, Comparator.nullsLast(String::compareTo)));
        return out;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
