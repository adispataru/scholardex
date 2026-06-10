package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.repository.ActivityRepository;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.reporting.CoreConferenceRankingRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminCatalogFacadeTest {

    @Mock private ScholardexProjectionReadService scholardexProjectionReadService;
    @Mock private ScholardexManualEditService scholardexManualEditService;
    @Mock private ArtisticEventRepository artisticEventRepository;
    @Mock private CoreConferenceRankingRepository coreConferenceRankingRepository;
    @Mock private IndicatorRepository indicatorRepository;
    @Mock private DomainRepository domainRepository;
    @Mock private InstitutionRepository institutionRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    @Mock private PostgresWosRankingDetailsReadPort postgresWosRankingDetailsReadPort;

    @InjectMocks
    private AdminCatalogFacade facade;

    @Test
    void institutionAndDomainDelegationWorks() {
        when(institutionRepository.findAll()).thenReturn(List.of(new Institution()));
        when(domainRepository.findAll()).thenReturn(List.of(new Domain()));
        assertEquals(1, facade.listInstitutions().size());
        assertEquals(1, facade.listDomains().size());
    }

    @Test
    void duplicateIndicatorReturnsSavedCopy() {
        Indicator indicator = new Indicator();
        indicator.setId("i1");
        indicator.setName("N");
        Indicator saved = new Indicator();
        saved.setId("i2");
        when(indicatorRepository.findById("i1")).thenReturn(Optional.of(indicator));
        when(indicatorRepository.save(any(Indicator.class))).thenReturn(saved);

        Optional<Indicator> duplicated = facade.duplicateIndicator("i1");
        assertTrue(duplicated.isPresent());
        assertEquals("i2", duplicated.get().getId());
    }

    @Test
    void listWosCategoriesReturnsSortedValues() {
        when(namedParameterJdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of("A - SSCI", "B - SCIE"));

        List<String> categories = facade.listWosCategories();
        assertEquals(List.of("A - SSCI", "B - SCIE"), categories);
    }

    @Test
    void affiliationsAndVenuesDelegationWorks() {
        ScholardexAffiliationView affiliation = new ScholardexAffiliationView();
        affiliation.setName("Aff");
        ScholardexForumView forum = new ScholardexForumView();
        when(scholardexProjectionReadService.findAffiliationsByNameContains("uvt")).thenReturn(List.of(affiliation));
        when(scholardexProjectionReadService.findAllForums()).thenReturn(List.of(forum));

        assertEquals(1, facade.listAffiliationsByNameContains("uvt").size());
        assertEquals(1, facade.listScopusVenues().size());
    }

    @Test
    void operationalDelegationCoverage() {
        Institution institution = new Institution();
        institution.setName("i1");
        Indicator indicator = new Indicator();
        indicator.setId("ind1");
        indicator.setName("N1");
        Domain domain = new Domain();
        domain.setName("d1");
        Activity activity = new Activity();
        ScholardexAffiliationView affiliationA = new ScholardexAffiliationView();
        affiliationA.setName("B");
        ScholardexAffiliationView affiliationB = new ScholardexAffiliationView();
        affiliationB.setName("A");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("f1");
        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId("a1");
        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId("p1");
        WoSRanking wos = new WoSRanking();
        CoreConferenceRanking core = new CoreConferenceRanking();
        ArtisticEvent artisticEvent = new ArtisticEvent();

        when(scholardexProjectionReadService.findAffiliationsByCountry("RO")).thenReturn(List.of(affiliationA, affiliationB));
        when(institutionRepository.findById("i1")).thenReturn(Optional.of(institution));
        when(institutionRepository.save(institution)).thenReturn(institution);
        when(indicatorRepository.findAll()).thenReturn(List.of(indicator));
        when(indicatorRepository.findById("ind1")).thenReturn(Optional.of(indicator));
        when(indicatorRepository.save(indicator)).thenReturn(indicator);
        when(activityRepository.findAll()).thenReturn(List.of(activity));
        when(domainRepository.findById("d1")).thenReturn(Optional.of(domain));
        when(domainRepository.save(domain)).thenReturn(domain);
        when(scholardexProjectionReadService.findForumById("f1")).thenReturn(Optional.of(forum));
        when(scholardexManualEditService.saveForum(forum)).thenReturn(forum);
        when(scholardexProjectionReadService.findAuthorsByAffiliationId("aff1")).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findAuthorById("a1")).thenReturn(Optional.of(author));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsContaining("a1")).thenReturn(List.of(publication));
        when(scholardexManualEditService.saveAuthor(author)).thenReturn(author);
        when(scholardexProjectionReadService.findAllAffiliations()).thenReturn(List.of(affiliationA));
        when(scholardexProjectionReadService.findAffiliationById("aff1")).thenReturn(Optional.of(affiliationA));
        when(scholardexManualEditService.saveAffiliation(affiliationA)).thenReturn(affiliationA);
        when(artisticEventRepository.findAll()).thenReturn(List.of(artisticEvent));
        when(coreConferenceRankingRepository.findAll()).thenReturn(List.of(core));
        when(postgresWosRankingDetailsReadPort.findByJournalId("w1")).thenReturn(Optional.of(wos));
        when(coreConferenceRankingRepository.findById("c1")).thenReturn(Optional.of(core));

        assertEquals(List.of("A", "B"), facade.listAffiliationsByCountry("RO").stream().map(ScholardexAffiliationView::getName).toList());
        assertEquals(Optional.of(institution), facade.findInstitutionById("i1"));
        assertEquals(institution, facade.saveInstitution(institution));
        facade.deleteInstitution("i1");
        assertEquals(1, facade.listIndicators().size());
        assertEquals(Optional.of(indicator), facade.findIndicatorById("ind1"));
        assertEquals(indicator, facade.saveIndicator(indicator));
        facade.deleteIndicator("ind1");
        assertEquals(1, facade.listActivities().size());
        assertEquals(Optional.of(domain), facade.findDomainById("d1"));
        assertEquals(domain, facade.saveDomain(domain));
        facade.deleteDomain("d1");
        assertEquals(Optional.of(forum), facade.findScopusVenueById("f1"));
        assertEquals(forum, facade.saveScopusVenue(forum));
        assertEquals(1, facade.listScopusAuthorsByAffiliation("aff1").size());
        assertEquals(Optional.of(author), facade.findScopusAuthorById("a1"));
        assertEquals(1, facade.listPublicationsByAuthorId("a1").size());
        assertEquals(author, facade.saveScopusAuthor(author));
        assertEquals(1, facade.listScopusAffiliations().size());
        assertEquals(Optional.of(affiliationA), facade.findScopusAffiliationById("aff1"));
        assertEquals(affiliationA, facade.saveScopusAffiliation(affiliationA));
        assertEquals(1, facade.listArtisticEvents().size());
        assertEquals(1, facade.listCoreRankings().size());
        assertEquals(Optional.of(wos), facade.findWosRankingById("w1"));
        assertEquals(Optional.of(core), facade.findCoreRankingById("c1"));

        verify(institutionRepository).deleteById("i1");
        verify(indicatorRepository).deleteById("ind1");
        verify(domainRepository).deleteById("d1");
    }

    @Test
    void duplicateIndicatorMutatesSourceValuesBeforeSave() {
        Indicator source = new Indicator();
        source.setId("id-1");
        source.setName("Name");
        when(indicatorRepository.findById("id-1")).thenReturn(Optional.of(source));
        when(indicatorRepository.save(any(Indicator.class))).thenAnswer(inv -> inv.getArgument(0));

        Indicator duplicated = facade.duplicateIndicator("id-1").orElseThrow();
        assertEquals("Name (copy)", duplicated.getName());
        assertEquals(null, duplicated.getId());
    }
}
