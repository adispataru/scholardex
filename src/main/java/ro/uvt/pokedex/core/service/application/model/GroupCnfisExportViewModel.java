package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.List;
import java.util.Map;

public record GroupCnfisExportViewModel(
        List<ScoringPublicationReadModel> publications,
        List<CNFISReport2025> cnfisReports,
        Map<String, ScholardexForumView> forumMap,
        List<String> authorIds
) {
}
