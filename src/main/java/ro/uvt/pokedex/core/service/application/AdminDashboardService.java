package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.model.tasks.Status;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.repository.tasks.ScopusCitationUpdateRepository;
import ro.uvt.pokedex.core.repository.tasks.ScopusPublicationUpdateRepository;
import ro.uvt.pokedex.core.service.application.model.AdminActivityEvent;
import ro.uvt.pokedex.core.service.application.model.AdminDashboardViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminOperationStatus;
import ro.uvt.pokedex.core.service.application.model.WosEnrichmentRunSummaryDto;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

@Service
@Slf4j
@RequiredArgsConstructor
public class AdminDashboardService {

    private static final String SOURCE_USER_DEFINED = "USER_DEFINED";
    private static final String CONFLICT_STATUS_OPEN = "OPEN";

    private final ScholardexIdentityConflictRepository identityConflictRepository;
    private final ScholardexSourceLinkRepository sourceLinkRepository;
    private final UserRepository userRepository;
    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final ScholardexForumFactRepository forumFactRepository;
    private final RankingMaintenanceFacade rankingMaintenanceFacade;
    private final ScopusPublicationUpdateRepository scopusPublicationUpdateRepository;
    private final ScopusCitationUpdateRepository scopusCitationUpdateRepository;

    public AdminDashboardViewModel buildDashboard() {
        List<String> errors = new ArrayList<>();

        long openConflictCount = safeCount(
                () -> identityConflictRepository.countByStatus(CONFLICT_STATUS_OPEN),
                errors, "openConflictCount");

        long pendingTriageCount = safeCount(
                () -> identityConflictRepository.countByIncomingSourceAndStatus(SOURCE_USER_DEFINED, CONFLICT_STATUS_OPEN)
                        + sourceLinkRepository.countBySourceAndLinkState(SOURCE_USER_DEFINED, ScholardexSourceLinkService.STATE_CONFLICT),
                errors, "pendingTriageCount");

        long totalResearchers = safeCount(
                () -> (long) userRepository.findAllByResearcherProfileIsNotNull().size(),
                errors, "totalResearchers");

        long totalPublications = safeCount(publicationFactRepository::count, errors, "totalPublications");

        long totalForums = safeCount(forumFactRepository::count, errors, "totalForums");

        AdminOperationStatus wosEnrichmentStatus = safeOp(this::buildWosStatus, errors, "wosEnrichment");

        AdminOperationStatus incrementalUpdateStatus = safeOp(this::buildIncrementalStatus, errors, "incrementalUpdate");

        List<AdminActivityEvent> recentActivity = safeList(this::buildRecentActivity, errors, "recentActivity");

        return new AdminDashboardViewModel(
                openConflictCount,
                pendingTriageCount,
                totalResearchers,
                totalPublications,
                totalForums,
                wosEnrichmentStatus,
                AdminOperationStatus.neverRun(),
                incrementalUpdateStatus,
                recentActivity,
                errors
        );
    }

    public record PublicationCatalogStats(long total, long recentlyAdded) {}

    public PublicationCatalogStats buildPublicationCatalogStats() {
        long total = publicationFactRepository.count();
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        long recentlyAdded = publicationFactRepository.countByCreatedAtAfter(cutoff);
        return new PublicationCatalogStats(total, recentlyAdded);
    }

    public AdminOperationStatus buildCitationSyncStatus() {
        List<ScopusCitationsUpdate> all = scopusCitationUpdateRepository
                .findAll(Sort.by(Sort.Direction.DESC, "initiatedDate"));
        if (all.isEmpty()) {
            return AdminOperationStatus.neverRun();
        }
        ScopusCitationsUpdate last = all.get(0);
        Instant ts = parseDate(last.getInitiatedDate());
        String outcome = mapTaskOutcome(last.getStatus());
        return new AdminOperationStatus(ts, outcome, last.getMessage());
    }

    private AdminOperationStatus buildWosStatus() {
        WosEnrichmentRunSummaryDto summary = rankingMaintenanceFacade.latestWosCategoryRankingEnrichmentSummary();
        if (!summary.executed()) {
            return AdminOperationStatus.neverRun();
        }
        String outcome = summary.failed() > 0 ? AdminOperationStatus.OUTCOME_PARTIAL : AdminOperationStatus.OUTCOME_SUCCESS;
        String text = "processed=" + summary.processed() + ", computed=" + summary.computed() + ", failed=" + summary.failed();
        return new AdminOperationStatus(summary.completedAt(), outcome, text);
    }

