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
import java.util.ArrayList;
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
    private final EffectiveAuthorshipReadService effectiveAuthorshipReadService;
    private final ProfileLinkedAuthorResolutionService profileLinkedAuthorResolutionService;

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

        OrgUnitReportRefreshEvent event = newEvent(OrgUnitReportRefreshEvent.Mode.CONFIRMED,
                unitType, unitId, report.getId(), label, actorEmail, members.size(), durationMs);
        event.setRefreshed(refreshed);
        event.setFailed(failed);
        event.setSkippedProvisional(skippedProvisional);
        event.setSkippedFresh(skippedFresh);
        orgUnitReportRefreshEventRepository.save(event);

        log.info("refresh-all {} {} report {} ({}): {} refreshed, {} failed, {} provisional skipped, {} fresh skipped of {} in {} ms",
                unitType, unitId, report.getId(), scope, refreshed, failed, skippedProvisional, skippedFresh,
                members.size(), durationMs);
        return new RefreshAllResult(refreshed, failed, skippedProvisional, skippedFresh, members.size(), durationMs);
    }

    public record ProvisionalScoreResult(int scored, int failed, int skippedConfirmed, int unresolved,
                                         int rosterSize, long durationMs, List<String> unresolvedNames) {}

    /**
     * Provisionally score every roster member WITHOUT confirmed publications, resolving their
     * canonical authors from profile-linked identifiers (Scopus/WoS/Scholar/ORCID — never names)
     * and persisting provisional runs via the H77 machinery. Members with confirmed publications
     * are skipped — their CONFIRMED runs are authoritative. Candidates are always re-scored (the
     * action is explicit); members with no resolvable identifier get no run and are reported back.
     */
    public ProvisionalScoreResult scoreProvisionalUnlinked(OrgUnitReportRefreshEvent.UnitType unitType,
                                                           String unitId, String reportDefinitionId,
                                                           String label, String actorEmail) {
        IndividualReport report = individualReportRepository.findById(reportDefinitionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown report: " + reportDefinitionId));
        List<OrgUnitRosterService.RosterMember> members = resolveRoster(unitType, unitId);

        long startedAt = System.currentTimeMillis();
        int scored = 0;
        int failed = 0;
        int skippedConfirmed = 0;
        List<String> unresolvedNames = new ArrayList<>();
        for (OrgUnitRosterService.RosterMember member : members) {
            String email = member.user().getEmail();
            try {
                if (effectiveAuthorshipReadService.hasConfirmedPublicationsForScoring(email)) {
                    skippedConfirmed++;
                    continue;
                }
                List<String> authorIds = profileLinkedAuthorResolutionService
                        .resolveCanonicalAuthorIds(member.user().getResearcherProfile());
                if (authorIds.isEmpty()) {
                    unresolvedNames.add(displayName(member.user()));
                    continue;
                }
                boolean saved = userIndividualReportRunService
                        .buildAndSaveProvisionalRun(email, report.getId(), authorIds, actorEmail)
                        .isPresent();
                if (saved) scored++;
                else failed++;
            } catch (Exception ex) {
                failed++;
                log.warn("score-provisional {} {}: scoring failed for {}", unitType, unitId, email, ex);
            }
        }
        long durationMs = System.currentTimeMillis() - startedAt;

        OrgUnitReportRefreshEvent event = newEvent(OrgUnitReportRefreshEvent.Mode.PROVISIONAL,
                unitType, unitId, report.getId(), label, actorEmail, members.size(), durationMs);
        event.setRefreshed(scored);
        event.setFailed(failed);
        event.setSkippedConfirmed(skippedConfirmed);
        event.setUnresolved(unresolvedNames.size());
        orgUnitReportRefreshEventRepository.save(event);

        log.info("score-provisional {} {} report {}: {} scored, {} failed, {} confirmed skipped, {} unresolved of {} in {} ms",
                unitType, unitId, report.getId(), scored, failed, skippedConfirmed, unresolvedNames.size(),
                members.size(), durationMs);
        return new ProvisionalScoreResult(scored, failed, skippedConfirmed, unresolvedNames.size(),
                members.size(), durationMs, unresolvedNames);
    }

    private static OrgUnitReportRefreshEvent newEvent(OrgUnitReportRefreshEvent.Mode mode,
                                                      OrgUnitReportRefreshEvent.UnitType unitType, String unitId,
                                                      String reportDefinitionId, String label, String actorEmail,
                                                      int rosterSize, long durationMs) {
        OrgUnitReportRefreshEvent event = new OrgUnitReportRefreshEvent();
        event.setMode(mode);
        event.setUnitType(unitType);
        event.setUnitId(unitId);
        event.setReportDefinitionId(reportDefinitionId);
        event.setCreatedAt(Instant.now());
        event.setTriggeredByEmail(actorEmail);
        event.setLabel(label == null || label.isBlank() ? null : label.trim());
        event.setRosterSize(rosterSize);
        event.setDurationMs(durationMs);
        return event;
    }

    private static String displayName(ro.uvt.pokedex.core.model.user.User user) {
        String name = user.getResearcherProfile() == null ? "" : user.getResearcherProfile().getName();
        String trimmed = name == null ? "" : name.trim();
        return trimmed.isBlank() ? user.getEmail() : trimmed;
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
