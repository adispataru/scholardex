package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Affiliation;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.repository.ActivityRepository;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.reporting.CoreConferenceRankingRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.repository.reporting.RankingRepository;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminCatalogFacadeTest {

    @Mock private ScholardexProjectionReadService scholardexProjectionReadService;
    @Mock private ArtisticEventRepository artisticEventRepository;
    @Mock private RankingRepository rankingRepository;
    @Mock private CoreConferenceRankingRepository coreConferenceRankingRepository;
    @Mock private IndicatorRepository indicatorRepository;
    @Mock private DomainRepository domainRepository;
    @Mock private InstitutionRepository institutionRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private WosCategoryFactRepository wosCategoryFactRepository;

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
        WosCategoryFact factB = new WosCategoryFact();
        factB.setCategoryNameCanonical("B");
        factB.setEditionNormalized(EditionNormalized.SCIE);
        WosCategoryFact factA = new WosCategoryFact();
        factA.setCategoryNameCanonical("A");
        factA.setEditionNormalized(EditionNormalized.SSCI);
        when(wosCategoryFactRepository.findAllByEditionNormalizedIn(Set.of(EditionNormalized.SCIE, EditionNormalized.SSCI)))
                .thenReturn(List.of(factB, factA));

        List<String> categories = facade.listWosCategories();
        assertEquals(List.of("A - SSCI", "B - SCIE"), categories);
    }

    @Test
    void affiliationsAndVenuesDelegationWorks() {
        Affiliation affiliation = new Affiliation();
        affiliation.setName("Aff");
        Forum forum = new Forum();
        when(scholardexProjectionReadService.findAffiliationsByNameContains("uvt")).thenReturn(List.of(affiliation));
        when(scholardexProjectionReadService.findAllForums()).thenReturn(List.of(forum));

        assertEquals(1, facade.listAffiliationsByNameContains("uvt").size());
        assertEquals(1, facade.listScopusVenues().size());
    }
}