    private AdminOperationStatus buildIncrementalStatus() {
        List<ScopusPublicationUpdate> all = scopusPublicationUpdateRepository
                .findAll(Sort.by(Sort.Direction.DESC, "initiatedDate"));
        if (all.isEmpty()) {
            return AdminOperationStatus.neverRun();
        }
        ScopusPublicationUpdate last = all.get(0);
        Instant ts = parseDate(last.getInitiatedDate());
        String outcome = mapTaskOutcome(last.getStatus());
        return new AdminOperationStatus(ts, outcome, last.getMessage());
    }

    private List<AdminActivityEvent> buildRecentActivity() {
        List<AdminActivityEvent> events = new ArrayList<>();

        List<ScopusPublicationUpdate> pubUpdates = scopusPublicationUpdateRepository
                .findAll(Sort.by(Sort.Direction.DESC, "initiatedDate"));
        pubUpdates.stream()
                .filter(t -> t.getStatus() == Status.COMPLETED || t.getStatus() == Status.FAILED)
                .limit(5)
                .forEach(t -> events.add(new AdminActivityEvent(
                        AdminActivityEvent.TYPE_SCOPUS_SYNC,
                        "Scopus publication sync for " + t.getScopusId() + " — " + t.getStatus(),
                        parseDate(t.getExecutionDate()),
                        "/admin/scholardex/publications"
                )));

        List<ScopusCitationsUpdate> citeUpdates = scopusCitationUpdateRepository
                .findAll(Sort.by(Sort.Direction.DESC, "initiatedDate"));
        citeUpdates.stream()
                .filter(t -> t.getStatus() == Status.COMPLETED || t.getStatus() == Status.FAILED)
                .limit(5)
                .forEach(t -> events.add(new AdminActivityEvent(
                        AdminActivityEvent.TYPE_SCOPUS_SYNC,
                        "Scopus citation sync for " + t.getScopusId() + " — " + t.getStatus(),
                        parseDate(t.getExecutionDate()),
                        "/admin/scholardex/publications"
                )));

        WosEnrichmentRunSummaryDto wos = rankingMaintenanceFacade.latestWosCategoryRankingEnrichmentSummary();
        if (wos.executed() && wos.completedAt() != null) {
            String desc = "WoS enrichment completed — processed=" + wos.processed()
                    + ", computed=" + wos.computed() + ", failed=" + wos.failed();
            events.add(new AdminActivityEvent(
                    AdminActivityEvent.TYPE_WOS_ENRICHMENT,
                    desc,
                    wos.completedAt(),
                    "/admin/wos-enrichment"
            ));
        }

        events.sort(Comparator.comparing(
                AdminActivityEvent::timestamp,
                Comparator.nullsLast(Comparator.reverseOrder())
        ));
        return events.stream().limit(10).toList();
    }

    private String mapTaskOutcome(Status status) {
        if (status == null) return AdminOperationStatus.OUTCOME_NEVER_RUN;
        return switch (status) {
            case COMPLETED -> AdminOperationStatus.OUTCOME_SUCCESS;
            case FAILED -> AdminOperationStatus.OUTCOME_FAILED;
            case IN_PROGRESS -> AdminOperationStatus.OUTCOME_IN_PROGRESS;
            default -> AdminOperationStatus.OUTCOME_NEVER_RUN;
        };
    }

    private Instant parseDate(String date) {
        if (date == null || date.isBlank()) return null;
        try {
            return Instant.parse(date);
        } catch (DateTimeParseException e) {
            log.debug("Could not parse task date '{}' as Instant", date);
            return null;
        }
    }

    private long safeCount(Supplier<Long> supplier, List<String> errors, String section) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("Admin dashboard: failed to compute count for section '{}': {}", section, e.getMessage());
            errors.add(section);
            return 0L;
        }
    }

    private AdminOperationStatus safeOp(Supplier<AdminOperationStatus> supplier, List<String> errors, String section) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("Admin dashboard: failed to build operation status for section '{}': {}", section, e.getMessage());
            errors.add(section);
            return AdminOperationStatus.neverRun();
        }
    }

    private List<AdminActivityEvent> safeList(Supplier<List<AdminActivityEvent>> supplier, List<String> errors, String section) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("Admin dashboard: failed to build activity feed for section '{}': {}", section, e.getMessage());
            errors.add(section);
            return List.of();
        }
    }
}
