package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import ro.uvt.pokedex.core.repository.ActivityRepository;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.CoreConferenceRankingRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AdminCatalogFacade {

    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final ScholardexManualEditService scholardexManualEditService;
    private final ArtisticEventRepository artisticEventRepository;
    private final CoreConferenceRankingRepository coreConferenceRankingRepository;
    private final IndicatorRepository indicatorRepository;
    private final DomainRepository domainRepository;
    private final InstitutionRepository institutionRepository;
    private final OrgDivisionRepository orgDivisionRepository;
    private final DepartmentRepository departmentRepository;
    private final ActivityRepository activityRepository;
    private final UserRepository userRepository;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final PostgresWosRankingDetailsReadPort postgresWosRankingDetailsReadPort;

    public List<Institution> listInstitutions() {
        return institutionRepository.findAll();
    }

    public List<ScholardexAffiliationView> listAffiliationsByNameContains(String afname) {
        return sortAffiliationsByName(scholardexProjectionReadService.findAffiliationsByNameContains(afname));
    }

    public List<ScholardexAffiliationView> listAffiliationsByCountry(String country) {
        return sortAffiliationsByName(scholardexProjectionReadService.findAffiliationsByCountry(country));
    }

    public Optional<Institution> findInstitutionById(String id) {
        return institutionRepository.findById(id);
    }

    public Institution saveInstitution(Institution institution) {
        return institutionRepository.save(institution);
    }

    public void deleteInstitution(String id) {
        institutionRepository.deleteById(id);
    }

    // --- Org divisions (Faculty / Institute / Service) ---

    public List<OrgDivision> listOrgDivisions() {
        List<OrgDivision> divisions = new ArrayList<>(orgDivisionRepository.findAll());
        divisions.sort(Comparator.comparing(d -> d.getName() == null ? "" : d.getName()));
        return divisions;
    }

    public Optional<OrgDivision> findOrgDivisionById(String id) {
        return orgDivisionRepository.findById(id);
    }

    public OrgDivision saveOrgDivision(OrgDivision division) {
        division.setHeadUserIds(IdListCleaner.clean(division.getHeadUserIds()));
        requireKnownUsers(division.getHeadUserIds(), "Division heads");
        java.time.Instant now = java.time.Instant.now();
        if (division.getCreatedAt() == null) division.setCreatedAt(now);
        division.setUpdatedAt(now);
        return orgDivisionRepository.save(division);
    }

    public void deleteOrgDivision(String id) {
        if (!departmentRepository.findByDivisionId(id).isEmpty()) {
            throw new IllegalStateException("Cannot delete division: it still has departments.");
        }
        orgDivisionRepository.deleteById(id);
    }

    // --- Departments ---

    public List<Department> listDepartments() {
        List<Department> departments = new ArrayList<>(departmentRepository.findAll());
        departments.sort(Comparator.comparing(d -> d.getName() == null ? "" : d.getName()));
        return departments;
    }

    public Optional<Department> findDepartmentById(String id) {
        return departmentRepository.findById(id);
    }

    /**
     * Rebuilds {@code institutionId} from the parent {@link OrgDivision} so the denormalized
     * field can never drift from the parent.
     */
    public Department saveDepartment(Department department) {
        if (department.getDivisionId() == null || department.getDivisionId().isBlank()) {
            throw new IllegalArgumentException("Department divisionId is required.");
        }
        OrgDivision parent = orgDivisionRepository.findById(department.getDivisionId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Parent division not found: " + department.getDivisionId()));
        department.setInstitutionId(parent.getInstitutionId());
        department.setHeadUserIds(IdListCleaner.clean(department.getHeadUserIds()));
        requireKnownUsers(department.getHeadUserIds(), "Department heads");
        java.time.Instant now = java.time.Instant.now();
        if (department.getCreatedAt() == null) department.setCreatedAt(now);
        department.setUpdatedAt(now);
        return departmentRepository.save(department);
    }

    /**
     * Rejects any email that doesn't resolve to a persisted {@code User}. Belt-and-suspenders
     * for the dropdown-constrained admin forms: a hand-crafted POST shouldn't be able to plant
     * phantom heads that nobody can ever sign in as.
     */
    private void requireKnownUsers(List<String> emails, String label) {
        if (emails == null || emails.isEmpty()) return;
        java.util.Set<String> found = new java.util.HashSet<>();
        for (var u : userRepository.findAllById(emails)) found.add(u.getEmail());
        List<String> unknown = new ArrayList<>();
        for (String email : emails) if (!found.contains(email)) unknown.add(email);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(label + " include unknown users: " + unknown);
        }
    }

    public void deleteDepartment(String id) {
        departmentRepository.deleteById(id);
    }

    public List<Indicator> listIndicators() {
        return indicatorRepository.findAll();
    }

    public Optional<Indicator> findIndicatorById(String id) {
        return indicatorRepository.findById(id);
    }

    public Indicator saveIndicator(Indicator indicator) {
        return indicatorRepository.save(indicator);
    }

    public Optional<Indicator> duplicateIndicator(String id) {
        return indicatorRepository.findById(id).map(indicator -> {
            indicator.setId(null);
            indicator.setName(indicator.getName() + " (copy)");
            return indicatorRepository.save(indicator);
        });
    }

    public void deleteIndicator(String id) {
        indicatorRepository.deleteById(id);
    }

    public List<Activity> listActivities() {
        return activityRepository.findAll();
    }

    public List<Domain> listDomains() {
        return domainRepository.findAll();
    }

    public Optional<Domain> findDomainById(String id) {
        return domainRepository.findById(id);
    }

    public Domain saveDomain(Domain domain) {
        return domainRepository.save(domain);
    }

    public void deleteDomain(String id) {
        domainRepository.deleteById(id);
    }

    public List<String> listWosCategories() {
        return namedParameterJdbcTemplate.query(
                """
                SELECT DISTINCT category_name_canonical, edition_normalized
                FROM reporting_read.wos_category_fact
                WHERE edition_normalized IN ('SCIE', 'SSCI')
                  AND category_name_canonical IS NOT NULL
                  AND category_name_canonical != ''
                ORDER BY category_name_canonical, edition_normalized
                """,
                org.springframework.jdbc.core.namedparam.EmptySqlParameterSource.INSTANCE,
                (rs, rowNum) -> rs.getString("category_name_canonical") + " - " + rs.getString("edition_normalized")
        );
    }

    public List<ScholardexForumView> listScopusVenues() {
        return scholardexProjectionReadService.findAllForums();
    }

    public Optional<ScholardexForumView> findScopusVenueById(String id) {
        return scholardexProjectionReadService.findForumById(id);
    }

    public ScholardexForumView saveScopusVenue(ScholardexForumView forum) {
        return scholardexManualEditService.saveForum(forum);
    }

    public List<ScholardexAuthorView> listScopusAuthorsByAffiliation(String affiliationId) {
        return scholardexProjectionReadService.findAuthorsByAffiliationId(affiliationId);
    }

    public Optional<ScholardexAuthorView> findScopusAuthorById(String id) {
        return scholardexProjectionReadService.findAuthorById(id);
    }

    public List<ScholardexPublicationView> listPublicationsByAuthorId(String authorId) {
        return scholardexProjectionReadService.findAllPublicationsByAuthorsContaining(authorId);
    }

    public ScholardexAuthorView saveScopusAuthor(ScholardexAuthorView author) {
        return scholardexManualEditService.saveAuthor(author);
    }

    public List<ScholardexAffiliationView> listScopusAffiliations() {
        return scholardexProjectionReadService.findAllAffiliations();
    }

    public Optional<ScholardexAffiliationView> findScopusAffiliationById(String id) {
        return scholardexProjectionReadService.findAffiliationById(id);
    }

    public ScholardexAffiliationView saveScopusAffiliation(ScholardexAffiliationView affiliation) {
        return scholardexManualEditService.saveAffiliation(affiliation);
    }

    public List<ArtisticEvent> listArtisticEvents() {
        return artisticEventRepository.findAll();
    }

    public List<CoreConferenceRanking> listCoreRankings() {
        return coreConferenceRankingRepository.findAll();
    }

    public Optional<WoSRanking> findWosRankingById(String id) {
        return postgresWosRankingDetailsReadPort.findByJournalId(id);
    }

    public Optional<CoreConferenceRanking> findCoreRankingById(String id) {
        return coreConferenceRankingRepository.findById(id);
    }

    private List<ScholardexAffiliationView> sortAffiliationsByName(List<ScholardexAffiliationView> affiliations) {
        List<ScholardexAffiliationView> sorted = new ArrayList<>(affiliations);
        sorted.sort(Comparator.comparing(ScholardexAffiliationView::getName));
        return sorted;
    }

}
