package ro.uvt.pokedex.core;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.importing.*;

import java.util.Optional;

@Component
@RequiredArgsConstructor
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

    private final DomainRepository domainRepository;
    private final boolean resetWosRankings = false;
    private final boolean updateWosRankings = false;
    private final boolean addPublications = false;

    @Bean
    CommandLineRunner initDatabase(CacheService cacheService) {
        return args -> {
            adminUserService.createDefaultAdminUser();
            scopusDataService.loadScopusDataIfEmpty(scopusDataFile);
            createSpecialDomainIfNotExist();
            artisticEventService.importArtisticEventsFromJson();

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
            urapRankingService.loadRankingsFromFolder("data/urap-univ");
            cncsisService.importPublisherListFromExcel("data/cncsis/publisher_list.xlsx");
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
}
