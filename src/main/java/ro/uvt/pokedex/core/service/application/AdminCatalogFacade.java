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
import ro.uvt.pokedex.core.model.scopus.Affiliation;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.repository.ActivityRepository;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.reporting.CoreConferenceRankingRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.repository.reporting.RankingRepository;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminCatalogFacade {

    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final ArtisticEventRepository artisticEventRepository;
    private final RankingRepository rankingRepository;
    private final CoreConferenceRankingRepository coreConferenceRankingRepository;
    private final IndicatorRepository indicatorRepository;
    private final DomainRepository domainRepository;
    private final InstitutionRepository institutionRepository;
    private final ActivityRepository activityRepository;
    private final WosCategoryFactRepository wosCategoryFactRepository;

    public List<Institution> listInstitutions() {
        return institutionRepository.findAll();
    }

    public List<Affiliation> listAffiliationsByNameContains(String afname) {
        List<Affiliation> affiliations = new ArrayList<>(scholardexProjectionReadService.findAffiliationsByNameContains(afname));
        affiliations.sort(java.util.Comparator.comparing(Affiliation::getName));
        return affiliations;
    }

    public List<Affiliation> listAffiliationsByCountry(String country) {
        List<Affiliation> affiliations = new ArrayList<>(scholardexProjectionReadService.findAffiliationsByCountry(country));
        affiliations.sort(java.util.Comparator.comparing(Affiliation::getName));
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
        return wosCategoryFactRepository
                .findAllByEditionNormalizedIn(Set.of(EditionNormalized.SCIE, EditionNormalized.SSCI))
                .stream()
                .filter(f -> f.getCategoryNameCanonical() != null && !f.getCategoryNameCanonical().isBlank())
                .map(f -> f.getCategoryNameCanonical() + " - " + f.getEditionNormalized())
                .distinct()
                .sorted()
                .toList();
    }

    public List<Forum> listScopusVenues() {
        return scholardexProjectionReadService.findAllForums();
    }

    public Optional<Forum> findScopusVenueById(String id) {
        return scholardexProjectionReadService.findForumById(id);
    }

    public Forum saveScopusVenue(Forum forum) {
        return scholardexProjectionReadService.saveForum(forum);
    }

    public List<Author> listScopusAuthorsByAffiliation(String affiliationId) {
        return scholardexProjectionReadService.findAuthorsByAffiliationId(affiliationId);
    }

    public Optional<Author> findScopusAuthorById(String id) {
        return scholardexProjectionReadService.findAuthorById(id);
    }

    public List<Publication> listPublicationsByAuthorId(String authorId) {
        return scholardexProjectionReadService.findAllPublicationsByAuthorsContaining(authorId);
    }

    public Author saveScopusAuthor(Author author) {
        return scholardexProjectionReadService.saveAuthor(author);
    }

    public List<Affiliation> listScopusAffiliations() {
        return scholardexProjectionReadService.findAllAffiliations();
    }

    public Optional<Affiliation> findScopusAffiliationById(String id) {
        return scholardexProjectionReadService.findAffiliationById(id);
    }

    public Affiliation saveScopusAffiliation(Affiliation affiliation) {
        return scholardexProjectionReadService.saveAffiliation(affiliation);
    }

    public List<ArtisticEvent> listArtisticEvents() {
        return artisticEventRepository.findAll();
    }

    public List<CoreConferenceRanking> listCoreRankings() {
        return coreConferenceRankingRepository.findAll();
    }

    public Optional<WoSRanking> findWosRankingById(String id) {
        return rankingRepository.findById(id);
    }

    public Optional<CoreConferenceRanking> findCoreRankingById(String id) {
        return coreConferenceRankingRepository.findById(id);
    }

}
