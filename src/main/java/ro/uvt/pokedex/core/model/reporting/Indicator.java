package ro.uvt.pokedex.core.model.reporting;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
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
    // v1 typed schema. As of H52 slice 11d.4 these are the ONLY storage
    // locations for indicator shape — the @Deprecated legacy fields are
    // gone. Legacy {@code setOutputType(...)}/{@code setScoringStrategy(...)}/
    // {@code setYearRange(...)}/{@code setScoreYearRange(...)}/{@code setSelector(...)}
    // setters (declared below) route their inputs into these fields. There
    // is no dual storage and no way to write data to a "legacy bag" that
    // doesn't make it into the typed shape.
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
    // Transient state for the brief window between paired form-binding
    // setter calls. Spring's @ModelAttribute may call setOutputType
    // before setScoringStrategy (or vice versa); we accumulate both
    // halves here and materialize the typed kind once both arrive.
    // Never persisted, never serialized.
    // -------------------------------------------------------------------
    @org.springframework.data.annotation.Transient
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String pendingOutputType;
    @org.springframework.data.annotation.Transient
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String pendingScoringStrategy;

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
        if (kind != null) return kind;
        // During form binding, surface a synthesized kind as soon as both
        // halves of the legacy pair have arrived. Reads after binding (e.g.
        // by the BeforeConvert listener) see the same shape they will
        // eventually persist.
        if (pendingOutputType != null && pendingScoringStrategy != null) {
            return IndicatorKind.of(pendingOutputType, pendingScoringStrategy);
        }
        return null;
    }

    /** Derived from {@link #kind} (or from a pending half during form binding). */
    public String getOutputType() {
        IndicatorKind k = kind;
        if (k != null) return k.toLegacy().outputTypeName();
        return pendingOutputType;
    }

    /** Derived from {@link #kind} (or from a pending half during form binding). */
    public String getScoringStrategy() {
        IndicatorKind k = kind;
        if (k != null) return k.toLegacy().strategyName();
        return pendingScoringStrategy;
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

    // -------------------------------------------------------------------
    // Legacy setters — route inputs into the typed kind/specs. The legacy
    // fields are gone; there is no dual storage. The admin form's
    // {@code th:field="*{outputType}"} bindings land here, get translated
    // into typed shape, and the {@code IndicatorFormulaHashStamper}
    // {@code onBeforeConvert} hook materializes any pending halves into
    // a kind before save.
    // -------------------------------------------------------------------

    /** Form-binding compat: routes the legacy-name output type into the typed {@link #kind}. */
    public void setOutputType(String t) {
        if (kind != null) {
            // Incremental update: preserve the current strategy, swap the type.
            String currentStrategy = kind.toLegacy().strategyName();
            this.kind = (t != null && currentStrategy != null)
                    ? IndicatorKind.of(t, currentStrategy)
                    : null;
            if (this.kind == null) this.pendingOutputType = t;
            return;
        }
        this.pendingOutputType = t;
        materializeKindIfReady();
    }

    /** Form-binding compat: routes the legacy-name strategy into the typed {@link #kind}. */
    public void setScoringStrategy(String s) {
        if (kind != null) {
            String currentType = kind.toLegacy().outputTypeName();
            this.kind = (s != null && currentType != null)
                    ? IndicatorKind.of(currentType, s)
                    : null;
            if (this.kind == null) this.pendingScoringStrategy = s;
            return;
        }
        this.pendingScoringStrategy = s;
        materializeKindIfReady();
    }

    /** Form-binding compat: parses the legacy string into {@link #yearRangeSpec}. */
    public void setYearRange(String yr) {
        if (yr == null || yr.isBlank()) {
            this.yearRangeSpec = null;
            return;
        }
        this.yearRangeSpec = YearRangeSpec.parse(yr);
    }

    /** Form-binding compat: parses the legacy string into {@link #scoreYearRangeSpec}. */
    public void setScoreYearRange(String syr) {
        if (syr == null || syr.isBlank()) {
            this.scoreYearRangeSpec = null;
            return;
        }
        this.scoreYearRangeSpec = ScoreYearRangeSpec.parse(syr);
    }

    /**
     * Form-binding compat: routes the legacy-name selector
     * ({@code null} / {@code "ALL"} / {@code "TOP_10"}) into {@link #selectorSpec}.
     */
    public void setSelector(String legacyName) {
        this.selectorSpec = ro.uvt.pokedex.core.model.reporting.scoring.Selector.of(legacyName);
        // Selector.of(null) returns All(); preserve "not set" intent on null.
        if (legacyName == null) this.selectorSpec = null;
    }

    private void materializeKindIfReady() {
        if (pendingOutputType != null && pendingScoringStrategy != null) {
            this.kind = IndicatorKind.of(pendingOutputType, pendingScoringStrategy);
            this.pendingOutputType = null;
            this.pendingScoringStrategy = null;
        }
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
     * True iff the indicator is publications-shaped. Prefers the typed kind when
     * resolvable; falls back to the legacy {@code outputType} for indicators that
     * only carry one half of the legacy pair (predominantly unit-test fixtures —
     * production data always has both fields populated by the migration runner).
     */
    public boolean isPublicationOutput() {
        IndicatorKind k = getEffectiveKind();
        if (k != null) return k instanceof IndicatorKind.Publications;
        // Half-set fixture: legacy setOutputType called without setScoringStrategy.
        return "PUBLICATIONS".equals(pendingOutputType)
                || "PUBLICATIONS_MAIN_AUTHOR".equals(pendingOutputType)
                || "PUBLICATIONS_COAUTHOR".equals(pendingOutputType);
    }

    /** True iff the indicator is citations-shaped (either inclusive or exclude-self). */
    public boolean isCitationsOutput() {
        IndicatorKind k = getEffectiveKind();
        if (k != null) return k instanceof IndicatorKind.Citations;
        return "CITATIONS".equals(pendingOutputType) || "CITATIONS_EXCLUDE_SELF".equals(pendingOutputType);
    }

    /** True for {@link IndicatorKind.Citations} with {@code excludeSelf == true}. */
    public boolean isCitationsExcludeSelf() {
        IndicatorKind k = getEffectiveKind();
        if (k != null) return k instanceof IndicatorKind.Citations c && c.excludeSelf();
        return "CITATIONS_EXCLUDE_SELF".equals(pendingOutputType);
    }

    /**
     * True iff the indicator is activity-shaped —
     * {@link IndicatorKind.Activity} (FORUM/UNIVERSITY/EVENT) or
     * {@link IndicatorKind.GenericActivity}.
     */
    public boolean isActivityOutput() {
        IndicatorKind k = getEffectiveKind();
        if (k != null) return k instanceof IndicatorKind.Activity || k instanceof IndicatorKind.GenericActivity;
        return "ACTIVITY_FORUM".equals(pendingOutputType)
                || "ACTIVITY_UNIVERSITY".equals(pendingOutputType)
                || "ACTIVITY_EVENT".equals(pendingOutputType)
                || "ACTIVITY_PROJECT".equals(pendingOutputType)
                || "GENERIC_ACTIVITIES".equals(pendingOutputType);
    }

    /**
     * The author-role constraint for a publications-typed indicator, or {@code null}
     * if the indicator isn't publications-shaped.
     */
    public ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole publicationAuthorRole() {
        IndicatorKind k = getEffectiveKind();
        if (k instanceof IndicatorKind.Publications p) return p.role();
        if (k != null) return null;
        return switch (pendingOutputType == null ? "" : pendingOutputType) {
            case "PUBLICATIONS_MAIN_AUTHOR" -> ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole.MAIN;
            case "PUBLICATIONS_COAUTHOR"    -> ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole.CO;
            case "PUBLICATIONS"             -> ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole.ALL;
            default -> null;
        };
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
