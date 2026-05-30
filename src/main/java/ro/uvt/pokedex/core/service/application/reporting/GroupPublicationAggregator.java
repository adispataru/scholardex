package ro.uvt.pokedex.core.service.application.reporting;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.application.GroupMembershipService;
import ro.uvt.pokedex.core.service.application.PersistenceYearSupport;
import ro.uvt.pokedex.core.service.application.PublicationOrderingSupport;
import ro.uvt.pokedex.core.service.application.ResearcherAuthorLookupService;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;
import ro.uvt.pokedex.core.service.application.model.GroupPublicationsViewModel;
import ro.uvt.pokedex.core.repository.UserRepository;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Builds the publication-overview view-model for a group: researchers, publications, authors,
 * forums, and per-year aggregates (counts + venue-class histogram).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GroupPublicationAggregator {

    private final GroupRepository groupRepository;
    private final UserRepository userRepository;
    private final GroupMembershipService groupMembershipService;
    private final IndividualReportRepository individualReportRepository;
    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final ResearcherAuthorLookupService researcherAuthorLookupService;
    private final VenueClassifier venueClassifier;

    public Optional<GroupPublicationsViewModel> buildView(String groupId) {
        Group group = groupRepository.findById(groupId).orElse(null);
        if (group == null) return Optional.empty();

        List<User> researchers = loadResearchers(group);
        researchers.sort(Comparator.comparing(u -> u.getResearcherProfile().getName()));

        List<String> lookupKeys = new ArrayList<>();
        for (User user : researchers) {
            lookupKeys.addAll(researcherAuthorLookupService.resolveAuthorLookupKeys(user.getResearcherProfile()));
        }
        List<String> authorIds = scholardexProjectionReadService.findAuthorsByIdIn(lookupKeys).stream()
                .map(ScholardexAuthorView::getId)
                .distinct()
                .toList();

        Map<String, ScholardexPublicationView> publicationsById = new LinkedHashMap<>();
        scholardexProjectionReadService.findAllPublicationsByAuthorsIn(authorIds)
                .forEach(publication -> publicationsById.putIfAbsent(publication.getId(), publication));
        List<ScholardexPublicationView> publications = new ArrayList<>(publicationsById.values());
        PublicationOrderingSupport.sortPublicationsInPlace(publications);

        Set<String> authorKeys = new HashSet<>();
        Set<String> forumKeys = new HashSet<>();
        publications.forEach(p -> {
            authorKeys.addAll(p.getAuthors());
            forumKeys.add(p.getForum());
        });

        Map<String, ScholardexAuthorView> authorMap = new HashMap<>();
        scholardexProjectionReadService.findAuthorsByIdIn(authorKeys)
                .forEach(a -> authorMap.put(a.getId(), a));

        Map<String, ScholardexForumView> forumMap = new HashMap<>();
        scholardexProjectionReadService.findForumsByIdIn(forumKeys)
                .forEach(f -> forumMap.put(f.getId(), f));

        Map<Integer, List<ScholardexPublicationView>> publicationsByYear = publications.stream()
                .map(publication -> new AbstractMap.SimpleEntry<>(
                        publication,
                        PersistenceYearSupport.extractYear(publication.getCoverDate(), publication.getId(), log)))
                .filter(entry -> entry.getValue().isPresent())
                .collect(Collectors.groupingBy(
                        entry -> entry.getValue().get(),
                        TreeMap::new,
                        Collectors.mapping(Map.Entry::getKey, Collectors.toList())
                ));
        publicationsByYear.values().forEach(PublicationOrderingSupport::sortPublicationsInPlace);

        Map<Integer, Long> publicationsCountByYear = publications.stream()
                .map(publication -> PersistenceYearSupport.extractYear(publication.getCoverDate(), publication.getId(), log))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.groupingBy(year -> year, TreeMap::new, Collectors.counting()));

        Map<Integer, Map<String, Long>> venueClassCountByYear = new TreeMap<>();
        publications.forEach(publication -> PersistenceYearSupport.extractYear(publication.getCoverDate(), publication.getId(), log)
                .ifPresent(year -> {
                    String bucket = venueClassifier.classify(publication, forumMap.get(publication.getForum()));
                    venueClassCountByYear
                            .computeIfAbsent(year, ignored -> new LinkedHashMap<>(venueClassifier.emptyBucketCounts()))
                            .merge(bucket, 1L, Long::sum);
                }));

        List<IndividualReport> allReports = individualReportRepository.findAll();

        return Optional.of(new GroupPublicationsViewModel(
                group, researchers, publications, authorMap, forumMap,
                publicationsByYear, publicationsCountByYear, venueClassCountByYear, allReports));
    }

    private List<User> loadResearchers(Group group) {
        List<String> memberIds = groupMembershipService.listCurrentMemberUserIds(group.getId());
        if (memberIds.isEmpty()) return new ArrayList<>();
        return userRepository.findAllById(memberIds).stream()
                .filter(u -> u.getResearcherProfile() != null)
                .collect(Collectors.toList());
    }
}
