package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.OrgUnitReportRefreshEvent;
import ro.uvt.pokedex.core.model.reporting.ReportingDataEpoch;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.reporting.OrgUnitReportRefreshEventRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Admin "Refresh all" for an org-unit report roll-up: sequentially refreshes each roster member's
 * run through the user-run path ({@link UserIndividualReportRunService#refreshRunWithAllIndicators}),
 * so the roll-up and the researcher's own workspace always agree. Skips members whose latest run is
 * provisional (a CONFIRMED refresh would bury the H77 provisional numbers under an all-zeros run) and,
 * with the default {@link Scope#STALE}, members whose run already post-dates the last data rebuild.
 * Each batch persists an {@link OrgUnitReportRefreshEvent} for auditing and the delta compare picker.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrgUnitReportRefreshService {

    private final OrgUnitRosterService orgUnitRosterService;
    private final IndividualReportRepository individualReportRepository;
    private final UserIndividualReportRunRepository userIndividualReportRunRepository;
    private final UserIndividualReportRunService userIndividualReportRunService;
    private final ReportingDataEpochService reportingDataEpochService;
    private final OrgUnitReportRefreshEventRepository orgUnitReportRefreshEventRepository;

    public enum Scope {
        /** Refresh only members with no run, a stale run, or a run predating the last data rebuild. */
        STALE,
        /** Refresh every non-provisional member. */
        ALL
    }

    public record RefreshAllResult(int refreshed, int failed, int skippedProvisional, int skippedFresh,
                                   int rosterSize, long durationMs) {}

    public RefreshAllResult refreshAll(OrgUnitReportRefreshEvent.UnitType unitType, String unitId,
                                       String reportDefinitionId, Scope scope, String label, String actorEmail) {
        IndividualReport report = individualReportRepository.findById(reportDefinitionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown report: " + reportDefinitionId));
        List<OrgUnitRosterService.RosterMember> members = resolveRoster(unitType, unitId);
        Instant epochUpdatedAt = reportingDataEpochService.currentEpochInfo()
                .map(ReportingDataEpoch::getUpdatedAt).orElse(null);

        long startedAt = System.currentTimeMillis();
        int refreshed = 0;
        int failed = 0;
        int skippedProvisional = 0;
        int skippedFresh = 0;
        for (OrgUnitRosterService.RosterMember member : members) {
            String email = member.user().getEmail();
            Optional<UserIndividualReportRun> latest = userIndividualReportRunRepository
                    .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(email, report.getId());
            if (latest.isPresent() && latest.get().isProvisional()) {
                skippedProvisional++;
                continue;
            }
            if (scope == Scope.STALE && latest.isPresent() && !isStale(latest.get(), epochUpdatedAt)) {
                skippedFresh++;
                continue;
            }
            try {
                userIndividualReportRunService.refreshRunWithAllIndicators(email, report.getId(), actorEmail);
                refreshed++;
            } catch (Exception ex) {
                failed++;
                log.warn("refresh-all {} {}: refresh failed for {}", unitType, unitId, email, ex);
            }
        }
        long durationMs = System.currentTimeMillis() - startedAt;

        OrgUnitReportRefreshEvent event = new OrgUnitReportRefreshEvent();
        event.setUnitType(unitType);
        event.setUnitId(unitId);
        event.setReportDefinitionId(report.getId());
        event.setCreatedAt(Instant.now());
        event.setTriggeredByEmail(actorEmail);
        event.setLabel(label == null || label.isBlank() ? null : label.trim());
        event.setRefreshed(refreshed);
        event.setFailed(failed);
        event.setSkippedProvisional(skippedProvisional);
        event.setSkippedFresh(skippedFresh);
        event.setRosterSize(members.size());
        event.setDurationMs(durationMs);
        orgUnitReportRefreshEventRepository.save(event);

        log.info("refresh-all {} {} report {} ({}): {} refreshed, {} failed, {} provisional skipped, {} fresh skipped of {} in {} ms",
                unitType, unitId, report.getId(), scope, refreshed, failed, skippedProvisional, skippedFresh,
                members.size(), durationMs);
        return new RefreshAllResult(refreshed, failed, skippedProvisional, skippedFresh, members.size(), durationMs);
    }

    private List<OrgUnitRosterService.RosterMember> resolveRoster(
            OrgUnitReportRefreshEvent.UnitType unitType, String unitId) {
        return switch (unitType) {
            case DIVISION -> orgUnitRosterService.divisionRoster(unitId);
            case DEPARTMENT -> orgUnitRosterService.departmentRoster(unitId);
            case GROUP -> orgUnitRosterService.groupRoster(unitId);
        };
    }

    private static boolean isStale(UserIndividualReportRun run, Instant epochUpdatedAt) {
        if (run.getCreatedAt() == null) return true;
        return epochUpdatedAt != null && run.getCreatedAt().isBefore(epochUpdatedAt);
    }
}
