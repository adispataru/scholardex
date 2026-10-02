package ro.uvt.pokedex.core.service.application;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.service.reporting.UefiscdiCompetitionDomains;

import java.util.List;
import java.util.Optional;

/** H138 — the competition domains as the pages need them (Z1 must not reach the reporting catalog directly). */
@Service
public class CompetitionDomainFacade {

    /** One domain of the "Domeniul propunerii" select. */
    public record DomainChoice(int code, String name) {
    }

    /** The domains a domain-selectable report may be scored under; empty for every other report. */
    public List<DomainChoice> choicesFor(IndividualReport report) {
        if (report == null || report.getCompetitionFamily() == null) {
            return List.of();
        }
        return UefiscdiCompetitionDomains.all().stream()
                .filter(d -> d.family() == report.getCompetitionFamily())
                .map(d -> new DomainChoice(d.code(), d.name()))
                .toList();
    }

    /** Whether {@code code} names a domain of the report's family. */
    public boolean isChoiceOf(IndividualReport report, int code) {
        return choicesFor(report).stream().anyMatch(c -> c.code() == code);
    }

    public Optional<String> nameOf(Integer code) {
        return code == null ? Optional.empty() : UefiscdiCompetitionDomains.byCode(code).map(UefiscdiCompetitionDomains.CompetitionDomain::name);
    }
}
