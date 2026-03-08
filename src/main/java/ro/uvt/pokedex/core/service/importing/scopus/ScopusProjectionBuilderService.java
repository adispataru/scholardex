package ro.uvt.pokedex.core.service.importing.scopus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationSearchView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorSearchView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumSearchView;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationSearchViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorSearchViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumSearchViewRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class ScopusProjectionBuilderService {

    private static final Logger log = LoggerFactory.getLogger(ScopusProjectionBuilderService.class);
    private static final Pattern DOI_URL_PREFIX = Pattern.compile("^https?://(dx\\.)?doi\\.org/", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOI_PREFIX = Pattern.compile("^doi:", Pattern.CASE_INSENSITIVE);

    private final ScopusForumFactRepository forumFactRepository;
    private final ScholardexAuthorFactRepository authorFactRepository;
    private final ScholardexAffiliationFactRepository affiliationFactRepository;
    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final ScopusCitationFactRepository citationFactRepository;
    private final ScopusForumSearchViewRepository forumSearchViewRepository;
    private final ScopusAuthorSearchViewRepository authorSearchViewRepository;
    private final ScopusAffiliationSearchViewRepository affiliationSearchViewRepository;
    private final ScholardexPublicationViewRepository publicationViewRepository;

    public ScopusProjectionBuilderService(
            ScopusForumFactRepository forumFactRepository,
            ScholardexAuthorFactRepository authorFactRepository,
            ScholardexAffiliationFactRepository affiliationFactRepository,
            ScholardexPublicationFactRepository publicationFactRepository,
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

            List<ScholardexAuthorFact> authorFacts = new ArrayList<>(authorFactRepository.findAll());
            authorFacts.sort(Comparator.comparing(ScholardexAuthorFact::getId, Comparator.nullsLast(String::compareTo)));
            List<ScopusAuthorSearchView> authorViews = authorFacts.stream()
                    .map(fact -> toAuthorView(fact, buildVersion, buildAt))
                    .toList();
            authorSearchViewRepository.deleteAll();
            authorSearchViewRepository.saveAll(authorViews);
            markImported(result, authorViews.size());

            List<ScholardexAffiliationFact> affiliationFacts = new ArrayList<>(affiliationFactRepository.findAll());
            affiliationFacts.sort(Comparator.comparing(ScholardexAffiliationFact::getId, Comparator.nullsLast(String::compareTo)));
            List<ScopusAffiliationSearchView> affiliationViews = affiliationFacts.stream()
                    .map(fact -> toAffiliationView(fact, buildVersion, buildAt))
                    .toList();
            affiliationSearchViewRepository.deleteAll();
            affiliationSearchViewRepository.saveAll(affiliationViews);
            markImported(result, affiliationViews.size());

            List<ScholardexPublicationFact> publicationFacts = new ArrayList<>(publicationFactRepository.findAll());
            publicationFacts.sort(Comparator.comparing(ScholardexPublicationFact::getEid, Comparator.nullsLast(String::compareTo)));
            Map<String, List<String>> citingByCited = buildCitingMap();
            List<ScholardexPublicationView> publicationViews = new ArrayList<>(publicationFacts.size());
            for (ScholardexPublicationFact fact : publicationFacts) {
                publicationViews.add(toPublicationView(fact, citingByCited, buildVersion, buildAt));
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

    private ScopusAuthorSearchView toAuthorView(ScholardexAuthorFact fact, String buildVersion, Instant buildAt) {
        ScopusAuthorSearchView view = new ScopusAuthorSearchView();
        view.setId(fact.getId());
        view.setName(fact.getDisplayName());
        view.setAffiliationIds(fact.getAffiliationIds() == null ? List.of() : new ArrayList<>(fact.getAffiliationIds()));
        view.setBuildVersion(buildVersion);
        view.setBuildAt(buildAt);
        view.setUpdatedAt(buildAt);
        view.setSourceEventId(fact.getSourceEventId());
        return view;
    }

    private ScopusAffiliationSearchView toAffiliationView(ScholardexAffiliationFact fact, String buildVersion, Instant buildAt) {
        ScopusAffiliationSearchView view = new ScopusAffiliationSearchView();
        view.setId(fact.getId());
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
            ScholardexPublicationFact fact,
            Map<String, List<String>> citingByCited,
            String buildVersion,
            Instant buildAt
    ) {
        ScholardexPublicationView view = new ScholardexPublicationView();
        view.setId(fact.getId());
        view.setDoi(fact.getDoi());
        view.setDoiNormalized(normalizeDoi(fact.getDoi()));
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
        view.setAuthorIds(fact.getAuthorIds() == null ? List.of() : new ArrayList<>(fact.getAuthorIds()));
        view.setAffiliationIds(fact.getAffiliationIds() == null ? List.of() : new ArrayList<>(fact.getAffiliationIds()));
        view.setForumId(fact.getForumId());
        List<String> citingEids = citingByCited.getOrDefault(fact.getEid(), List.of());
        view.setCitingPublicationIds(new ArrayList<>(citingEids));
        view.setCitedByCount(fact.getCitedByCount() == null ? citingEids.size() : fact.getCitedByCount());
        view.setWosId(fact.getWosId());
        view.setGoogleScholarId(fact.getGoogleScholarId());
        view.setBuildVersion(buildVersion);
        view.setBuildAt(buildAt);
        view.setUpdatedAt(buildAt);
        view.setScopusLineage(fact.getSourceEventId());
        view.setWosLineage(fact.getWosId() == null ? null : fact.getSource());
        view.setScholarLineage(fact.getGoogleScholarId() == null ? null : fact.getSource());
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

    private void markImported(ImportProcessingResult result, int count) {
        for (int i = 0; i < count; i++) {
            result.markProcessed();
            result.markImported();
        }
    }

    private String normalizeDoi(String doi) {
        if (doi == null) {
            return null;
        }
        String normalized = doi.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        normalized = DOI_URL_PREFIX.matcher(normalized).replaceFirst("");
        normalized = DOI_PREFIX.matcher(normalized).replaceFirst("");
        normalized = normalized.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}
