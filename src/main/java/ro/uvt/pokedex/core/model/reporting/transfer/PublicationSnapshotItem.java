package ro.uvt.pokedex.core.model.reporting.transfer;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
public class PublicationSnapshotItem implements SnapshotItem {
    public static final String ENTITY_TYPE = "PUBLICATION";

    private String itemKey;
    private String roleKey;
    private String title;
    private String authors;
    private String forumName;
    private String volumeInfo;
    private Integer year;
    private String forumCategoryLetter;
    private Integer authorCount;
    private String isWorkshopDaNu;
    private Integer citationCount;
    private Double citationPoints;
    /** Platform-computed score for this publication (the indicator's authorScore). Used by the
     *  import score-verification comparison; not written to any template cell. */
    private Double score;
    private Double authorScore;
    /** Base/forum score before the per-author division (Score.getScore()). For templates that show
     *  both the journal score (si) and the per-author contribution (si/ni = authorScore). */
    private Double forumScore;

    @Override
    public String entityType() {
        return ENTITY_TYPE;
    }

    @Override
    public String itemKey() {
        return itemKey;
    }

    public Map<String, Object> toRowMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("publication.title", title);
        m.put("publication.authors", authors);
        m.put("publication.forumName", forumName);
        m.put("publication.volumeInfo", volumeInfo);
        m.put("publication.year", year);
        m.put("publication.forumCategoryLetter", forumCategoryLetter);
        m.put("publication.authorCount", authorCount);
        m.put("publication.isWorkshopDaNu", isWorkshopDaNu);
        m.put("publication.citationCount", citationCount);
        m.put("publication.citationPoints", citationPoints);
        return m;
    }
}
