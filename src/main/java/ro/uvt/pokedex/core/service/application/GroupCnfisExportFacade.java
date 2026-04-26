package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.CanonicalPublicationConstants;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.WoSExtractor;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.service.application.model.GroupCnfisExportViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupCnfisZipExportViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupMemberCnfisWorkbook;
import ro.uvt.pokedex.core.service.application.model.GroupWorkbookExportResult;
import ro.uvt.pokedex.core.service.reporting.CNFISReportExportService;
import ro.uvt.pokedex.core.service.reporting.CNFISScoringService2025;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GroupCnfisExportFacade {
    private static final String WOS_EXTRACTOR_SOURCE = "WOSEXTRACTOR";
    private static final String LINKER_VERSION = "h17.10";

    private final GroupManagementFacade groupManagementFacade;
    private final UserRepository userRepository;
    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final ResearcherAuthorLookupService researcherAuthorLookupService;
    private final PublicationEnrichmentLinkerService publicationEnrichmentLinkerService;
    private final CNFISScoringService2025 cnfiSScoringService2025;
    private final WoSExtractor woSExtractor;
    private final CNFISReportExportService exportService;

    public Optional<GroupCnfisExportViewModel> buildGroupCnfisExport(String groupId, int startYear, int endYear) {
        Group group = groupManagementFacade.buildGroupEditView(groupId).group();
        if (group == null) {
            return Optional.empty();
        }

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

        List<ScholardexPublicationView> publications = scholardexProjectionReadService.findAllPublicationsByAuthorsIn(authorIds);
        publications = filterPublicationsByYear(publications, startYear, endYear);

        Domain allDomain = resolveAllDomain();
        List<ScoringPublicationReadModel> scoringPublications = publications.stream()
                .map(this::withResolvedWosId)
                .toList();
        List<CNFISReport2025> cnfisReports = generateReports(scoringPublications, allDomain);
        Map<String, ScholardexForumView> forumMap = loadForumMap(publications);

        return Optional.of(new GroupCnfisExportViewModel(scoringPublications, cnfisReports, forumMap, authorIds));
    }

    public Optional<GroupCnfisZipExportViewModel> buildGroupCnfisZipExport(String groupId, int startYear, int endYear) throws IOException {
        Group group = groupManagementFacade.buildGroupEditView(groupId).group();
        if (group == null) {
            return Optional.empty();
        }

        Domain allDomain = resolveAllDomain();
        List<GroupMemberCnfisWorkbook> workbooks = new ArrayList<>();

        for (User user : loadResearchers(group)) {
            List<String> authorIds = scholardexProjectionReadService.findAuthorsByIdIn(
                    researcherAuthorLookupService.resolveAuthorLookupKeys(user.getResearcherProfile())
            ).stream().map(ScholardexAuthorView::getId).toList();
            List<ScholardexPublicationView> publications = scholardexProjectionReadService.findAllPublicationsByAuthorsIn(authorIds);
            publications = filterPublicationsByYear(publications, startYear, endYear);

            List<ScoringPublicationReadModel> scoringPublications = publications.stream()
                    .map(this::withResolvedWosId)
                    .toList();
            List<CNFISReport2025> cnfisReports = generateReports(scoringPublications, allDomain);
            Map<String, ScholardexForumView> forumMap = loadForumMap(publications);
            byte[] reportBytes = exportService.generateCNFISReportWorkbook(scoringPublications, cnfisReports, forumMap, authorIds, false);
            String entryName = user.getResearcherProfile().getLastName() + "_" + user.getResearcherProfile().getFirstName().charAt(0) + "_AB.xlsx";
            workbooks.add(new GroupMemberCnfisWorkbook(entryName, reportBytes));
        }

        return Optional.of(new GroupCnfisZipExportViewModel(workbooks));
    }

    public Optional<GroupWorkbookExportResult> buildGroupCnfisWorkbookExport(String groupId, int startYear, int endYear) throws IOException {
        Optional<GroupCnfisExportViewModel> exportViewModel = buildGroupCnfisExport(groupId, startYear, endYear);
        if (exportViewModel.isEmpty()) {
            return Optional.empty();
        }

        GroupCnfisExportViewModel vm = exportViewModel.get();
        byte[] workbookBytes = exportService.generateCNFISReportWorkbook(
                vm.publications(),
                vm.cnfisReports(),
                vm.forumMap(),
                vm.authorIds(),
                true
        );

        return Optional.of(new GroupWorkbookExportResult(
                workbookBytes,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "data/templates/AC2025_Anexa6-Tabel_institutional_articole_brevete-2025.xlsx"
        ));
    }

    private List<User> loadResearchers(Group group) {
        List<String> memberIds = group.getMemberIds();
        if (memberIds == null || memberIds.isEmpty()) return new ArrayList<>();
        return userRepository.findAllById(memberIds).stream()
                .filter(u -> u.getResearcherProfile() != null)
                .collect(Collectors.toList());
    }

    private Domain resolveAllDomain() {
        return groupManagementFacade.buildGroupListView().allDomains().stream()
                .filter(domain -> "ALL".equals(domain.getName()))
                .findFirst()
                .orElse(null);
    }

    private List<ScholardexPublicationView> filterPublicationsByYear(List<ScholardexPublicationView> publications, int startYear, int endYear) {
        return publications.stream().filter(publication -> {
            return PersistenceYearSupport.extractYear(publication.getCoverDate(), publication.getId(), log)
                    .map(pubYear -> pubYear >= startYear && pubYear <= endYear)
                    .orElse(false);
        }).toList();
    }

    private List<CNFISReport2025> generateReports(List<? extends ScoringPublicationReadModel> publications, Domain domain) {
        List<CNFISReport2025> reports = new ArrayList<>();
        for (ScoringPublicationReadModel publication : publications) {
            reports.add(cnfiSScoringService2025.getReport(publication, domain));
        }
        return reports;
    }

    private Map<String, ScholardexForumView> loadForumMap(List<ScholardexPublicationView> publications) {
        Set<String> forumKeys = publications.stream().map(ScholardexPublicationView::getForum).collect(Collectors.toSet());
        return scholardexProjectionReadService.findForumsByIdIn(forumKeys).stream()
                .collect(Collectors.toMap(ScholardexForumView::getId, forum -> forum));
    }

    private ScoringPublicationReadModel withResolvedWosId(ScholardexPublicationView publication) {
        String resolvedWosId = publication.getWosId();
        if ((resolvedWosId == null || resolvedWosId.isBlank())
                && publication.getDoi() != null && !publication.getDoi().isBlank()) {
            resolvedWosId = woSExtractor.resolveWosId(publication.getDoi())
                    .orElse(CanonicalPublicationConstants.NON_WOS_ID);
        }
        publicationEnrichmentLinkerService.linkWosEnrichment(
                publication.getId(),
                publication.getEid(),
                publication.getDoi(),
                resolvedWosId,
                WOS_EXTRACTOR_SOURCE,
                LINKER_VERSION,
                "group-cnfis-" + System.currentTimeMillis()
        );
        publication.setWosId(resolvedWosId);
        return publication.toScoringPublication();
    }
}
