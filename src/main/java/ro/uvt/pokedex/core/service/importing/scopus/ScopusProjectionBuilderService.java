package ro.uvt.pokedex.core.service.importing.scopus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationSearchView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorSearchView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumSearchView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationSearchViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorSearchViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumSearchViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ScopusProjectionBuilderService {

    private static final Logger log = LoggerFactory.getLogger(ScopusProjectionBuilderService.class);

    private final ScopusForumFactRepository forumFactRepository;
    private final ScopusAuthorFactRepository authorFactRepository;
    private final ScopusAffiliationFactRepository affiliationFactRepository;
    private final ScopusPublicationFactRepository publicationFactRepository;
    private final ScopusCitationFactRepository citationFactRepository;
    private final ScopusForumSearchViewRepository forumSearchViewRepository;
    private final ScopusAuthorSearchViewRepository authorSearchViewRepository;
    private final ScopusAffiliationSearchViewRepository affiliationSearchViewRepository;
    private final ScholardexPublicationViewRepository publicationViewRepository;

    public ScopusProjectionBuilderService(
            ScopusForumFactRepository forumFactRepository,
            ScopusAuthorFactRepository authorFactRepository,
            ScopusAffiliationFactRepository affiliationFactRepository,
            ScopusPublicationFactRepository publicationFactRepository,
            ScopusCitationFactRepository citationFactRepository,
            ScopusForumSearchViewRepository forumSearchViewRepository,
            ScopusAuthorSearchViewRepository authorSearchViewRepository,
            ScopusAffiliationSearchViewRepository affiliationSearchViewRepository,
            ScholardexPublicationViewRepository publicationViewRepository
    ) {
        this.forumFactRepository = forumFactRepository;
        this.authorFactRepository = authorFactRepository;
        this.affiliationFactRepository = affiliationFactRepository;
        this.publicationFactRepository = publicationFactRepository;
        this.citationFactRepository = citationFactRepository;
        this.forumSearchViewRepository = forumSearchViewRepository;
        this.authorSearchViewRepository = authorSearchViewRepository;
        this.affiliationSearchViewRepository = affiliationSearchViewRepository;
        this.publicationViewRepository = publicationViewRepository;
    }

    public ImportProcessingResult rebuildViews() {
        ImportProcessingResult result = new ImportProcessingResult(20);
        Instant buildAt = Instant.now();
        String buildVersion = buildAt.toString();
        try {
            List<ScopusForumFact> forumFacts = new ArrayList<>(forumFactRepository.findAll());
            forumFacts.sort(Comparator.comparing(ScopusForumFact::getSourceId, Comparator.nullsLast(String::compareTo)));
            List<ScopusForumSearchView> forumViews = forumFacts.stream()
                    .map(fact -> toForumView(fact, buildVersion, buildAt))
                    .toList();
            forumSearchViewRepository.deleteAll();
            forumSearchViewRepository.saveAll(forumViews);
            markImported(result, forumViews.size());

            List<ScopusAuthorFact> authorFacts = new ArrayList<>(authorFactRepository.findAll());
            authorFacts.sort(Comparator.comparing(ScopusAuthorFact::getAuthorId, Comparator.nullsLast(String::compareTo)));
            List<ScopusAuthorSearchView> authorViews = authorFacts.stream()
                    .map(fact -> toAuthorView(fact, buildVersion, buildAt))
                    .toList();
            authorSearchViewRepository.deleteAll();
            authorSearchViewRepository.saveAll(authorViews);
            markImported(result, authorViews.size());

            List<ScopusAffiliationFact> affiliationFacts = new ArrayList<>(affiliationFactRepository.findAll());
            affiliationFacts.sort(Comparator.comparing(ScopusAffiliationFact::getAfid, Comparator.nullsLast(String::compareTo)));
            List<ScopusAffiliationSearchView> affiliationViews = affiliationFacts.stream()
                    .map(fact -> toAffiliationView(fact, buildVersion, buildAt))
                    .toList();
            affiliationSearchViewRepository.deleteAll();
            affiliationSearchViewRepository.saveAll(affiliationViews);
            markImported(result, affiliationViews.size());

            List<ScopusPublicationFact> publicationFacts = new ArrayList<>(publicationFactRepository.findAll());
            publicationFacts.sort(Comparator.comparing(ScopusPublicationFact::getEid, Comparator.nullsLast(String::compareTo)));
            Map<String, List<String>> citingByCited = buildCitingMap();
            Map<String, ScholardexPublicationView> previousByEid = buildPreviousByEid();
            List<ScholardexPublicationView> publicationViews = new ArrayList<>(publicationFacts.size());
            for (ScopusPublicationFact fact : publicationFacts) {
                publicationViews.add(toPublicationView(fact, previousByEid.get(fact.getEid()), citingByCited, buildVersion, buildAt));
            }
            publicationViewRepository.deleteAll();
            publicationViewRepository.saveAll(publicationViews);
            markImported(result, publicationViews.size());

            log.info("Scopus projection rebuild complete: buildVersion={}, forums={}, authors={}, affiliations={}, publications={}",
                    buildVersion, forumViews.size(), authorViews.size(), affiliationViews.size(), publicationViews.size());
        } catch (Exception e) {
            result.markError("scopus-projection-rebuild-error=" + e.getMessage());
            log.error("Scopus projection rebuild failed", e);
        }
        return result;
    }

    private ScopusForumSearchView toForumView(ScopusForumFact fact, String buildVersion, Instant buildAt) {
        ScopusForumSearchView view = new ScopusForumSearchView();
        view.setId(fact.getSourceId());
        view.setPublicationName(fact.getPublicationName());
        view.setIssn(fact.getIssn());
        view.setEIssn(fact.getEIssn());
        view.setAggregationType(fact.getAggregationType());
        view.setBuildVersion(buildVersion);
        view.setBuildAt(buildAt);
        view.setUpdatedAt(buildAt);
        view.setSourceEventId(fact.getSourceEventId());
        return view;
    }

    private ScopusAuthorSearchView toAuthorView(ScopusAuthorFact fact, String buildVersion, Instant buildAt) {
        ScopusAuthorSearchView view = new ScopusAuthorSearchView();
        view.setId(fact.getAuthorId());
        view.setName(fact.getName());
        view.setAffiliationIds(fact.getAffiliationIds() == null ? List.of() : new ArrayList<>(fact.getAffiliationIds()));
        view.setBuildVersion(buildVersion);
        view.setBuildAt(buildAt);
        view.setUpdatedAt(buildAt);
        view.setSourceEventId(fact.getSourceEventId());
        return view;
    }

    private ScopusAffiliationSearchView toAffiliationView(ScopusAffiliationFact fact, String buildVersion, Instant buildAt) {
        ScopusAffiliationSearchView view = new ScopusAffiliationSearchView();
        view.setId(fact.getAfid());
        view.setName(fact.getName());
        view.setCity(fact.getCity());
        view.setCountry(fact.getCountry());
        view.setBuildVersion(buildVersion);
        view.setBuildAt(buildAt);
        view.setUpdatedAt(buildAt);
        view.setSourceEventId(fact.getSourceEventId());
        return view;
    }

    private ScholardexPublicationView toPublicationView(
            ScopusPublicationFact fact,
            ScholardexPublicationView previous,
            Map<String, List<String>> citingByCited,
            String buildVersion,
            Instant buildAt
    ) {
        ScholardexPublicationView view = new ScholardexPublicationView();
        view.setId(fact.getId());
        view.setDoi(fact.getDoi());
        view.setEid(fact.getEid());
        view.setTitle(fact.getTitle());
        view.setSubtype(fact.getSubtype());
        view.setSubtypeDescription(fact.getSubtypeDescription());
        view.setScopusSubtype(fact.getScopusSubtype());
        view.setScopusSubtypeDescription(fact.getScopusSubtypeDescription());
        view.setCreator(fact.getCreator());
        view.setCoverDate(fact.getCoverDate());
        view.setCoverDisplayDate(fact.getCoverDisplayDate());
        view.setVolume(fact.getVolume());
        view.setIssueIdentifier(fact.getIssueIdentifier());
        view.setDescription(fact.getDescription());
        view.setAuthorCount(fact.getAuthorCount());
        view.setCorrespondingAuthors(fact.getCorrespondingAuthors() == null ? List.of() : new ArrayList<>(fact.getCorrespondingAuthors()));
        view.setOpenAccess(Boolean.TRUE.equals(fact.getOpenAccess()));
        view.setFreetoread(fact.getFreetoread());
        view.setFreetoreadLabel(fact.getFreetoreadLabel());
        view.setFundingId(fact.getFundingId());
        view.setArticleNumber(fact.getArticleNumber());
        view.setPageRange(fact.getPageRange());
        view.setApproved(Boolean.TRUE.equals(fact.getApproved()));
        view.setAuthorIds(fact.getAuthors() == null ? List.of() : new ArrayList<>(fact.getAuthors()));
        view.setAffiliationIds(fact.getAffiliations() == null ? List.of() : new ArrayList<>(fact.getAffiliations()));
        view.setForumId(fact.getForumId());
        List<String> citingEids = citingByCited.getOrDefault(fact.getEid(), List.of());
        view.setCitingPublicationIds(new ArrayList<>(citingEids));
        view.setCitedByCount(fact.getCitedByCount() == null ? citingEids.size() : fact.getCitedByCount());
        // Preserve enrichment-owned fields and lineage across Scopus projection rebuilds.
        view.setWosId(previous == null ? null : previous.getWosId());
        view.setGoogleScholarId(previous == null ? null : previous.getGoogleScholarId());
        view.setBuildVersion(buildVersion);
        view.setBuildAt(buildAt);
        view.setUpdatedAt(buildAt);
        view.setScopusLineage(fact.getSourceEventId());
        view.setWosLineage(previous == null ? null : previous.getWosLineage());
        view.setScholarLineage(previous == null ? null : previous.getScholarLineage());
        view.setLinkerVersion(previous == null ? null : previous.getLinkerVersion());
        view.setLinkerRunId(previous == null ? null : previous.getLinkerRunId());
        view.setLinkedAt(previous == null ? null : previous.getLinkedAt());
        return view;
    }

    private Map<String, List<String>> buildCitingMap() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<ScopusCitationFact> facts = new ArrayList<>(citationFactRepository.findAll());
        facts.sort(Comparator
                .comparing(ScopusCitationFact::getCitedEid, Comparator.nullsLast(String::compareTo))
                .thenComparing(ScopusCitationFact::getCitingEid, Comparator.nullsLast(String::compareTo)));
        for (ScopusCitationFact fact : facts) {
            if (fact.getCitedEid() == null || fact.getCitingEid() == null) {
                continue;
            }
            out.computeIfAbsent(fact.getCitedEid(), key -> new ArrayList<>());
            List<String> values = out.get(fact.getCitedEid());
            if (!values.contains(fact.getCitingEid())) {
                values.add(fact.getCitingEid());
            }
        }
        return out;
    }

    private Map<String, ScholardexPublicationView> buildPreviousByEid() {
        Map<String, ScholardexPublicationView> out = new HashMap<>();
        for (ScholardexPublicationView row : publicationViewRepository.findAll()) {
            if (row.getEid() != null && !row.getEid().isBlank()) {
                out.put(row.getEid(), row);
            }
        }
        return out;
    }

    private void markImported(ImportProcessingResult result, int count) {
        for (int i = 0; i < count; i++) {
            result.markProcessed();
            result.markImported();
        }
    }
}
