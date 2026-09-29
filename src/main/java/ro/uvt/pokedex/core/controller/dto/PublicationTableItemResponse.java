package ro.uvt.pokedex.core.controller.dto;

import java.util.List;

public record PublicationTableItemResponse(
        String id,
        String title,
        String year,
        String forumId,
        String forumName,
        List<String> authorNames,
        /** null on the public view (H119): citation counts are shown only to signed-in users */
        Integer citedByCount,
        String eid,
        /** the record's page on Scopus, null without a Scopus EID */
        String scopusUrl
) {
}
