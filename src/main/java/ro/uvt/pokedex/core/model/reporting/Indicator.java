package ro.uvt.pokedex.core.model.reporting;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoreYearRangeSpec;
import ro.uvt.pokedex.core.model.reporting.scoring.Selector;
import ro.uvt.pokedex.core.model.reporting.scoring.YearRangeSpec;

import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "indicators")
public class Indicator {
    @Id
    private String id;
    private String name;
    private String formula;

    @DBRef
    private Domain domain;
    @DBRef
    private Activity activity;

    /**
     * Optimistic-locking version. Existing documents were backfilled to {@code 0} on
     * 2026-05-31 (H52 slice 2 pre-step); fresh inserts get {@code 0L} as the Spring Data
     * convention so the first save round-trips correctly.
     */
    @Version
    private Long version;

    // -------------------------------------------------------------------
    // Legacy schema (kept readable through the H52 migration; removed in
    // H52 slice 6). New callers should reach for the {@code getEffective*}
    // helpers below instead of these fields directly.
    // -------------------------------------------------------------------

    /** @deprecated use {@link #getEffectiveKind()}. Removed at the end of H52. */
    @Deprecated
    private Type outputType;
    /** @deprecated use {@link #getEffectiveKind()}. Removed at the end of H52. */
    @Deprecated
    private Strategy scoringStrategy;
    /** @deprecated use {@link #getEffectiveYearRange()}. Removed at the end of H52. */
    @Deprecated
    private String yearRange;
    /** @deprecated use {@link #getEffectiveScoreYearRange()}. Removed at the end of H52. */
    @Deprecated
    private String scoreYearRange;
    /** @deprecated use {@link #getEffectiveSelector()}. Removed at the end of H52. */
    @Deprecated
    private Selector selector;

    // -------------------------------------------------------------------
    // v1 typed schema (populated by H52 slice 3 migration; falls back to
    // converting from the legacy fields until then via the helpers below).
    // -------------------------------------------------------------------

    private IndicatorKind kind;

    /**
     * SHA-256 of the parsed AST canonical form of {@link #formula}. Stable across cosmetic
     * whitespace edits; identity for the {@code userIndicatorResults} fingerprint. Computed
     * at indicator-save time once H52 slice 4 ships.
     */
    private String formulaHash;

    private YearRangeSpec yearRangeSpec;
    private ScoreYearRangeSpec scoreYearRangeSpec;
    private ro.uvt.pokedex.core.model.reporting.scoring.Selector selectorSpec;

    // -------------------------------------------------------------------
    // Effective getters — prefer v1 fields when populated; otherwise
    // synthesise the v1 shape from the legacy fields. Hot-path scoring
    // code calls these so it doesn't care which migration phase the
    // document is in.
    // -------------------------------------------------------------------

    /** v1 shape of the indicator, computed lazily from legacy fields when not yet migrated. */
    public IndicatorKind getEffectiveKind() {
        if (kind != null) return kind;
        if (outputType == null || scoringStrategy == null) return null;
        return IndicatorKind.fromLegacy(outputType, scoringStrategy);
    }

    public YearRangeSpec getEffectiveYearRange() {
        if (yearRangeSpec != null) return yearRangeSpec;
        return YearRangeSpec.parse(yearRange);
    }

    public ScoreYearRangeSpec getEffectiveScoreYearRange() {
        if (scoreYearRangeSpec != null) return scoreYearRangeSpec;
        return ScoreYearRangeSpec.parse(scoreYearRange);
    }

    public ro.uvt.pokedex.core.model.reporting.scoring.Selector getEffectiveSelector() {
        if (selectorSpec != null) return selectorSpec;
        return ro.uvt.pokedex.core.model.reporting.scoring.Selector.fromLegacy(selector);
    }

    /**
     * @deprecated Legacy nested enum. The v1 grammar lives in
     * {@link ro.uvt.pokedex.core.model.reporting.scoring.Selector}. Removed at the end of H52.
     */
    @Deprecated
    public static enum Selector {
        ALL,
        TOP_10
    }

    public static List<Integer> parseYearRange(String yearRange, int itemYear) {
        List<Integer> years = new ArrayList<>();
        if (yearRange == null || yearRange.isEmpty()) {
            return years;
        }
        if(yearRange.equals("*")){
            int currentYear = java.time.LocalDate.now().getYear();
            for (int i = 1990; i <= currentYear; i++) {
                years.add(i);
            }
            return years;
        }

        String[] parts = yearRange.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.contains("->")) {
                String[] range = part.split("->");
                try {
                    int start = parseYear(range[0].trim(), itemYear);
                    int end = parseYear(range[1].trim(), itemYear);
                    for (int i = start; i <= end; i++) {
                        years.add(i);
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid range format: " + part);
                }
            } else {
                try {
                    years.add(parseYear(part, itemYear));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid year format: " + part);
                }
            }
        }
        return years;
    }

    private static int parseYear(String year, int itemYear) {
        if (year.equals("IY")) {
            return itemYear;
        } else if (year.startsWith("IY")) {
            return itemYear + Integer.parseInt(year.substring(2));
        } else {
            return Integer.parseInt(year);
        }
    }

    /**
     * @deprecated Legacy free-floating discriminator. The v1 hierarchy lives in
     * {@link ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind}; convert via
     * {@link ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind#fromLegacy}.
     * Removed at the end of H52.
     */
    @Deprecated
    public enum Type{
        PUBLICATIONS,
        PUBLICATIONS_MAIN_AUTHOR,
        PUBLICATIONS_COAUTHOR,
        CITATIONS,
        CITATIONS_EXCLUDE_SELF,
        GENERIC_ACTIVITIES,
        ACTIVITY_FORUM,
        ACTIVITY_EVENT,
        ACTIVITY_PROJECT,
        ACTIVITY_UNIVERSITY
    }

    /**
     * @deprecated Legacy free-floating discriminator. The v1 enum lives in
     * {@link ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy}. Removed at the
     * end of H52.
     */
    @Deprecated
    public enum Strategy{
        GENERIC_ACTIVITY,
        GENERIC_COUNT,
        CS_CONFERENCE,
        CS_JOURNAL,
        CS_SENSE,
        CS,
        IMPACT_FACTOR,
        RIS,
        AIS,
        ECONOMICS_JOURNAL_AIS,
        UNI_RANKING,
        CNCSIS,
        ART_EVENT
    }
}
