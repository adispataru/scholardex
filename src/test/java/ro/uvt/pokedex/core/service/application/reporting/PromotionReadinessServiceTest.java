package ro.uvt.pokedex.core.service.application.reporting;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitRunRollupService.MemberRunRow;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitRunRollupService.OrgUnitRunRollup;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitRunRollupService.RunSummary;
import ro.uvt.pokedex.core.model.user.User;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PromotionReadinessServiceTest {

    private final PromotionReadinessService service = new PromotionReadinessService();

    @Test
    void memberMeetingAllNextRungThresholdsLandsInMeets() {
        OrgUnitRunRollup rollup = rollup(
                List.of(row("ana@uvt.ro", Position.LECT_UNIV, Map.of(0, 40.0, 1, 50.0), false)),
                Map.of(0, Map.of("CONF_UNIV", 32.0), 1, Map.of("CONF_UNIV", 48.0)));

        var board = service.build(report(2), rollup);

        assertEquals(1, board.meets().size());
        var m = board.meets().get(0);
        assertEquals(Position.CONF_UNIV, m.targetPosition());
        assertEquals(2, m.metCount());
        assertEquals(2, m.applicableCount());
    }

    @Test
    void nearMissWithinTenPercentIsBorderlineWithGap() {
        // 30 vs threshold 32 → within 10% margin (28.8) → near-miss.
        OrgUnitRunRollup rollup = rollup(
                List.of(row("bob@uvt.ro", Position.LECT_UNIV, Map.of(0, 30.0), false)),
                Map.of(0, Map.of("CONF_UNIV", 32.0)));

        var board = service.build(report(1), rollup);

        assertEquals(1, board.borderline().size());
        var check = board.borderline().get(0).checks().get(0);
        assertFalse(check.met());
        assertTrue(check.near());
        assertEquals(2.0, check.gap(), 1e-9);
    }

    @Test
    void farBelowThresholdIsBuildingAndSortedByCloseness() {
        OrgUnitRunRollup rollup = rollup(List.of(
                row("far@uvt.ro", Position.LECT_UNIV, Map.of(0, 5.0, 1, 5.0), false),
                row("close@uvt.ro", Position.LECT_UNIV, Map.of(0, 40.0, 1, 20.0), false)),
                Map.of(0, Map.of("CONF_UNIV", 32.0), 1, Map.of("CONF_UNIV", 48.0)));

        var board = service.build(report(2), rollup);

        assertEquals(2, board.building().size());
        assertEquals("close@uvt.ro", board.building().get(0).row().user().getEmail());
        assertEquals("far@uvt.ro", board.building().get(1).row().user().getEmail());
    }

    @Test
    void criteriaWithoutTargetThresholdAreSkippedNotFailed() {
        // Criterion 1 has no CONF threshold (e.g. "Publicații de top" applies from CONF up only in
        // higher-rung columns) — an ASIST→LECT check must only see the LECT column.
        OrgUnitRunRollup rollup = rollup(
                List.of(row("asist@uvt.ro", Position.ASIST_UNIV, Map.of(0, 13.0, 1, 0.0), false)),
                Map.of(0, Map.of("LECT_UNIV", 12.0, "CONF_UNIV", 32.0),
                       1, Map.of("CONF_UNIV", 16.0)));

        var board = service.build(report(2), rollup);

        assertEquals(1, board.meets().size());
        assertEquals(1, board.meets().get(0).applicableCount());
        assertEquals(Position.LECT_UNIV, board.meets().get(0).targetPosition());
    }

    @Test
    void ladderEdgesAndMissingDataLandInNotEvaluable() {
        OrgUnitRunRollup rollup = rollup(List.of(
                row("prof@uvt.ro", Position.PROF_UNIV, Map.of(0, 99.0), false),
                row("unclassified@uvt.ro", null, Map.of(0, 99.0), false),
                row("other@uvt.ro", Position.OTHER, Map.of(0, 99.0), false),
                new MemberRunRow(user("norun@uvt.ro", Position.LECT_UNIV), "Dept", null, false, null),
                row("nostandard@uvt.ro", Position.CS_II, Map.of(0, 99.0), false)),
                Map.of(0, Map.of("CONF_UNIV", 32.0)));

        var board = service.build(report(1), rollup);

        assertEquals(5, board.notEvaluable().size());
        Map<String, PromotionReadinessService.Band> bands = new java.util.HashMap<>();
        board.notEvaluable().forEach(m -> bands.put(m.row().user().getEmail(), m.band()));
        assertEquals(PromotionReadinessService.Band.TOP_OF_LADDER, bands.get("prof@uvt.ro"));
        assertEquals(PromotionReadinessService.Band.UNCLASSIFIED, bands.get("unclassified@uvt.ro"));
        assertEquals(PromotionReadinessService.Band.UNCLASSIFIED, bands.get("other@uvt.ro"));
        assertEquals(PromotionReadinessService.Band.NO_RUN, bands.get("norun@uvt.ro"));
        // CS_II → CS_I exists on the ladder but the report has no CS_I thresholds.
        assertEquals(PromotionReadinessService.Band.NO_STANDARD, bands.get("nostandard@uvt.ro"));
    }

    @Test
    void habilitationCheckAppliesToEveryPositionWhenReportDefinesIt() {
        // Habilitation is a qualification, not a rung — a LECT gets the HABIL check too.
        OrgUnitRunRollup rollup = rollup(
                List.of(row("lect@uvt.ro", Position.LECT_UNIV, Map.of(0, 50.0), false)),
                Map.of(0, Map.of("CONF_UNIV", 32.0, "HABIL", 44.0)));

        var board = service.build(report(1), rollup);

        var m = board.meets().get(0);
        assertEquals(1, m.habilChecks().size());
        assertTrue(m.habilMet());
    }

    @Test
    void topOfLadderMembersStillCarryTheHabilitationCheck() {
        OrgUnitRunRollup rollup = rollup(
                List.of(row("prof@uvt.ro", Position.PROF_UNIV, Map.of(0, 40.0), false)),
                Map.of(0, Map.of("HABIL", 44.0)));

        var board = service.build(report(1), rollup);

        var m = board.notEvaluable().get(0);
        assertEquals(PromotionReadinessService.Band.TOP_OF_LADDER, m.band());
        assertEquals(1, m.habilChecks().size());
        assertFalse(m.habilMet());
    }

    @Test
    void confMemberGetsHabilitationSecondaryCheck() {
        OrgUnitRunRollup rollup = rollup(
                List.of(row("conf@uvt.ro", Position.CONF_UNIV, Map.of(0, 50.0), false)),
                Map.of(0, Map.of("PROF_UNIV", 56.0, "HABIL", 44.0)));

        var board = service.build(report(1), rollup);

        // 50 < 56 and below the 10% margin (50.4) → BUILDING vs PROF, but habilitation standards met.
        assertEquals(1, board.building().size());
        var m = board.building().get(0);
        assertEquals(1, m.habilChecks().size());
        assertTrue(m.habilMet());
    }

    @Test
    void provisionalRunsAreCountedForTheDisclaimer() {
        OrgUnitRunRollup rollup = rollup(
                List.of(row("prov@uvt.ro", Position.LECT_UNIV, Map.of(0, 40.0), true)),
                Map.of(0, Map.of("CONF_UNIV", 32.0)));

        var board = service.build(report(1), rollup);

        assertEquals(1, board.provisionalCount());
        assertEquals(1, board.meets().size());
    }

    // ------------------------------------------------------------------ helpers

    private static User user(String email, Position position) {
        User u = new User();
        u.setEmail(email);
        User.ResearcherProfile p = new User.ResearcherProfile();
        p.setFirstName("Test");
        p.setLastName(email.substring(0, email.indexOf('@')));
        p.setPosition(position);
        u.setResearcherProfile(p);
        return u;
    }

    private static MemberRunRow row(String email, Position position, Map<Integer, Double> scores,
                                    boolean provisional) {
        RunSummary run = new RunSummary("run-" + email, Instant.parse("2026-07-01T10:00:00Z"),
                ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun.Status.READY,
                provisional, scores, null, null, null, List.of());
        return new MemberRunRow(user(email, position), "Dept", run, false, null);
    }

    private static OrgUnitRunRollup rollup(List<MemberRunRow> rows,
                                           Map<Integer, Map<String, Double>> thresholds) {
        return new OrgUnitRunRollup(rows, thresholds, 0, 0, 0, null, null, null, null);
    }

    private static IndividualReport report(int criteriaCount) {
        IndividualReport r = new IndividualReport();
        r.setId("rep-1");
        r.setTitle("Test report");
        java.util.List<AbstractReport.Criterion> criteria = new java.util.ArrayList<>();
        for (int i = 0; i < criteriaCount; i++) {
            AbstractReport.Criterion c = new AbstractReport.Criterion();
            c.setName("C" + (i + 1));
            criteria.add(c);
        }
        r.setCriteria(criteria);
        return r;
    }
}
