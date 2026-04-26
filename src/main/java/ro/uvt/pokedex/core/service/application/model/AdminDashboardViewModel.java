package ro.uvt.pokedex.core.service.application.model;

import java.util.List;

public record AdminDashboardViewModel(
        long openConflictCount,
        long pendingTriageCount,
        long totalResearchers,
        long totalPublications,
        long totalForums,
        AdminOperationStatus wosEnrichmentStatus,
        AdminOperationStatus initializationStatus,
        AdminOperationStatus incrementalUpdateStatus,
        List<AdminActivityEvent> recentActivity,
        List<String> aggregationErrors
) {
    public boolean hasErrors() {
        return aggregationErrors != null && !aggregationErrors.isEmpty();
    }
}
