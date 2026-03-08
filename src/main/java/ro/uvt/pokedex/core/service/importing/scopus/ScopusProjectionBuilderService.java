package ro.uvt.pokedex.core.service.importing.scopus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
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
    private final ScholardexForumFactRepository canonicalForumFactRepository;
    private final ScholardexAuthorFactRepository authorFactRepository;
    private final ScholardexAffiliationFactRepository affiliationFactRepository;
    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final ScopusCitationFactRepository citationFactRepository;
    private final ScholardexForumViewRepository forumViewRepository;
    private final ScholardexAuthorViewRepository authorViewRepository;
    private final ScholardexAffiliationViewRepository affiliationViewRepository;
    private final ScholardexPublicationViewRepository publicationViewRepository;

    public ScopusProjectionBuilderService(
            ScopusForumFactRepository forumFactRepository,
            ScholardexForumFactRepository canonicalForumFactRepository,
            ScholardexAuthorFactRepository authorFactRepository,
            ScholardexAffiliationFactRepository affiliationFactRepository,
            ScholardexPublicationFactRepository publicationFactRepository,
            ScopusCitationFactRepository citationFactRepository,
            ScholardexForumViewRepository forumViewRepository,
            ScholardexAuthorViewRepository authorViewRepository,
            ScholardexAffiliationViewRepository affiliationViewRepository,
            ScholardexPublicationViewRepository publicationViewRepository
    ) {
        this.forumFactRepository = forumFactRepository;
        this.canonicalForumFactRepository = canonicalForumFactRepository;
        this.authorFactRepository = authorFactRepository;
        this.affiliationFactRepository = affiliationFactRepository;
        this.publicationFactRepository = publicationFactRepository;
        this.citationFactRepository = citationFactRepository;
        this.forumViewRepository = forumViewRepository;
        this.authorViewRepository = authorViewRepository;
        this.affiliationViewRepository = affiliationViewRepository;
        this.publicationViewRepository = publicationViewRepository;
    }

    public ImportProcessingResult rebuildViews() {
        ImportProcessingResult result = new ImportProcessingResult(20);
        Instant buildAt = Instant.now();
        String buildVersion = buildAt.toString();
        try {
            List<ScopusForumFact> forumFacts = new ArrayList<>(forumFactRepository.findAll());
            forumFacts.sort(Comparator.comparing(ScopusForumFact::getSourceId, Comparator.nullsLast(String::compareTo)));
            List<ScholardexForumView> forumViews = forumFacts.stream()
                    .map(fact -> toForumView(fact, buildVersion, buildAt))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            mergeWosOnlyForumViews(forumViews, buildVersion, buildAt);
            forumViewRepository.deleteAll();
            forumViewRepository.saveAll(forumViews);
            markImported(result, forumViews.size());

            List<ScholardexAuthorFact> authorFacts = new ArrayList<>(authorFactRepository.findAll());
            authorFacts.sort(Comparator.comparing(ScholardexAuthorFact::getId, Comparator.nullsLast(String::compareTo)));
            List<ScholardexAuthorView> authorViews = authorFacts.stream()
                    .map(fact -> toAuthorView(fact, buildVersion, buildAt))
                    .toList();
            authorViewRepository.deleteAll();
            authorViewRepository.saveAll(authorViews);
            markImported(result, authorViews.size());

            List<ScholardexAffiliationFact> affiliationFacts = new ArrayList<>(affiliationFactRepository.findAll());
            affiliationFacts.sort(Comparator.comparing(ScholardexAffiliationFact::getId, Comparator.nullsLast(String::compareTo)));
            List<ScholardexAffiliationView> affiliationViews = affiliationFacts.stream()
                    .map(fact -> toAffiliationView(fact, buildVersion, buildAt))
                    .toList();
            affiliationViewRepository.deleteAll();
            affiliationViewRepository.saveAll(affiliationViews);
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

    private ScholardexForumView toForumView(ScopusForumFact fact, String buildVersion, Instant buildAt) {
        ScholardexForumView view = new ScholardexForumView();
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

    private void mergeWosOnlyForumViews(List<ScholardexForumView> forumViews, String buildVersion, Instant buildAt) {
        List<ScholardexForumFact> canonicalForums = new ArrayList<>(canonicalForumFactRepository.findAll());
        canonicalForums.sort(Comparator.comparing(ScholardexForumFact::getId, Comparator.nullsLast(String::compareTo)));
        java.util.Set<String> existingIds = forumViews.stream()
                .map(ScholardexForumView::getId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        for (ScholardexForumFact canonicalForum : canonicalForums) {
            if (!safeList(canonicalForum.getScopusForumIds()).isEmpty()) {
                continue;
            }
            if (canonicalForum.getId() == null || existingIds.contains(canonicalForum.getId())) {
                continue;
            }
            ScholardexForumView wosView = new ScholardexForumView();
            wosView.setId(canonicalForum.getId());
            wosView.setPublicationName(canonicalForum.getName());
            wosView.setIssn(canonicalForum.getIssn());
            wosView.setEIssn(canonicalForum.getEIssn());
            wosView.setAggregationType(canonicalForum.getAggregationType());
            wosView.setBuildVersion(buildVersion);
            wosView.setBuildAt(buildAt);
            wosView.setUpdatedAt(buildAt);
            wosView.setSourceEventId(canonicalForum.getSourceEventId());
            forumViews.add(wosView);
            existingIds.add(canonicalForum.getId());
        }
    }

    private ScholardexAuthorView toAuthorView(ScholardexAuthorFact fact, String buildVersion, Instant buildAt) {
        ScholardexAuthorView view = new ScholardexAuthorView();
        view.setId(fact.getId());
        view.setName(fact.getDisplayName());
        view.setAffiliationIds(fact.getAffiliationIds() == null ? List.of() : new ArrayList<>(fact.getAffiliationIds()));
        view.setBuildVersion(buildVersion);
        view.setBuildAt(buildAt);
        view.setUpdatedAt(buildAt);
        view.setSourceEventId(fact.getSourceEventId());
        return view;
    }

    private ScholardexAffiliationView toAffiliationView(ScholardexAffiliationFact fact, String buildVersion, Instant buildAt) {
        ScholardexAffiliationView view = new ScholardexAffiliationView();
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

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
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
