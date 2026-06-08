package ro.uvt.pokedex.core.model.reporting;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoreYearRangeSpec;
import ro.uvt.pokedex.core.model.reporting.scoring.YearRangeSpec;

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
    // v1 typed schema. These are the ONLY storage locations for indicator
    // shape. Admin form string binding lives in AdminViewController.IndicatorForm
    // and is converted into this typed shape before save.
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
    // Effective getters and legacy-compat getters. The {@code getEffective*}
    // helpers are the canonical read API; the legacy {@code getOutputType},
    // {@code getScoringStrategy}, {@code getYearRange}, {@code getScoreYearRange},
    // {@code getSelector} getters derive from the typed shape and are only
    // kept alive for downstream code (admin form rendering, fingerprint
    // composition, log messages) that hasn't migrated to the typed API yet.
    // -------------------------------------------------------------------

    /** v1 shape of the indicator. {@code null} if no kind has been resolved. */
    public IndicatorKind getEffectiveKind() {
        return kind;
    }

    /** Derived from {@link #kind}. */
    public String getOutputType() {
        return kind != null ? kind.toLegacy().outputTypeName() : null;
    }

    /** Derived from {@link #kind}. */
    public String getScoringStrategy() {
        return kind != null ? kind.toLegacy().strategyName() : null;
    }

    /** Derived from {@link #yearRangeSpec}. */
    public String getYearRange() {
        return yearRangeSpec != null ? legacyYearRangeString(yearRangeSpec) : null;
    }

    /** Derived from {@link #scoreYearRangeSpec}. */
    public String getScoreYearRange() {
        return scoreYearRangeSpec != null ? legacyScoreYearRangeString(scoreYearRangeSpec) : null;
    }

    /** Derived from {@link #selectorSpec}. Returns legacy name (`"ALL"`/`"TOP_10"`) or null. */
    public String getSelector() {
        return selectorSpec != null ? selectorSpec.legacyName() : null;
    }

    private static String legacyYearRangeString(YearRangeSpec spec) {
        if (spec instanceof YearRangeSpec.AllYears) return "*";
        if (spec instanceof YearRangeSpec.Absolute a) return a.from() + "->" + a.to();
        return null;
    }

    private static String legacyScoreYearRangeString(ScoreYearRangeSpec spec) {
        if (spec instanceof ScoreYearRangeSpec.AllYears) return "*";
        if (spec instanceof ScoreYearRangeSpec.ItemYear) return "IY";
        if (spec instanceof ScoreYearRangeSpec.Absolute a) return a.from() + "->" + a.to();
        return null;
    }

    /**
     * H52 slice 11d.1: convenience for the common consumer check
     * {@code indicator.getScoringStrategy() == GENERIC_COUNT}. The legacy
     * {@code (PUBLICATIONS, GENERIC_COUNT)} pairing maps to
     * {@link IndicatorKind.Publications}, not {@link IndicatorKind.GenericCount},
     * so an {@code instanceof} check would miss the legacy form. The strategy-based
     * check here works against both legacy and v1 indicators.
     */
    public boolean isGenericCount() {
        IndicatorKind k = getEffectiveKind();
        return k != null && k.strategy() == ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy.GENERIC_COUNT;
    }

    /**
     * H52 slice 11d.1: convenience for the {@code GENERIC_ACTIVITY} strategy check
     * on the activity-reporting path. Only the {@code (GENERIC_ACTIVITIES,
     * GENERIC_ACTIVITY)} legacy pair maps to {@link IndicatorKind.GenericActivity},
     * but strategy-based check is uniform.
     */
    public boolean isGenericActivity() {
        IndicatorKind k = getEffectiveKind();
        return k != null && k.strategy() == ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy.GENERIC_ACTIVITY;
    }

    // -------------------------------------------------------------------
    // H52 slice 11d.2: typed-output convenience accessors. Each replaces
    // a legacy {@code outputType} switch / equality check at call sites.
    // -------------------------------------------------------------------

    /**
     * True iff the indicator is publications-shaped.
     */
    public boolean isPublicationOutput() {
        IndicatorKind k = getEffectiveKind();
        return k instanceof IndicatorKind.Publications;
    }

    /** True iff the indicator is citations-shaped (either inclusive or exclude-self). */
    public boolean isCitationsOutput() {
        IndicatorKind k = getEffectiveKind();
        return k instanceof IndicatorKind.Citations;
    }

    /** True for {@link IndicatorKind.Citations} with {@code excludeSelf == true}. */
    public boolean isCitationsExcludeSelf() {
        IndicatorKind k = getEffectiveKind();
        return k instanceof IndicatorKind.Citations c && c.excludeSelf();
    }

    /**
     * True iff the indicator is activity-shaped —
     * {@link IndicatorKind.Activity} (FORUM/UNIVERSITY/EVENT) or
     * {@link IndicatorKind.GenericActivity}.
     */
    public boolean isActivityOutput() {
        IndicatorKind k = getEffectiveKind();
        return k instanceof IndicatorKind.Activity || k instanceof IndicatorKind.GenericActivity;
    }

    /**
     * The author-role constraint for a publications-typed indicator, or {@code null}
     * if the indicator isn't publications-shaped.
     */
    public ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole publicationAuthorRole() {
        IndicatorKind k = getEffectiveKind();
        if (k instanceof IndicatorKind.Publications p) return p.role();
        return null;
    }

    /**
     * True iff the effective selector is a {@link ro.uvt.pokedex.core.model.reporting.scoring.Selector.TopN}.
     * Replaces the legacy {@code selector == Selector.TOP_10} check; the v1 grammar
     * carries the {@code n} too so callers can stop hardcoding 10.
     */
    public boolean isTopNSelector() {
        return getEffectiveSelector() instanceof ro.uvt.pokedex.core.model.reporting.scoring.Selector.TopN;
    }

    /**
     * The numeric N for a {@code TopN} selector, or {@code 10} if the indicator
     * carries only the legacy {@code Selector.TOP_10} enum. Callers should prefer
     * this over hardcoding {@code 10} so the v1 typed grammar reaches the runtime.
     */
    public int topNLimit() {
        ro.uvt.pokedex.core.model.reporting.scoring.Selector s = getEffectiveSelector();
        if (s instanceof ro.uvt.pokedex.core.model.reporting.scoring.Selector.TopN top) {
            return top.n();
        }
        return 10;
    }

    public YearRangeSpec getEffectiveYearRange() {
        return yearRangeSpec != null ? yearRangeSpec : new YearRangeSpec.AllYears();
    }

    public ScoreYearRangeSpec getEffectiveScoreYearRange() {
        return scoreYearRangeSpec != null ? scoreYearRangeSpec : new ScoreYearRangeSpec.ItemYear();
    }

    public ro.uvt.pokedex.core.model.reporting.scoring.Selector getEffectiveSelector() {
        return selectorSpec != null
                ? selectorSpec
                : new ro.uvt.pokedex.core.model.reporting.scoring.Selector.All();
    }

    // H52 slice 11d.5: the legacy nested enums (Selector, Type, Strategy) and the
    // {@code parseYearRange} static helper are gone. The v1 model lives entirely in
    // {@link ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind} /
    // {@link YearRangeSpec} / {@link ScoreYearRangeSpec} /
    // {@link ro.uvt.pokedex.core.model.reporting.scoring.Selector}. Year-range expansion
    // is provided by {@link ScoreYearRangeSpec#allowedYears(int)}.
}
