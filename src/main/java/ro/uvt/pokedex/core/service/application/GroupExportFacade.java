package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.application.model.GroupPublicationCsvExportViewModel;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GroupExportFacade {
    private final GroupManagementFacade groupManagementFacade;
    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final ResearcherAuthorLookupService researcherAuthorLookupService;

    public Optional<GroupPublicationCsvExportViewModel> buildGroupPublicationCsvExport(String groupId) {
        Group group = groupManagementFacade.buildGroupEditView(groupId).group();
        if (group == null) {
            return Optional.empty();
        }

        List<Researcher> researchers = new ArrayList<>(group.getResearchers());
        researchers.sort(Comparator.comparing(Researcher::getName));
        List<String> lookupKeys = new ArrayList<>();
        for (Researcher researcher : researchers) {
            lookupKeys.addAll(researcherAuthorLookupService.resolveAuthorLookupKeys(researcher));
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
        publications.forEach(publication -> {
            authorKeys.addAll(publication.getAuthors());
            forumKeys.add(publication.getForum());
        });

        Map<String, ScholardexAuthorView> authorMap = scholardexProjectionReadService.findAuthorsByIdIn(authorKeys).stream()
                .collect(Collectors.toMap(ScholardexAuthorView::getId, author -> author));
        Map<String, ScholardexForumView> forumMap = scholardexProjectionReadService.findForumsByIdIn(forumKeys).stream()
                .collect(Collectors.toMap(ScholardexForumView::getId, forum -> forum));

        return Optional.of(new GroupPublicationCsvExportViewModel(
                publications,
                authorMap,
                forumMap,
                new HashSet<>(authorIds)
        ));
    }
}
