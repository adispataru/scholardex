package ro.uvt.pokedex.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.observability.StartupReadinessTracker;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.importing.*;

import java.util.Optional;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataLoaderNew {

    @Value("${scopus.data.file}")
    private String scopusDataFile;

    private final AdminUserService adminUserService;

    private final ScopusDataService scopusDataService;

    private final RankingService rankingService;

    private final CoreConferenceRankingService coreConferenceRankingService;
    private final SenseRankingService senseRankingService;

    private final ArtisticEventsService artisticEventService;
    private final URAPRankingService urapRankingService;
    private final CNCSISService cncsisService;
    private final MeterRegistry meterRegistry;
    private final StartupReadinessTracker startupReadinessTracker;

    private final DomainRepository domainRepository;
    private final boolean resetWosRankings = false;
    private final boolean updateWosRankings = false;
    private final boolean addPublications = false;

    @Bean
    CommandLineRunner initDatabase(CacheService cacheService) {
        return args -> {
            runCriticalPhase("admin-user", () -> {
                adminUserService.createDefaultAdminUser();
                return null;
            });
            runCriticalPhase("scopus-data-load", () -> {
                scopusDataService.loadScopusDataIfEmpty(scopusDataFile);
                return null;
            });
            runCriticalPhase("domain-bootstrap", () -> {
                createSpecialDomainIfNotExist();
                return null;
            });
            runOptionalPhase("artistic-events-import", () -> {
                artisticEventService.importArtisticEventsFromJson();
                return null;
            });

            if(addPublications){
                scopusDataService.loadAdditionalScopusData(scopusDataFile);
            }

            if(resetWosRankings){
                rankingService.deleteWosRankings();
                rankingService.initializeCategoriesFromExcel("/Users/adispataru/Documents/programming/demo-exam/core/data/AIS_2022.xlsx", "uefiscdi");
                rankingService.loadRankingsFromExcel("/Users/adispataru/Documents/programming/demo-exam/core/data/", "uefiscdi");
            }
            if(updateWosRankings && !resetWosRankings){
                rankingService.loadRankingsFromExcel("/Users/adispataru/Documents/programming/demo-exam/core/data/", "uefiscdi");
            }



            // Uncomment these methods to load additional data if necessary
//            coreConferenceRankingService.loadRankingsFromCSV("/Users/adispataru/Documents/programming/demo-exam/core/data/core-conf");
//            rankingService.updateImpactFactorsFromExcel("/Users/adispataru/Documents/programming/demo-exam/core/data/jcr2022.xlsx");
//            senseRankingService.importBookRankingsFromExcel("/data/sense/SENSE-rankings.xlsx");
            runOptionalPhase("urap-import", () -> {
                urapRankingService.loadRankingsFromFolder("data/urap-univ");
                return null;
            });
            runOptionalPhase("cncsis-import", () -> {
                cncsisService.importPublisherListFromExcel("data/cncsis/publisher_list.xlsx");
                return null;
            });
        };
    }

    public void createSpecialDomainIfNotExist() {
        Optional<Domain> all = domainRepository.findById("all");
        if (all.isEmpty()) {
            Domain domain = new Domain();
            domain.setName("ALL");
            domain.setDescription("Special domain to consider all WoS domains");
            domain.getWosCategories().add("*");
            domainRepository.save(domain);
        }
    }

    private <T> T runCriticalPhase(String phase, Supplier<T> action) {
        return runPhase(phase, true, action);
    }

    private <T> T runOptionalPhase(String phase, Supplier<T> action) {
        try {
            return runPhase(phase, false, action);
        } catch (RuntimeException ex) {
            log.warn("Optional startup phase failed: phase={}, message={}", phase, ex.getMessage());
            return null;
        }
    }

    private <T> T runPhase(String phase, boolean critical, Supplier<T> action) {
        startupReadinessTracker.phaseStart(phase, critical);
        long startedAt = System.currentTimeMillis();
        try {
            T result = action.get();
            long durationMs = System.currentTimeMillis() - startedAt;
            startupReadinessTracker.phaseSuccess(phase, durationMs);
            meterRegistry.timer("core.startup.phase.duration",
                            "phase", phase,
                            "critical", Boolean.toString(critical),
                            "outcome", "success")
                    .record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return result;
        } catch (RuntimeException ex) {
            long durationMs = System.currentTimeMillis() - startedAt;
            startupReadinessTracker.phaseFailure(phase, durationMs, ex.getMessage());
            meterRegistry.timer("core.startup.phase.duration",
                            "phase", phase,
                            "critical", Boolean.toString(critical),
                            "outcome", "failure")
                    .record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            throw ex;
        }
    }
}
