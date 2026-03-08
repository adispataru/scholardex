package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.Affiliation;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationSearchView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorSearchView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumSearchView;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationSearchViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorSearchViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumSearchViewRepository;

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
    private final ScopusCitationFactRepository citationFactRepository;
    private final ScopusForumSearchViewRepository forumSearchViewRepository;
    private final ScopusAuthorSearchViewRepository authorSearchViewRepository;
    private final ScopusAffiliationSearchViewRepository affiliationSearchViewRepository;
    private final ScholardexSourceLinkRepository sourceLinkRepository;
    private final ScholardexAuthorFactRepository canonicalAuthorFactRepository;
    private final ScholardexAffiliationFactRepository canonicalAffiliationFactRepository;
    private final ScholardexAuthorAffiliationFactRepository canonicalAuthorAffiliationFactRepository;

    public List<Publication> findAllPublicationsByAuthorsIn(Collection<String> authorIds) {
        List<String> resolvedAuthorIds = resolveCanonicalIds(ScholardexEntityType.AUTHOR, authorIds);
        return dedupeAndSortPublications(publicationViewRepository.findAllByAuthorIdsIn(resolvedAuthorIds)
                .stream()
                .map(this::toPublication)
                .toList());
    }

    public List<Publication> findAllPublicationsByAuthorsContaining(String authorId) {
        return findAllPublicationsByAuthorsIn(List.of(authorId));
    }

    public List<Publication> findAllPublicationsByAffiliationsContaining(String affiliationId) {
        List<String> resolvedAffiliationIds = resolveCanonicalIds(ScholardexEntityType.AFFILIATION, List.of(affiliationId));
        List<Publication> publications = new ArrayList<>();
        for (String canonicalAffiliationId : resolvedAffiliationIds) {
            publicationViewRepository.findAllByAffiliationIdsContaining(canonicalAffiliationId).stream()
                    .map(this::toPublication)
                    .forEach(publications::add);
        }
        return dedupeAndSortPublications(publications);
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
        Map<String, String> idToEid = loadPublicationEidsByIds(citedIds);
        List<String> citedEids = idToEid.values().stream().filter(v -> v != null && !v.isBlank()).toList();
        List<ScopusCitationFact> facts = citationFactRepository.findByCitedEidIn(citedEids);
        return mapCitationFacts(facts);
    }

    public List<Citation> findAllCitationsByCitedId(String citedId) {
        Optional<ScholardexPublicationView> cited = publicationViewRepository.findById(citedId)
                .or(() -> publicationViewRepository.findByEid(citedId));
        if (cited.isEmpty() || cited.get().getEid() == null) {
            return List.of();
        }
        List<ScopusCitationFact> facts = citationFactRepository.findByCitedEid(cited.get().getEid());
        return mapCitationFacts(facts);
    }

    public long countCitationsByCitedId(String citedId) {
        return findAllCitationsByCitedId(citedId).size();
    }

    public List<Forum> findForumsByIdIn(Collection<String> forumIds) {
        return forumSearchViewRepository.findByIdIn(forumIds).stream()
                .map(this::toForum)
                .toList();
    }

    public Optional<Forum> findForumById(String id) {
        return forumSearchViewRepository.findById(id).map(this::toForum);
    }

    public List<Forum> findAllForums() {
        return forumSearchViewRepository.findAll().stream().map(this::toForum).toList();
    }

    public List<Author> findAuthorsByIdIn(Collection<String> authorIds) {
        List<String> resolvedAuthorIds = resolveCanonicalIds(ScholardexEntityType.AUTHOR, authorIds);
        return authorSearchViewRepository.findByIdIn(resolvedAuthorIds).stream()
                .map(this::toAuthor)
                .toList();
    }

    public List<Author> findAllAuthors() {
        return authorSearchViewRepository.findAll().stream()
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
        return authorSearchViewRepository.findByIdIn(authorIds).stream()
                .map(this::toAuthor)
                .toList();
    }

    public Optional<Author> findAuthorById(String id) {
        List<String> resolvedAuthorIds = resolveCanonicalIds(ScholardexEntityType.AUTHOR, List.of(id));
        return authorSearchViewRepository.findByIdIn(resolvedAuthorIds).stream().findFirst().map(this::toAuthor);
    }

    public List<Author> findAuthorsByNameContainsIgnoreCase(String authorName) {
        return authorSearchViewRepository.findAllByNameContainingIgnoreCase(authorName).stream()
                .map(this::toAuthor)
                .toList();
    }

    public List<Affiliation> findAllAffiliations() {
        return affiliationSearchViewRepository.findAll().stream().map(this::toAffiliation).toList();
    }

    public Optional<Affiliation> findAffiliationById(String id) {
        List<String> resolvedAffiliationIds = resolveCanonicalIds(ScholardexEntityType.AFFILIATION, List.of(id));
        return affiliationSearchViewRepository.findByIdIn(resolvedAffiliationIds).stream().findFirst().map(this::toAffiliation);
    }

    public List<Affiliation> findAffiliationsByCountry(String country) {
        return affiliationSearchViewRepository.findAllByCountry(country).stream().map(this::toAffiliation).toList();
    }

    public List<Affiliation> findAffiliationsByNameContains(String name) {
        return affiliationSearchViewRepository.findAllByNameContains(name).stream().map(this::toAffiliation).toList();
    }

    public Optional<ScholardexPublicationView> findPublicationViewById(String id) {
        return publicationViewRepository.findById(id);
    }

    public void savePublicationView(ScholardexPublicationView view) {
        publicationViewRepository.save(view);
    }

    public Forum saveForum(Forum forum) {
        ScopusForumSearchView row = forumSearchViewRepository.findById(forum.getId()).orElseGet(ScopusForumSearchView::new);
        row.setId(forum.getId());
        row.setPublicationName(forum.getPublicationName());
        row.setIssn(forum.getIssn());
        row.setEIssn(forum.getEIssn());
        row.setAggregationType(forum.getAggregationType());
        if (row.getBuildVersion() == null) {
            row.setBuildVersion("manual-override");
        }
        java.time.Instant now = java.time.Instant.now();
        if (row.getBuildAt() == null) {
            row.setBuildAt(now);
        }
        row.setUpdatedAt(now);
        ScopusForumSearchView saved = forumSearchViewRepository.save(row);
        return toForum(saved);
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
            upsertSourceLink(ScholardexEntityType.AUTHOR, "MANUAL_AUTHOR_EDIT", sourceRecordId, canonicalId, "manual-author-save", now);
        }

        for (String affiliationId : affiliationIds) {
            ScholardexAuthorAffiliationFact edge = canonicalAuthorAffiliationFactRepository
                    .findByAuthorIdAndAffiliationIdAndSource(canonicalId, affiliationId, "MANUAL_AUTHOR_EDIT")
                    .orElseGet(ScholardexAuthorAffiliationFact::new);
            if (edge.getCreatedAt() == null) {
                edge.setCreatedAt(now);
            }
            edge.setAuthorId(canonicalId);
            edge.setAffiliationId(affiliationId);
            edge.setSource("MANUAL_AUTHOR_EDIT");
            edge.setSourceRecordId(canonicalId + "::affiliation::" + affiliationId);
            edge.setLinkState("LINKED");
            edge.setLinkReason("manual-author-save");
            edge.setUpdatedAt(now);
            canonicalAuthorAffiliationFactRepository.save(edge);
        }

        ScopusAuthorSearchView row = authorSearchViewRepository.findById(canonicalId).orElseGet(ScopusAuthorSearchView::new);
        row.setId(canonicalId);
        row.setName(author.getName());
        row.setAffiliationIds(new ArrayList<>(affiliationIds));
        if (row.getBuildVersion() == null) {
            row.setBuildVersion("manual-override");
        }
        if (row.getBuildAt() == null) {
            row.setBuildAt(now);
        }
        row.setUpdatedAt(now);
        ScopusAuthorSearchView saved = authorSearchViewRepository.save(row);
        return toAuthor(saved);
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
            upsertSourceLink(ScholardexEntityType.AFFILIATION, "MANUAL_AFFILIATION_EDIT", sourceRecordId, canonicalId, "manual-affiliation-save", now);
        }

        ScopusAffiliationSearchView row = affiliationSearchViewRepository.findById(canonicalId)
                .orElseGet(ScopusAffiliationSearchView::new);
        row.setId(canonicalId);
        row.setName(affiliation.getName());
        row.setCity(affiliation.getCity());
        row.setCountry(affiliation.getCountry());
        if (row.getBuildVersion() == null) {
            row.setBuildVersion("manual-override");
        }
        if (row.getBuildAt() == null) {
            row.setBuildAt(now);
        }
        row.setUpdatedAt(now);
        ScopusAffiliationSearchView saved = affiliationSearchViewRepository.save(row);
        return toAffiliation(saved);
    }

    private Optional<String> resolveCanonicalId(ScholardexEntityType entityType, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Optional.empty();
        }
        List<ScholardexSourceLink> mapped = sourceLinkRepository.findByEntityTypeAndSourceRecordId(entityType, candidate);
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
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (String id : candidateIds) {
            String normalized = normalizeBlank(id);
            if (normalized == null) {
                continue;
            }
            resolved.add(normalized);
            List<ScholardexSourceLink> mapped = sourceLinkRepository.findByEntityTypeAndSourceRecordId(entityType, normalized);
            if (mapped == null || mapped.isEmpty()) {
                continue;
            }
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
            String reason,
            java.time.Instant now
    ) {
        ScholardexSourceLink link = sourceLinkRepository
                .findByEntityTypeAndSourceAndSourceRecordId(entityType, source, sourceRecordId)
                .orElseGet(ScholardexSourceLink::new);
        link.setEntityType(entityType);
        link.setSource(source);
        link.setSourceRecordId(sourceRecordId);
        link.setCanonicalEntityId(canonicalId);
        link.setLinkState("LINKED");
        link.setLinkReason(reason);
        if (link.getLinkedAt() == null) {
            link.setLinkedAt(now);
        }
        link.setUpdatedAt(now);
        sourceLinkRepository.save(link);
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

    private Map<String, String> loadPublicationEidsByIds(Collection<String> publicationIds) {
        Map<String, String> out = new HashMap<>();
        if (publicationIds == null || publicationIds.isEmpty()) {
            return out;
        }
        for (ScholardexPublicationView publication : publicationViewRepository.findAllByIdIn(publicationIds)) {
            out.put(publication.getId(), publication.getEid());
        }
        return out;
    }

    private List<Citation> mapCitationFacts(List<ScopusCitationFact> facts) {
        if (facts.isEmpty()) {
            return List.of();
        }
        Set<String> eids = new LinkedHashSet<>();
        for (ScopusCitationFact fact : facts) {
            if (fact.getCitedEid() != null) {
                eids.add(fact.getCitedEid());
            }
            if (fact.getCitingEid() != null) {
                eids.add(fact.getCitingEid());
            }
        }
        Map<String, ScholardexPublicationView> byEid = publicationViewRepository.findAllByEidIn(eids).stream()
                .filter(row -> row.getEid() != null)
                .collect(Collectors.toMap(ScholardexPublicationView::getEid, row -> row, (a, b) -> a));
        List<Citation> out = new ArrayList<>();
        for (ScopusCitationFact fact : facts) {
            ScholardexPublicationView cited = byEid.get(fact.getCitedEid());
            ScholardexPublicationView citing = byEid.get(fact.getCitingEid());
            if (cited == null || citing == null) {
                continue;
            }
            Citation citation = new Citation();
            citation.setId(fact.getId());
            citation.setCitedId(cited.getId());
            citation.setCitingId(citing.getId());
            out.add(citation);
        }
        out.sort(Comparator.comparing(Citation::getCitedId, Comparator.nullsLast(String::compareTo))
                .thenComparing(Citation::getCitingId, Comparator.nullsLast(String::compareTo)));
        return out;
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

    private Forum toForum(ScopusForumSearchView row) {
        Forum forum = new Forum();
        forum.setId(row.getId());
        forum.setPublicationName(row.getPublicationName());
        forum.setIssn(row.getIssn());
        forum.setEIssn(row.getEIssn());
        forum.setAggregationType(row.getAggregationType());
        return forum;
    }

    private Author toAuthor(ScopusAuthorSearchView row) {
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

    private Affiliation toAffiliation(ScopusAffiliationSearchView row) {
        Affiliation affiliation = new Affiliation();
        affiliation.setAfid(row.getId());
        affiliation.setName(row.getName());
        affiliation.setCity(row.getCity());
        affiliation.setCountry(row.getCountry());
        return affiliation;
    }
}
