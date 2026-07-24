package ro.uvt.pokedex.core.service.application;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.observability.StartupReadinessTracker;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.service.importing.AdminUserService;
import ro.uvt.pokedex.core.service.importing.ArtisticEventsService;
import ro.uvt.pokedex.core.service.importing.CNCSISService;
import ro.uvt.pokedex.core.service.importing.CoreConferenceRankingService;
import ro.uvt.pokedex.core.service.importing.SenseRankingService;
import ro.uvt.pokedex.core.service.importing.URAPRankingService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class GeneralInitializationService {

    private static final String METRIC_STARTUP_PHASE_DURATION = "core.startup.phase.duration";

    private final AdminUserService adminUserService;
    private final ArtisticEventsService artisticEventsService;
    private final URAPRankingService urapRankingService;
    private final ro.uvt.pokedex.core.service.importing.UniversityRankingCsvService universityRankingCsvService;
    private final CNCSISService cncsisService;
    private final CoreConferenceRankingService coreConferenceRankingService;
    private final SenseRankingService senseRankingService;
    private final ro.uvt.pokedex.core.service.dblp.DblpDumpConferenceSweepService dblpDumpConferenceSweepService;
    private final DomainRepository domainRepository;
    private final MeterRegistry meterRegistry;
    private final StartupReadinessTracker startupReadinessTracker;

    @Value("${general.init.urap.folder:data/urap-univ}")
    private String urapFolderPath;

    @Value("${general.init.arwu.folder:data/arwu}")
    private String arwuFolderPath;

    @Value("${general.init.cncsis.file:data/cncsis/publisher_list.xlsx}")
    private String cncsisFilePath;

    @Value("${general.init.core-conference.folder:data/core-conf}")
    private String coreConferenceFolderPath;

    @Value("${general.init.sense.file:data/sense/SENSE-rankings.xlsx}")
    private String senseFilePath;

    public GeneralInitializationRunSummary runAll() {
        List<GeneralInitializationStepResult> steps = new ArrayList<>();
        steps.add(runAdminUserBootstrap());
        steps.add(runSpecialDomainBootstrap());
        steps.add(runArtisticEventsImport());
        steps.add(runUrapImport());
        steps.add(runCncsisImport());
        steps.add(runCoreConferenceImport());
        steps.add(runSenseImport());
        return new GeneralInitializationRunSummary("run-all", Instant.now(), steps);
    }

    public GeneralInitializationStepResult runAdminUserBootstrap() {
        return runStep("admin-user", true, "default-admin-user", () -> {
            adminUserService.createDefaultAdminUser();
            return "admin user bootstrap completed";
        });
    }

    public GeneralInitializationStepResult runSpecialDomainBootstrap() {
        return runStep("domain-bootstrap", true, "special-domain-all", () -> {
            createSpecialDomainIfNotExist();
            return "special domain bootstrap completed";
        });
    }

    public GeneralInitializationStepResult runArtisticEventsImport() {
        return runStep("artistic-events-import", false, "artistic-events", () -> {
            artisticEventsService.importArtisticEventsFromJson();
            return "artistic events import completed";
        });
    }

    public GeneralInitializationStepResult runUrapImport() {
        return runStep("urap-import", false, "urap", () -> {
            urapRankingService.loadRankingsFromFolder(urapFolderPath);
            return "urap import completed from " + urapFolderPath;
        });
    }

    public GeneralInitializationStepResult runArwuImport() {
        return runStep("arwu-import", false, "arwu", () -> {
            universityRankingCsvService.loadSourceFromFolder("ARWU", arwuFolderPath, "ARWU");
            return "arwu import completed from " + arwuFolderPath;
        });
    }

    public GeneralInitializationStepResult runCncsisImport() {
        return runStep("cncsis-import", false, "cncsis", () -> {
            cncsisService.importPublisherListFromExcelSync(cncsisFilePath);
            return "cncsis import completed from " + cncsisFilePath;
        });
    }

    public GeneralInitializationStepResult runCoreConferenceImport() {
        return runStep("core-conference-import", false, "core-conference", () -> {
            coreConferenceRankingService.loadRankingsFromCSVSync(coreConferenceFolderPath);
            return "core conference import completed from " + coreConferenceFolderPath;
        });
    }

    public GeneralInitializationStepResult runSenseImport() {
        return runStep("sense-import", false, "sense", () -> {
            senseRankingService.importBookRankingsFromExcelSync(senseFilePath);
            return "sense import completed from " + senseFilePath;
        });
    }

    public GeneralInitializationStepResult runDblpLnChapterEnrichment() {
        // H66B Phase 4b: this admin step runs the corpus-matched DBLP dump sweep — the rate-limit-free BULK path that
        // streams the configured dump once, matching our hidden-conference candidates (mints conf/X forums + writes
        // scorer-compatible evidence). The per-paper API path covers on-demand syncs + papers newer than the dump.
        return runStep("dblp-conference-resolve", false, "dblp-conference-resolve", () -> {
            var result = dblpDumpConferenceSweepService.sweep();
            return "dblp dump sweep completed: resolved=" + result.getImportedCount()
                    + " skipped=" + result.getSkippedCount();
        });
    }

    private void createSpecialDomainIfNotExist() {
        if (domainRepository.findByName("ALL").isPresent()) {
            return;
        }
        Domain domain = new Domain();
        domain.setName("ALL");
        domain.setDescription("Special domain to consider all WoS domains");
        domain.getWosCategories().add("*");
        domainRepository.save(domain);
    }

    private GeneralInitializationStepResult runStep(
            String phase,
            boolean critical,
            String stepKey,
            Supplier<String> action
    ) {
        startupReadinessTracker.phaseStart(phase, critical);
        long startedAtNanos = System.nanoTime();
        Instant startedAt = Instant.now();
        try {
            String details = action.get();
            long durationMs = nanosToMillis(System.nanoTime() - startedAtNanos);
            startupReadinessTracker.phaseSuccess(phase, durationMs);
            meterRegistry.timer(
                            METRIC_STARTUP_PHASE_DURATION,
                            "phase", phase,
                            "critical", Boolean.toString(critical),
                            "outcome", "success"
                    )
                    .record(durationMs, TimeUnit.MILLISECONDS);
            return new GeneralInitializationStepResult(
                    stepKey,
                    true,
                    critical,
                    durationMs,
                    startedAt,
                    Instant.now(),
                    details
            );
        } catch (RuntimeException ex) {
            long durationMs = nanosToMillis(System.nanoTime() - startedAtNanos);
            startupReadinessTracker.phaseFailure(phase, durationMs, ex.getMessage());
            meterRegistry.timer(
                            METRIC_STARTUP_PHASE_DURATION,
                            "phase", phase,
                            "critical", Boolean.toString(critical),
                            "outcome", "failure"
                    )
                    .record(durationMs, TimeUnit.MILLISECONDS);
            return new GeneralInitializationStepResult(
                    stepKey,
                    false,
                    critical,
                    durationMs,
                    startedAt,
                    Instant.now(),
                    ex.getMessage()
            );
        }
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    public record GeneralInitializationStepResult(
            String step,
            boolean success,
            boolean critical,
            long durationMs,
            Instant startedAt,
            Instant completedAt,
            String message
    ) {
    }

    public record GeneralInitializationRunSummary(
            String operation,
            Instant completedAt,
            List<GeneralInitializationStepResult> steps
    ) {
        public long successCount() {
            return steps.stream().filter(GeneralInitializationStepResult::success).count();
        }

        public long failureCount() {
            return steps.size() - successCount();
        }
    }
}
