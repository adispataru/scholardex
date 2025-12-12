package ro.uvt.pokedex.core.repository.reporting;


import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import ro.uvt.pokedex.core.model.reporting.GroupReport;

@Repository
public interface GroupReportRepository extends MongoRepository<GroupReport, String> {
    // Additional query methods can be defined here if needed
}
