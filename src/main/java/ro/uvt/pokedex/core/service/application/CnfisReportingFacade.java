package ro.uvt.pokedex.core.service.application;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.service.application.model.CnfisEditionViewModel;
import ro.uvt.pokedex.core.service.reporting.CnfisEdition;

import java.util.Comparator;
import java.util.List;

/** H129 — what the CNFIS page reads: the editions of the reporting, the newest first. */
@Service
public class CnfisReportingFacade {

    public List<CnfisEditionViewModel> editions() {
        return CnfisEdition.known().stream()
                .sorted(Comparator.comparingInt(CnfisEdition::reportingYear).reversed())
                .map(e -> new CnfisEditionViewModel(e.reportingYear(), e.windowStart(), e.windowEnd(),
                        e.lastListYear(), e.provisional()))
                .toList();
    }
}
