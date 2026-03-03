package ro.uvt.pokedex.core.service.application.model;

import java.util.List;

public record ForumExportViewModel(
        List<ForumExportRow> rows
) {
}
