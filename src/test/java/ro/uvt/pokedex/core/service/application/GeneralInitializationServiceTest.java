package ro.uvt.pokedex.core.service.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.observability.StartupReadinessTracker;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.service.importing.AdminUserService;
import ro.uvt.pokedex.core.service.importing.ArtisticEventsService;
import ro.uvt.pokedex.core.service.importing.CNCSISService;
import ro.uvt.pokedex.core.service.importing.CoreConferenceRankingService;
import ro.uvt.pokedex.core.service.importing.SenseRankingService;
import ro.uvt.pokedex.core.service.importing.URAPRankingService;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeneralInitializationServiceTest {

    @Mock
    private AdminUserService adminUserService;
    @Mock
    private ArtisticEventsService artisticEventsService;
    @Mock
    private URAPRankingService urapRankingService;
    @Mock
    private CNCSISService cncsisService;
    @Mock
    private CoreConferenceRankingService coreConferenceRankingService;
    @Mock
    private SenseRankingService senseRankingService;
    @Mock
    private DomainRepository domainRepository;

    private GeneralInitializationService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new GeneralInitializationService(
                adminUserService,
                artisticEventsService,
                urapRankingService,
                cncsisService,
                coreConferenceRankingService,
                senseRankingService,
                domainRepository,
                new SimpleMeterRegistry(),
                new StartupReadinessTracker()
        );
        setField("urapFolderPath", "data/urap-univ");
        setField("cncsisFilePath", "data/cncsis/publisher_list.xlsx");
        setField("coreConferenceFolderPath", "data/core-conf");
        setField("senseFilePath", "data/sense/SENSE-rankings.xlsx");
    }

    @Test
    void runAllExecutesAllStepsInOrder() {
        when(domainRepository.findById("all")).thenReturn(Optional.of(new Domain()));

        GeneralInitializationService.GeneralInitializationRunSummary summary = service.runAll();

        assertThat(summary.steps()).hasSize(7);
        assertThat(summary.failureCount()).isEqualTo(0);
        verify(adminUserService).createDefaultAdminUser();
        verify(artisticEventsService).importArtisticEventsFromJson();
        verify(urapRankingService).loadRankingsFromFolder("data/urap-univ");
        verify(cncsisService).importPublisherListFromExcelSync("data/cncsis/publisher_list.xlsx");
        verify(coreConferenceRankingService).loadRankingsFromCSVSync("data/core-conf");
        verify(senseRankingService).importBookRankingsFromExcelSync("data/sense/SENSE-rankings.xlsx");
    }

    @Test
    void specialDomainBootstrapCreatesAllDomainOnlyWhenMissing() {
        when(domainRepository.findById("all")).thenReturn(Optional.empty());

        GeneralInitializationService.GeneralInitializationStepResult step = service.runSpecialDomainBootstrap();

        assertThat(step.success()).isTrue();
        verify(domainRepository).save(any(Domain.class));
    }

    @Test
    void specialDomainBootstrapIsIdempotentWhenDomainExists() {
        when(domainRepository.findById("all")).thenReturn(Optional.of(new Domain()));

        GeneralInitializationService.GeneralInitializationStepResult step = service.runSpecialDomainBootstrap();

        assertThat(step.success()).isTrue();
        verify(domainRepository, never()).save(any(Domain.class));
    }

    private void setField(String fieldName, String value) throws Exception {
        Field field = GeneralInitializationService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }
}
