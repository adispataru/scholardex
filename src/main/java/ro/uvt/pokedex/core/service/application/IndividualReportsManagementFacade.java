package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class IndividualReportsManagementFacade {

    private final IndividualReportRepository individualReportRepository;
    private final IndicatorRepository indicatorRepository;
    private final InstitutionRepository institutionRepository;

    public List<IndividualReport> listIndividualReports() {
        return individualReportRepository.findAll();
    }

    public List<Indicator> listIndicatorsSortedByName() {
        List<Indicator> indicators = new java.util.ArrayList<>(indicatorRepository.findAll());
        indicators.sort(Comparator.comparing(Indicator::getName));
        return indicators;
    }

    public List<Indicator> listIndicators() {
        return indicatorRepository.findAll();
    }

    public List<Institution> listInstitutions() {
        return institutionRepository.findAll();
    }

    public IndividualReport saveIndividualReport(IndividualReport individualReport) {
        return individualReportRepository.save(individualReport);
    }

    public IndividualReport findIndividualReportRequired(String id) {
        return individualReportRepository.findById(id).orElseThrow();
    }

    public Optional<IndividualReport> findIndividualReport(String id) {
        return individualReportRepository.findById(id);
    }

    public void deleteIndividualReport(String id) {
        individualReportRepository.deleteById(id);
    }

    public Optional<IndividualReport> duplicateIndividualReport(String id) {
        return individualReportRepository.findById(id).map(individualReport -> {
            individualReport.setId(null);
            individualReport.setTitle(individualReport.getTitle() + " (Copy)");
            return individualReportRepository.save(individualReport);
        });
    }
}
