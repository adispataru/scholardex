package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.activities.Activity;
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
import ro.uvt.pokedex.core.repository.reporting.CoreConferenceRankingRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AdminCatalogFacade {

    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final ArtisticEventRepository artisticEventRepository;
    private final CoreConferenceRankingRepository coreConferenceRankingRepository;
    private final IndicatorRepository indicatorRepository;
    private final DomainRepository domainRepository;
    private final InstitutionRepository institutionRepository;
    private final ActivityRepository activityRepository;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final PostgresWosRankingDetailsReadPort postgresWosRankingDetailsReadPort;

    public List<Institution> listInstitutions() {
        return institutionRepository.findAll();
    }

    public List<ScholardexAffiliationView> listAffiliationsByNameContains(String afname) {
        List<ScholardexAffiliationView> affiliations = new ArrayList<>(scholardexProjectionReadService.findAffiliationsByNameContains(afname));
        affiliations.sort(java.util.Comparator.comparing(ScholardexAffiliationView::getName));
        return affiliations;
    }

    public List<ScholardexAffiliationView> listAffiliationsByCountry(String country) {
        List<ScholardexAffiliationView> affiliations = new ArrayList<>(scholardexProjectionReadService.findAffiliationsByCountry(country));
        affiliations.sort(java.util.Comparator.comparing(ScholardexAffiliationView::getName));
        return affiliations;
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
        return scholardexProjectionReadService.saveForum(forum);
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
        return scholardexProjectionReadService.saveAuthor(author);
    }

    public List<ScholardexAffiliationView> listScopusAffiliations() {
        return scholardexProjectionReadService.findAllAffiliations();
    }

    public Optional<ScholardexAffiliationView> findScopusAffiliationById(String id) {
        return scholardexProjectionReadService.findAffiliationById(id);
    }

    public ScholardexAffiliationView saveScopusAffiliation(ScholardexAffiliationView affiliation) {
        return scholardexProjectionReadService.saveAffiliation(affiliation);
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

}
