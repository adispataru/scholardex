package ro.uvt.pokedex.core.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Map;

/**
 * H83 S2 — generic multi-source world university ranking (ARWU/Shanghai now, QS next). One document per
 * (source, university-name); URAP keeps its legacy collection ({@code urap.rankings}) until a later
 * unification. Banded positions ("151-200") store the band's LOWER BOUND as {@code rank} (pinned
 * decision: candidate-favorable, consistent with the top-N bracket reads) plus the raw band string for
 * display/provenance.
 */
@Data
@Document(collection = "university_rankings")
public class UniversityRanking {

    /** {@code <source>|<name>} — names are only unique within a source. */
    @Id
    private String id;
    @Indexed
    private String name;
    @Indexed
    private String source;
    private String country;
    private Map<Integer, RankEntry> ranks;

    public static String composeId(String source, String name) {
        return source + "|" + name;
    }

    @Data
    public static class RankEntry {
        /** Numeric position; for banded rows the band's lower bound. */
        private int rank;
        /** Raw position string as published (e.g. "151-200"); equals the rank for exact positions. */
        private String rankBand;
    }
}
