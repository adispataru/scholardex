package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole;
import ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.model.reporting.uefiscdi.CompetitionFamily;
import ro.uvt.pokedex.core.service.reporting.ScoringSubjectContext;
import ro.uvt.pokedex.core.service.reporting.UefiscdiDomainCatalogService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** H138 — the chosen competition domain replaces the journal indicators' domain and author role at scoring time. */
class CompetitionDomainOverrideTest {

    private static Indicator journal(String id) {
        Indicator i = new Indicator();
        i.setId(id);
        i.setName(id);
        i.setFormula("Q == \"Q1\" ? 1 : 0");
        i.setKind(new IndicatorKind.Publications(AuthorRole.ALL, ScoringStrategy.PD_WOS));
        Domain base = new Domain();
        base.setId(UefiscdiDomainCatalogService.NO_DOMAIN_ID);
        i.setDomain(base);
        return i;
    }

    private static Indicator core(String id, List<Integer> codes) {
        Indicator i = new Indicator();
        i.setId(id);
        i.setName(id);
        i.setFormula("category == \"A\" ? 1 : 0");
        i.setKind(new IndicatorKind.Publications(AuthorRole.FIRST_OR_CORRESPONDING, ScoringStrategy.CS_CONFERENCE));
        i.setCompetitionDomainCodes(codes);
        return i;
    }

    private static IndividualReport report(CompetitionFamily family) {
        IndividualReport r = new IndividualReport();
        r.setId("rep");
        r.setCompetitionFamily(family);
        return r;
    }

    private static CompetitionDomainOverride override() {
        UefiscdiDomainCatalogService catalog = mock(UefiscdiDomainCatalogService.class);
        when(catalog.domainOf(anyInt())).thenAnswer(inv -> {
            Domain d = new Domain();
            d.setId("UEFISCDI 2026 — domain " + inv.getArgument(0));
            d.setWosCategories(List.of("X - SCIE"));
            return Optional.of(d);
        });
        return new CompetitionDomainOverride(catalog);
    }

    @Test
    void theChosenDomainReplacesTheDomainAndTheAuthorRuleOfTheJournalIndicators() {
        Indicator q1 = journal("q1");
        List<Indicator> out = ScoringSubjectContext.withCompetitionDomain(9,   // Medicină: last author too
                () -> override().apply(report(CompetitionFamily.EXACT), List.of(q1)));
        assertNotSame(q1, out.getFirst(), "a copy, the stored definition is untouched");
        assertEquals("q1", out.getFirst().getId());
        assertEquals("UEFISCDI 2026 — domain 9", out.getFirst().getDomain().getId());
        assertEquals(AuthorRole.FIRST_CORRESPONDING_OR_LAST, ((IndicatorKind.Publications) out.getFirst().getKind()).role());
        assertEquals(AuthorRole.ALL, ((IndicatorKind.Publications) q1.getKind()).role());

        List<Indicator> maths = ScoringSubjectContext.withCompetitionDomain(1,
                () -> override().apply(report(CompetitionFamily.EXACT), List.of(q1)));
        assertEquals(AuthorRole.ALL, ((IndicatorKind.Publications) maths.getFirst().getKind()).role(), "6(e): all authors");
    }

    @Test
    void theCoreRouteAppliesUnderInformaticaOnly() {
        Indicator core = core("core", List.of(2));
        List<Indicator> info = ScoringSubjectContext.withCompetitionDomain(2, () -> override().apply(report(CompetitionFamily.EXACT), List.of(core)));
        assertSame(core, info.getFirst(), "under Informatică the conference indicator is used as stored");
        List<Indicator> maths = ScoringSubjectContext.withCompetitionDomain(1, () -> override().apply(report(CompetitionFamily.EXACT), List.of(core)));
        assertEquals("0.0", maths.getFirst().getFormula(), "elsewhere it yields nothing");
        assertEquals("core", maths.getFirst().getId());
    }

    @Test
    void withoutAChoiceTheJournalIndicatorsScoreAgainstTheEmptyDomain() {
        Indicator q1 = journal("q1");
        List<Indicator> out = override().apply(report(CompetitionFamily.EXACT), List.of(q1));
        assertEquals(UefiscdiDomainCatalogService.NO_DOMAIN_ID, out.getFirst().getDomain().getId());
        assertTrue(out.getFirst().getDomain().getWosCategories().isEmpty());
        assertEquals(AuthorRole.ALL, ((IndicatorKind.Publications) out.getFirst().getKind()).role(), "the stored role stays");
    }

    @Test
    void aDomainOfAnotherFamilyOrAReportWithoutAFamilyIsNotApplied() {
        Indicator q1 = journal("q1");
        List<Indicator> social = ScoringSubjectContext.withCompetitionDomain(11,
                () -> override().apply(report(CompetitionFamily.EXACT), List.of(q1)));
        assertEquals(UefiscdiDomainCatalogService.NO_DOMAIN_ID, social.getFirst().getDomain().getId(), "11 is not an EXACT domain");
        List<Indicator> plain = ScoringSubjectContext.withCompetitionDomain(2,
                () -> override().apply(report(null), List.of(q1)));
        assertSame(q1, plain.getFirst(), "a CNATDCU report passes through");
        assertTrue(override().chosenDomain(report(null)).isEmpty());
    }
}
