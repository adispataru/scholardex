package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.Affiliation;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
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
public class ScopusProjectionReadService {

    private final ScholardexPublicationViewRepository publicationViewRepository;
    private final ScholardexCitationFactRepository citationFactRepository;
    private final ScholardexForumViewRepository forumViewRepository;
    private final ScholardexAuthorViewRepository authorViewRepository;
    private final ScholardexAffiliationViewRepository affiliationViewRepository;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexAuthorFactRepository canonicalAuthorFactRepository;
    private final ScholardexAffiliationFactRepository canonicalAffiliationFactRepository;
    private final ScholardexForumFactRepository canonicalForumFactRepository;
    private final ScholardexAuthorAffiliationFactRepository canonicalAuthorAffiliationFactRepository;
    private final ScholardexAuthorshipFactRepository canonicalAuthorshipFactRepository;
    private final ScholardexEdgeWriterService edgeWriterService;

    public List<Publication> findAllPublicationsByAuthorsIn(Collection<String> authorIds) {
        List<String> resolvedAuthorIds = resolveCanonicalIds(ScholardexEntityType.AUTHOR, authorIds);
        if (resolvedAuthorIds.isEmpty()) {
            return List.of();
        }
        Set<String> publicationIds = canonicalAuthorshipFactRepository.findByAuthorIdIn(resolvedAuthorIds).stream()
                .map(edge -> normalizeBlank(edge.getPublicationId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (publicationIds.isEmpty()) {
            return List.of();
        }
        return dedupeAndSortPublications(publicationViewRepository.findAllByIdIn(publicationIds)
                .stream()
                .map(this::toPublication)
                .toList());
    }

    public List<Publication> findAllPublicationsByAuthorsContaining(String authorId) {
        return findAllPublicationsByAuthorsIn(List.of(authorId));
    }

    public List<Publication> findAllPublicationsByAffiliationsContaining(String affiliationId) {
        List<String> resolvedAffiliationIds = resolveCanonicalIds(ScholardexEntityType.AFFILIATION, List.of(affiliationId));
        if (resolvedAffiliationIds.isEmpty()) {
            return List.of();
        }
        Set<String> authorIds = new LinkedHashSet<>();
        for (String canonicalAffiliationId : resolvedAffiliationIds) {
            canonicalAuthorAffiliationFactRepository.findByAffiliationId(canonicalAffiliationId)
                    .forEach(edge -> {
                        String authorId = normalizeBlank(edge.getAuthorId());
                        if (authorId != null) {
                            authorIds.add(authorId);
                        }
                    });
        }
        if (authorIds.isEmpty()) {
            return List.of();
        }
        Set<String> publicationIds = canonicalAuthorshipFactRepository.findByAuthorIdIn(authorIds).stream()
                .map(edge -> normalizeBlank(edge.getPublicationId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (publicationIds.isEmpty()) {
            return List.of();
        }
        return dedupeAndSortPublications(publicationViewRepository.findAllByIdIn(publicationIds)
                .stream()
                .map(this::toPublication)
                .toList());
    }

    public List<Publication> findAllPublicationsByIdIn(Collection<String> ids) {
        Map<String, Publication> out = new LinkedHashMap<>();
        publicationViewRepository.findAllByIdIn(ids).forEach(row -> out.putIfAbsent(row.getId(), toPublication(row)));
        if (!ids.isEmpty()) {
            publicationViewRepository.findAllByEidIn(ids).forEach(row -> out.putIfAbsent(row.getId(), toPublication(row)));
        }
        List<Publication> publications = new ArrayList<>(out.values());
        PublicationOrderingSupport.sortPublicationsInPlace(publications);
        return publications;
    }

    public Optional<Publication> findPublicationById(String id) {
        return publicationViewRepository.findById(id).map(this::toPublication);
    }

    public Optional<Publication> findPublicationByEid(String eid) {
        return publicationViewRepository.findByEid(eid).map(this::toPublication);
    }

    public Optional<Publication> findPublicationByAnyId(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return publicationViewRepository.findById(key)
                .or(() -> publicationViewRepository.findByEid(key))
                .or(() -> publicationViewRepository.findByWosId(key))
                .or(() -> publicationViewRepository.findByGoogleScholarId(key))
                .map(this::toPublication);
    }

    public List<Publication> findPublicationsByTitleContainingIgnoreCaseOrderByCoverDateDesc(String title) {
        List<Publication> out = publicationViewRepository.findByTitleContainingIgnoreCaseOrderByCoverDateDesc(title)
                .stream()
                .map(this::toPublication)
                .collect(Collectors.toCollection(ArrayList::new));
        PublicationOrderingSupport.sortPublicationsInPlace(out);
        return out;
    }

    public List<Citation> findAllCitationsByCitedIdIn(Collection<String> citedIds) {
        List<String> publicationIds = resolvePublicationIdsByAnyKeys(citedIds);
        List<ScholardexCitationFact> facts = citationFactRepository.findByCitedPublicationIdIn(publicationIds);
        return mapCitationFacts(facts);
    }

    public List<Citation> findAllCitationsByCitedId(String citedId) {
        Optional<Publication> cited = findPublicationByAnyId(citedId);
        if (cited.isEmpty() || cited.get().getId() == null) {
            return List.of();
        }
        List<ScholardexCitationFact> facts = citationFactRepository.findByCitedPublicationId(cited.get().getId());
        return mapCitationFacts(facts);
    }

    public long countCitationsByCitedId(String citedId) {
        return findAllCitationsByCitedId(citedId).size();
    }

    public List<Forum> findForumsByIdIn(Collection<String> forumIds) {
        List<String> resolvedForumIds = resolveCanonicalIds(ScholardexEntityType.FORUM, forumIds);
        return forumViewRepository.findByIdIn(resolvedForumIds).stream()
                .map(this::toForum)
                .toList();
    }

    public Optional<Forum> findForumById(String id) {
        List<String> resolvedForumIds = resolveCanonicalIds(ScholardexEntityType.FORUM, List.of(id));
        return forumViewRepository.findByIdIn(resolvedForumIds).stream().findFirst().map(this::toForum);
    }

    public List<Forum> findAllForums() {
        return forumViewRepository.findAll().stream().map(this::toForum).toList();
    }

    public List<Author> findAuthorsByIdIn(Collection<String> authorIds) {
        List<String> resolvedAuthorIds = resolveCanonicalIds(ScholardexEntityType.AUTHOR, authorIds);
        return authorViewRepository.findByIdIn(resolvedAuthorIds).stream()
                .map(this::toAuthor)
                .toList();
    }

    public List<Author> findAllAuthors() {
        return authorViewRepository.findAll().stream()
                .map(this::toAuthor)
                .toList();
    }

    public List<Author> findAuthorsByAffiliationId(String affiliationId) {
        List<String> resolvedAffiliationIds = resolveCanonicalIds(ScholardexEntityType.AFFILIATION, List.of(affiliationId));
        Set<String> authorIds = new LinkedHashSet<>();
        for (String canonicalAffiliationId : resolvedAffiliationIds) {
            canonicalAuthorAffiliationFactRepository.findByAffiliationId(canonicalAffiliationId)
                    .forEach(edge -> authorIds.add(edge.getAuthorId()));
        }
        return authorViewRepository.findByIdIn(authorIds).stream()
                .map(this::toAuthor)
                .toList();
    }

    public Optional<Author> findAuthorById(String id) {
        List<String> resolvedAuthorIds = resolveCanonicalIds(ScholardexEntityType.AUTHOR, List.of(id));
        return authorViewRepository.findByIdIn(resolvedAuthorIds).stream().findFirst().map(this::toAuthor);
    }

    public List<Author> findAuthorsByNameContainsIgnoreCase(String authorName) {
        return authorViewRepository.findAllByNameContainingIgnoreCase(authorName).stream()
                .map(this::toAuthor)
                .toList();
    }

    public List<Affiliation> findAllAffiliations() {
        return affiliationViewRepository.findAll().stream().map(this::toAffiliation).toList();
    }

    public Optional<Affiliation> findAffiliationById(String id) {
        List<String> resolvedAffiliationIds = resolveCanonicalIds(ScholardexEntityType.AFFILIATION, List.of(id));
        return affiliationViewRepository.findByIdIn(resolvedAffiliationIds).stream().findFirst().map(this::toAffiliation);
    }

    public List<Affiliation> findAffiliationsByCountry(String country) {
        return affiliationViewRepository.findAllByCountry(country).stream().map(this::toAffiliation).toList();
    }

    public List<Affiliation> findAffiliationsByNameContains(String name) {
        return affiliationViewRepository.findAllByNameContains(name).stream().map(this::toAffiliation).toList();
    }

    public Optional<ScholardexPublicationView> findPublicationViewById(String id) {
        return publicationViewRepository.findById(id);
    }

    public void savePublicationView(ScholardexPublicationView view) {
        publicationViewRepository.save(view);
    }

    public Forum saveForum(Forum forum) {
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

        Forum out = new Forum();
        out.setId(canonicalId);
        out.setPublicationName(forum.getPublicationName());
        out.setIssn(forum.getIssn());
        out.setEIssn(forum.getEIssn());
        out.setAggregationType(forum.getAggregationType());
        return out;
    }

    public Author saveAuthor(Author author) {
        String sourceRecordId = normalizeBlank(author.getId());
        String canonicalId = resolveCanonicalId(ScholardexEntityType.AUTHOR, sourceRecordId)
                .orElse(sourceRecordId == null ? "sauth_manual_" + Integer.toHexString(Objects.hash(author.getName())) : sourceRecordId);
        List<String> affiliationSourceIds = author.getAffiliations() == null
                ? List.of()
                : author.getAffiliations().stream().map(Affiliation::getAfid).filter(Objects::nonNull).toList();
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

        Author out = new Author();
        out.setId(canonicalId);
        out.setName(author.getName());
        out.setAffiliations(affiliationIds.stream().map(id -> {
            Affiliation affiliation = new Affiliation();
            affiliation.setAfid(id);
            return affiliation;
        }).toList());
        return out;
    }

    public Affiliation saveAffiliation(Affiliation affiliation) {
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

        Affiliation out = new Affiliation();
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

    private List<Publication> dedupeAndSortPublications(List<Publication> publications) {
        Map<String, Publication> byId = new LinkedHashMap<>();
        for (Publication publication : publications) {
            byId.putIfAbsent(publication.getId(), publication);
        }
        List<Publication> out = new ArrayList<>(byId.values());
        PublicationOrderingSupport.sortPublicationsInPlace(out);
        return out;
    }

    private List<String> resolvePublicationIdsByAnyKeys(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> publicationIds = new LinkedHashSet<>();
        for (String key : keys) {
            findPublicationByAnyId(key).map(Publication::getId).ifPresent(publicationIds::add);
        }
        return new ArrayList<>(publicationIds);
    }

    private List<Citation> mapCitationFacts(List<ScholardexCitationFact> facts) {
        if (facts.isEmpty()) {
            return List.of();
        }
        Set<String> publicationIds = new LinkedHashSet<>();
        for (ScholardexCitationFact fact : facts) {
            if (!isBlank(fact.getCitedPublicationId())) {
                publicationIds.add(fact.getCitedPublicationId());
            }
            if (!isBlank(fact.getCitingPublicationId())) {
                publicationIds.add(fact.getCitingPublicationId());
            }
        }
        Set<String> existingIds = publicationViewRepository.findAllByIdIn(publicationIds).stream()
                .map(ScholardexPublicationView::getId)
                .collect(Collectors.toSet());
        List<Citation> out = new ArrayList<>();
        for (ScholardexCitationFact fact : facts) {
            if (isBlank(fact.getCitedPublicationId()) || isBlank(fact.getCitingPublicationId())) {
                continue;
            }
            if (!existingIds.contains(fact.getCitedPublicationId())
                    || !existingIds.contains(fact.getCitingPublicationId())) {
                continue;
            }
            Citation citation = new Citation();
            citation.setId(fact.getId());
            citation.setCitedId(fact.getCitedPublicationId());
            citation.setCitingId(fact.getCitingPublicationId());
            out.add(citation);
        }
        out.sort(Comparator.comparing(Citation::getCitedId, Comparator.nullsLast(String::compareTo))
                .thenComparing(Citation::getCitingId, Comparator.nullsLast(String::compareTo)));
        return out;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private Publication toPublication(ScholardexPublicationView row) {
        Publication publication = new Publication();
        publication.setId(row.getId());
        publication.setDoi(row.getDoi());
        publication.setEid(row.getEid());
        publication.setWosId(row.getWosId());
        publication.setTitle(row.getTitle());
        publication.setSubtype(row.getSubtype());
        publication.setScopusSubtype(row.getScopusSubtype());
        publication.setSubtypeDescription(row.getSubtypeDescription());
        publication.setScopusSubtypeDescription(row.getScopusSubtypeDescription());
        publication.setCreator(row.getCreator());
        publication.setAffiliations(row.getAffiliationIds() == null ? List.of() : new ArrayList<>(row.getAffiliationIds()));
        publication.setAuthorCount(row.getAuthorCount() == null ? 0 : row.getAuthorCount());
        publication.setAuthors(row.getAuthorIds() == null ? List.of() : new ArrayList<>(row.getAuthorIds()));
        publication.setCorrespondingAuthors(row.getCorrespondingAuthors() == null ? List.of() : new ArrayList<>(row.getCorrespondingAuthors()));
        publication.setForum(row.getForumId());
        publication.setVolume(row.getVolume());
        publication.setIssueIdentifier(row.getIssueIdentifier());
        publication.setCoverDate(row.getCoverDate());
        publication.setCoverDisplayDate(row.getCoverDisplayDate());
        publication.setDescription(row.getDescription());
        publication.setCitedbyCount(row.getCitedByCount() == null ? 0 : row.getCitedByCount());
        publication.setOpenAccess(row.isOpenAccess());
        publication.setFreetoread(row.getFreetoread());
        publication.setFreetoreadLabel(row.getFreetoreadLabel());
        publication.setFundingId(row.getFundingId());
        publication.setArticleNumber(row.getArticleNumber());
        publication.setPageRange(row.getPageRange());
        publication.setApproved(row.isApproved());
        if (row.getCitingPublicationIds() != null) {
            publication.setCitedBy(new LinkedHashSet<>(row.getCitingPublicationIds()));
        }
        return publication;
    }

    private Forum toForum(ScholardexForumView row) {
        Forum forum = new Forum();
        forum.setId(row.getId());
        forum.setPublicationName(row.getPublicationName());
        forum.setIssn(row.getIssn());
        forum.setEIssn(row.getEIssn());
        forum.setAggregationType(row.getAggregationType());
        return forum;
    }

    private Author toAuthor(ScholardexAuthorView row) {
        Author author = new Author();
        author.setId(row.getId());
        author.setName(row.getName());
        List<Affiliation> affiliations = row.getAffiliationIds() == null
                ? List.of()
                : row.getAffiliationIds().stream()
                .map(affiliationId -> {
                    Affiliation affiliation = new Affiliation();
                    affiliation.setAfid(affiliationId);
                    return affiliation;
                })
                .toList();
        author.setAffiliations(affiliations);
        return author;
    }

    private Affiliation toAffiliation(ScholardexAffiliationView row) {
        Affiliation affiliation = new Affiliation();
        affiliation.setAfid(row.getId());
        affiliation.setName(row.getName());
        affiliation.setCity(row.getCity());
        affiliation.setCountry(row.getCountry());
        return affiliation;
    }
}
