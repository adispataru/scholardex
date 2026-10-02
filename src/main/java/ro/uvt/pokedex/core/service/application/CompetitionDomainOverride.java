package ro.uvt.pokedex.core.service.application;

import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.service.reporting.ScoringSubjectContext;
import ro.uvt.pokedex.core.service.reporting.UefiscdiCompetitionDomains;
import ro.uvt.pokedex.core.service.reporting.UefiscdiDomainCatalogService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * H138 — applies the researcher's chosen competition domain to the indicators of a domain-selectable UEFISCDI
 * report before they are scored: every WoS-journal indicator (PD_WOS) is scored against the chosen domain's
 * category set with the domain's principal-author rule, and an indicator that applies only under some domains
 * (the CORE A/A* route under Informatică) scores nothing elsewhere. Without a choice the journal indicators score
 * against an EMPTY category set — nothing qualifies — so the page's "choose a domain" gate is also a scoring
 * fact, not only a display one. Reports without a competition family pass through untouched.
 *
 * <p>Indicators are copied, never mutated: the stored definitions keep their base domain, and the copies keep
 * their ids so results, criteria indices and the drilldowns resolve as before.</p>
 */
@Service
public class CompetitionDomainOverride {


    private final UefiscdiDomainCatalogService catalog;

    public CompetitionDomainOverride(UefiscdiDomainCatalogService catalog) {
        this.catalog = catalog;
    }

    /** The chosen domain of the subject in scope, when it belongs to the report's family. */
    public Optional<UefiscdiCompetitionDomains.CompetitionDomain> chosenDomain(IndividualReport report) {
        if (report == null || report.getCompetitionFamily() == null) {
            return Optional.empty();
        }
        Integer code = ScoringSubjectContext.competitionDomainCode();
        if (code == null) {
            return Optional.empty();
        }
        return UefiscdiCompetitionDomains.byCode(code).filter(d -> d.family() == report.getCompetitionFamily());
    }

    /** The report's indicators as they must be scored for the subject in scope. */
    public List<Indicator> apply(IndividualReport report, List<Indicator> indicators) {
        if (report == null || report.getCompetitionFamily() == null || indicators == null) {
            return indicators;
        }
        Optional<UefiscdiCompetitionDomains.CompetitionDomain> chosen = chosenDomain(report);
        Domain scoringDomain = chosen.flatMap(d -> catalog.domainOf(d.code())).orElseGet(CompetitionDomainOverride::noDomain);
        List<Indicator> out = new ArrayList<>(indicators.size());
        for (Indicator indicator : indicators) {
            out.add(indicator == null ? null : override(indicator, chosen.orElse(null), scoringDomain));
        }
        return out;
    }

    private static Indicator override(Indicator indicator, UefiscdiCompetitionDomains.CompetitionDomain chosen, Domain scoringDomain) {
        boolean applicable = indicator.getCompetitionDomainCodes() == null
                || (chosen != null && indicator.getCompetitionDomainCodes().contains(chosen.code()));
        boolean journalIndicator = indicator.getKind() instanceof IndicatorKind.Publications p
                && p.strategy() == ScoringStrategy.PD_WOS;
        if (applicable && !journalIndicator) {
            return indicator;
        }
        Indicator copy = new Indicator();
        BeanUtils.copyProperties(indicator, copy);
        if (!applicable) {
            // not this domain's route: the indicator yields nothing (the formula is the cheapest place to say so)
            copy.setFormula("0.0");
            return copy;
        }
        copy.setDomain(scoringDomain);
        if (chosen != null) {
            IndicatorKind.Publications kind = (IndicatorKind.Publications) indicator.getKind();
            copy.setKind(new IndicatorKind.Publications(chosen.principalAuthorRule().authorRole(), kind.strategy()));
        }
        return copy;
    }

    private static Domain noDomain() {
        Domain domain = new Domain();
        domain.setId(UefiscdiDomainCatalogService.NO_DOMAIN_ID);
        domain.setName(UefiscdiDomainCatalogService.NO_DOMAIN_ID);
        domain.setDescription("H138: a domain-selectable report scored before the researcher chose a domain — nothing qualifies");
        domain.setWosCategories(List.of());
        return domain;
    }
}
