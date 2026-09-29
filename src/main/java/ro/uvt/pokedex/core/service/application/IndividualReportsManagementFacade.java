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

    /**
     * Saves a report posted by the admin edit form. The form manages only part of a report, so the fields it
     * has no inputs for (perspectives, criterion weights, percent caps, threshold-cap additions) are first
     * carried over from the stored report — see {@link ReportFormCarryOver}. When the posted criteria or
     * indicators no longer sit at the positions those fields point at, nothing is saved and the result
     * carries the reason.
     */
    public FormSaveResult saveIndividualReportFromForm(IndividualReport posted) {
        if (posted.getId() != null && !posted.getId().isBlank()) {
            Optional<IndividualReport> stored = individualReportRepository.findById(posted.getId());
            if (stored.isPresent()) {
                Optional<String> refusal = ReportFormCarryOver.apply(stored.get(), posted);
                if (refusal.isPresent()) {
                    return new FormSaveResult(null, refusal.get());
                }
            }
        }
        return new FormSaveResult(individualReportRepository.save(posted), null);
    }

    /** Outcome of a form save: the saved report, or the reason the save was refused (exactly one is set). */
    public record FormSaveResult(IndividualReport saved, String refusalReason) {
        public boolean refused() {
            return refusalReason != null;
        }
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
