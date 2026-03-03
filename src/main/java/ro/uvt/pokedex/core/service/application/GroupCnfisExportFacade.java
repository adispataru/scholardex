package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.WoSExtractor;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusPublicationRepository;
import ro.uvt.pokedex.core.service.application.model.GroupCnfisExportViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupCnfisZipExportViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupMemberCnfisWorkbook;
import ro.uvt.pokedex.core.service.reporting.CNFISReportExportService;
import ro.uvt.pokedex.core.service.reporting.CNFISScoringService2025;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GroupCnfisExportFacade {
    private final GroupManagementFacade groupManagementFacade;
    private final ScopusPublicationRepository scopusPublicationRepository;
    private final ScopusForumRepository scopusForumRepository;
    private final CNFISScoringService2025 cnfiSScoringService2025;
    private final WoSExtractor woSExtractor;
    private final CNFISReportExportService exportService;

    public Optional<GroupCnfisExportViewModel> buildGroupCnfisExport(String groupId, int startYear, int endYear) {
        Group group = groupManagementFacade.buildGroupEditView(groupId).group();
        if (group == null) {
            return Optional.empty();
        }

        List<Researcher> researchers = new ArrayList<>(group.getResearchers());
        researchers.sort(Comparator.comparing(Researcher::getName));
        List<String> authorIds = new ArrayList<>();
        for (Researcher researcher : researchers) {
            authorIds.addAll(researcher.getScopusId());
        }

        List<Publication> publications = scopusPublicationRepository.findAllByAuthorsIn(authorIds);
        publications = filterPublicationsByYear(publications, startYear, endYear);

        Domain allDomain = resolveAllDomain();
        List<CNFISReport2025> cnfisReports = generateReports(publications, allDomain);
        Map<String, Forum> forumMap = loadForumMap(publications);

        return Optional.of(new GroupCnfisExportViewModel(publications, cnfisReports, forumMap, authorIds));
    }

    public Optional<GroupCnfisZipExportViewModel> buildGroupCnfisZipExport(String groupId, int startYear, int endYear) throws IOException {
        Group group = groupManagementFacade.buildGroupEditView(groupId).group();
        if (group == null) {
            return Optional.empty();
        }

        Domain allDomain = resolveAllDomain();
        List<GroupMemberCnfisWorkbook> workbooks = new ArrayList<>();

        for (Researcher researcher : group.getResearchers()) {
            List<String> authorIds = researcher.getScopusId();
            List<Publication> publications = scopusPublicationRepository.findAllByAuthorsIn(authorIds);
            publications = filterPublicationsByYear(publications, startYear, endYear);

            List<CNFISReport2025> cnfisReports = generateReports(publications, allDomain);
            Map<String, Forum> forumMap = loadForumMap(publications);
            byte[] reportBytes = exportService.generateCNFISReportWorkbook(publications, cnfisReports, forumMap, authorIds, false);
            String entryName = researcher.getLastName() + "_" + researcher.getFirstName().charAt(0) + "_AB.xlsx";
            workbooks.add(new GroupMemberCnfisWorkbook(entryName, reportBytes));
        }

        return Optional.of(new GroupCnfisZipExportViewModel(workbooks));
    }

    private Domain resolveAllDomain() {
        return groupManagementFacade.buildGroupListView().allDomains().stream()
                .filter(domain -> "ALL".equals(domain.getName()))
                .findFirst()
                .orElse(null);
    }

    private List<Publication> filterPublicationsByYear(List<Publication> publications, int startYear, int endYear) {
        return publications.stream().filter(publication -> {
            int pubYear = Integer.parseInt(publication.getCoverDate().substring(0, 4));
            return pubYear >= startYear && pubYear <= endYear;
        }).toList();
    }

    private List<CNFISReport2025> generateReports(List<Publication> publications, Domain domain) {
        List<CNFISReport2025> reports = new ArrayList<>();
        for (Publication publication : publications) {
            Publication enrichedPublication = woSExtractor.findPublicationWosId(publication);
            scopusPublicationRepository.save(enrichedPublication);
            reports.add(cnfiSScoringService2025.getReport(enrichedPublication, domain));
        }
        return reports;
    }

    private Map<String, Forum> loadForumMap(List<Publication> publications) {
        Set<String> forumKeys = publications.stream().map(Publication::getForum).collect(Collectors.toSet());
        return scopusForumRepository.findByIdIn(forumKeys).stream()
                .collect(Collectors.toMap(Forum::getId, forum -> forum));
    }
}
