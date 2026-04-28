package ro.uvt.pokedex.core.controller.dto;

import java.util.List;

public record PublicationTableItemResponse(
        String id,
        String title,
        String year,
        String forumId,
        String forumName,
        List<String> authorNames,
        int citedByCount,
        String eid
) {
}
