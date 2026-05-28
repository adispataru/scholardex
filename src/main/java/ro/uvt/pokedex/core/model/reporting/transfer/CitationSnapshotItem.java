package ro.uvt.pokedex.core.model.reporting.transfer;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class CitationSnapshotItem implements SnapshotItem {
    public static final String ENTITY_TYPE = "CITATION_TILE";

    private String itemKey;
    private String roleKey;

    // Cited publication (the tile's header).
    private String publicationTitle;
    private String publicationForumName;
    private Integer publicationYear;
    private Integer publicationAuthorCount;

    /** Sum of the kept citing rows' platform scores — the tile's comparable score for verification. */
    private Double score;

    // Citing publications (one row per citation inside the tile).
    private List<CitingPublication> citingPublications = new ArrayList<>();

    @Override
    public String entityType() {
        return ENTITY_TYPE;
    }

    @Override
    public String itemKey() {
        return itemKey;
    }

    public Map<String, Object> toHeaderMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("publication.title", publicationTitle);
        m.put("publication.forumName", publicationForumName);
        m.put("publication.year", publicationYear);
        m.put("publication.authorCount", publicationAuthorCount);
        return m;
    }

    public List<Map<String, Object>> toInnerRowMaps() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CitingPublication c : citingPublications) {
            Map<String, Object> r = new HashMap<>();
            r.put("citation.title", c.getTitle());
            r.put("citation.authors", c.getAuthors());
            r.put("citation.forumName", c.getForumName());
            r.put("citation.volumeInfo", c.getVolumeInfo());
            r.put("citation.year", c.getYear());
            r.put("citation.isWorkshopDaNu", c.getIsWorkshopDaNu());
            r.put("citation.forumCategoryLetter", c.getForumCategoryLetter());
            rows.add(r);
        }
        return rows;
    }

    @Data
    @NoArgsConstructor
    public static class CitingPublication {
        private String title;
        private String authors;
        private String forumName;
        private String volumeInfo;
        private Integer year;
        private String isWorkshopDaNu;
        private String forumCategoryLetter;
        private Double score;
    }
}
