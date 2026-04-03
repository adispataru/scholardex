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
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
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
}
