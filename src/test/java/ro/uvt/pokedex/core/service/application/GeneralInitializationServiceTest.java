package ro.uvt.pokedex.core.service.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;

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
    private DblpPublicationEnrichmentService dblpPublicationEnrichmentService;
    @Mock
    private DomainRepository domainRepository;
    @Mock
    private MeterRegistry meterRegistry;
    @Mock
    private StartupReadinessTracker startupReadinessTracker;
    @Mock
    private Timer timer;

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
                dblpPublicationEnrichmentService,
                domainRepository,
                meterRegistry,
                startupReadinessTracker
        );
        when(meterRegistry.timer(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(timer);
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
    void dblpLnChapterEnrichmentDelegatesToConfiguredService() {
        when(dblpPublicationEnrichmentService.runConfiguredEnrichment())
                .thenReturn(new DblpPublicationEnrichmentService.DblpEnrichmentRunSummary(
                        "/tmp/dblp.xml.gz", "march-2026", 3, 42L, 1, 1, 0, 1, 0, 0
                ));

        GeneralInitializationService.GeneralInitializationStepResult step = service.runDblpLnChapterEnrichment();

        assertThat(step.success()).isTrue();
        assertThat(step.message()).contains("march-2026");
        verify(dblpPublicationEnrichmentService).runConfiguredEnrichment();
    }

    @Test
    void specialDomainBootstrapCreatesAllDomainOnlyWhenMissing() {
        when(domainRepository.findById("all")).thenReturn(Optional.empty());

        GeneralInitializationService.GeneralInitializationStepResult step = service.runSpecialDomainBootstrap();

        assertThat(step.success()).isTrue();
        org.mockito.ArgumentCaptor<Domain> domainCaptor = org.mockito.ArgumentCaptor.forClass(Domain.class);
        verify(domainRepository).save(domainCaptor.capture());
        Domain saved = domainCaptor.getValue();
        assertThat(saved.getName()).isEqualTo("ALL");
        assertThat(saved.getDescription()).isEqualTo("Special domain to consider all WoS domains");
        assertThat(saved.getWosCategories()).containsExactly("*");
        verify(startupReadinessTracker).phaseStart("domain-bootstrap", true);
        verify(startupReadinessTracker).phaseSuccess(eq("domain-bootstrap"), anyLong());
        verify(timer).record(anyLong(), eq(java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    @Test
    void specialDomainBootstrapIsIdempotentWhenDomainExists() {
        when(domainRepository.findById("all")).thenReturn(Optional.of(new Domain()));

        GeneralInitializationService.GeneralInitializationStepResult step = service.runSpecialDomainBootstrap();

        assertThat(step.success()).isTrue();
        verify(domainRepository, never()).save(any(Domain.class));
    }

    @Test
    void runStepFailureIsReportedAsNonSuccess() {
        doThrow(new IllegalStateException("boom")).when(cncsisService).importPublisherListFromExcelSync("data/cncsis/publisher_list.xlsx");

        GeneralInitializationService.GeneralInitializationStepResult step = service.runCncsisImport();

        assertThat(step.success()).isFalse();
        assertThat(step.message()).contains("boom");
        assertThat(step.durationMs()).isGreaterThanOrEqualTo(0);
        verify(startupReadinessTracker).phaseStart("cncsis-import", false);
        verify(startupReadinessTracker).phaseFailure(eq("cncsis-import"), anyLong(), eq("boom"));
        verify(timer).record(anyLong(), eq(java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    @Test
    void runAllCountsFailuresWhenAStepThrows() {
        when(domainRepository.findById("all")).thenReturn(Optional.of(new Domain()));
        doThrow(new IllegalStateException("urap fail")).when(urapRankingService).loadRankingsFromFolder("data/urap-univ");

        GeneralInitializationService.GeneralInitializationRunSummary summary = service.runAll();

        assertThat(summary.failureCount()).isEqualTo(1);
        assertThat(summary.successCount()).isEqualTo(6);
        verify(startupReadinessTracker, times(7)).phaseStart(anyString(), anyBoolean());
    }

    private void setField(String fieldName, String value) throws Exception {
        Field field = GeneralInitializationService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }
}
