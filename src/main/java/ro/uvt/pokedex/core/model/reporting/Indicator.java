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
    // Legacy schema. As of H52 slice 11d.3 these fields are {@code @Transient}:
    // Spring Data Mongo no longer persists or reads them. They stay on the class
    // so unit tests that still call {@code setOutputType(...)} etc. keep
    // compiling (production reads went through {@code getEffectiveKind()} in
    // slices 11d.1 / 11d.2). Pre-condition: the migration runner has populated
    // v1 typed fields ({@code kind}, {@code yearRangeSpec}, etc.) on every
    // indicator. Slice 11e will Mongo-{@code $unset} the residual legacy keys
    // from existing docs; slice 11d.4 physically removes the fields once the
    // test surface has been refactored.
    //
    // JSON serialization still emits the legacy keys via Lombok's getters —
    // that's what the slice-10 fixture captured, so the tripwire stays valid.
    // -------------------------------------------------------------------

    /** @deprecated use {@link #getEffectiveKind()}. Not persisted as of slice 11d.3. */
    @Deprecated
    @Transient
    private Type outputType;
    /** @deprecated use {@link #getEffectiveKind()}. Not persisted as of slice 11d.3. */
    @Deprecated
    @Transient
    private Strategy scoringStrategy;
    /** @deprecated use {@link #getEffectiveYearRange()}. Not persisted as of slice 11d.3. */
    @Deprecated
    @Transient
    private String yearRange;
    /** @deprecated use {@link #getEffectiveScoreYearRange()}. Not persisted as of slice 11d.3. */
    @Deprecated
    @Transient
    private String scoreYearRange;
    /** @deprecated use {@link #getEffectiveSelector()}. Not persisted as of slice 11d.3. */
    @Deprecated
    @Transient
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

    // -------------------------------------------------------------------
    // Legacy getter overrides. Lombok's {@code @Data} generates getters
    // from fields; we override here so that when the {@code @Transient}
    // field is null (i.e. loaded from Mongo where the legacy keys are
    // gone), the getter falls back to deriving the legacy value from
    // the typed kind/specs. Production-side consumers that still call
    // {@code getScoringStrategy()} / {@code getOutputType()} (e.g. the
    // {@code ScoringFactoryService} legacy bridge, log messages, the
    // {@code UserIndicatorResultService} fingerprint) continue to work
    // without touching their call sites.
    // -------------------------------------------------------------------

    /**
     * H52 slice 11d.3: returns the stored legacy field, or derives from
     * {@link #kind} when the field is null (the common case post-migration).
     */
    public Type getOutputType() {
        if (outputType != null) return outputType;
        return kind != null ? kind.toLegacy().type() : null;
    }

    /** H52 slice 11d.3: stored field, or derived from {@link #kind}. */
    public Strategy getScoringStrategy() {
        if (scoringStrategy != null) return scoringStrategy;
        return kind != null ? kind.toLegacy().strategy() : null;
    }

    /** H52 slice 11d.3: stored field, or derived from {@link #yearRangeSpec}. */
    public String getYearRange() {
        if (yearRange != null) return yearRange;
        return yearRangeSpec != null ? legacyYearRangeString(yearRangeSpec) : null;
    }

    /** H52 slice 11d.3: stored field, or derived from {@link #scoreYearRangeSpec}. */
    public String getScoreYearRange() {
        if (scoreYearRange != null) return scoreYearRange;
        return scoreYearRangeSpec != null ? legacyScoreYearRangeString(scoreYearRangeSpec) : null;
    }

    /** H52 slice 11d.3: stored field, or derived from {@link #selectorSpec}. */
    public Selector getSelector() {
        if (selector != null) return selector;
        if (selectorSpec instanceof ro.uvt.pokedex.core.model.reporting.scoring.Selector.TopN) {
            return Selector.TOP_10;
        }
        if (selectorSpec instanceof ro.uvt.pokedex.core.model.reporting.scoring.Selector.All) {
            return Selector.ALL;
        }
        return null;
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
        return outputType == Type.PUBLICATIONS
                || outputType == Type.PUBLICATIONS_MAIN_AUTHOR
                || outputType == Type.PUBLICATIONS_COAUTHOR;
    }

    /** True iff the indicator is citations-shaped (either inclusive or exclude-self). */
    public boolean isCitationsOutput() {
        IndicatorKind k = getEffectiveKind();
        if (k != null) return k instanceof IndicatorKind.Citations;
        return outputType == Type.CITATIONS || outputType == Type.CITATIONS_EXCLUDE_SELF;
    }

    /** True for {@link IndicatorKind.Citations} with {@code excludeSelf == true}. */
    public boolean isCitationsExcludeSelf() {
        IndicatorKind k = getEffectiveKind();
        if (k != null) return k instanceof IndicatorKind.Citations c && c.excludeSelf();
        return outputType == Type.CITATIONS_EXCLUDE_SELF;
    }

    /**
     * True iff the indicator is activity-shaped —
     * {@link IndicatorKind.Activity} (FORUM/UNIVERSITY/EVENT) or
     * {@link IndicatorKind.GenericActivity}. Mirrors the pre-v1
     * {@code outputType.toString().contains("ACTIVIT")} check.
     */
    public boolean isActivityOutput() {
        IndicatorKind k = getEffectiveKind();
        if (k != null) return k instanceof IndicatorKind.Activity || k instanceof IndicatorKind.GenericActivity;
        return outputType == Type.ACTIVITY_FORUM
                || outputType == Type.ACTIVITY_UNIVERSITY
                || outputType == Type.ACTIVITY_EVENT
                || outputType == Type.ACTIVITY_PROJECT
                || outputType == Type.GENERIC_ACTIVITIES;
    }

    /**
     * The author-role constraint for a publications-typed indicator, or {@code null}
     * if the indicator isn't publications-shaped. Replaces the legacy
     * {@code outputType == PUBLICATIONS_MAIN_AUTHOR} / {@code _COAUTHOR} equality checks.
     */
    public ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole publicationAuthorRole() {
        IndicatorKind k = getEffectiveKind();
        if (k instanceof IndicatorKind.Publications p) return p.role();
        if (k != null) return null;
        if (outputType == Type.PUBLICATIONS_MAIN_AUTHOR) {
            return ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole.MAIN;
        }
        if (outputType == Type.PUBLICATIONS_COAUTHOR) {
            return ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole.CO;
        }
        if (outputType == Type.PUBLICATIONS) {
            return ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole.ALL;
        }
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
