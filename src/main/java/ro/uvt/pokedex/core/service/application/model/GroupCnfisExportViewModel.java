package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;

import java.util.List;
import java.util.Map;

public record GroupCnfisExportViewModel(
        List<Publication> publications,
        List<CNFISReport2025> cnfisReports,
        Map<String, Forum> forumMap,
        List<String> authorIds
) {
}
