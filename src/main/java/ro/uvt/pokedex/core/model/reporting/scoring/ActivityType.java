package ro.uvt.pokedex.core.model.reporting.scoring;

/**
 * Discriminator for {@link IndicatorKind.Activity}. Replaces the three activity variants
 * of the former {@code Indicator.Type} enum (deleted in H52 slice 11d.5):
 * {@code ACTIVITY_FORUM} / {@code ACTIVITY_UNIVERSITY} / {@code ACTIVITY_EVENT}.
 *
 * <p>The legacy {@code GENERIC_ACTIVITIES} value is NOT modeled here — it has its own
 * top-level kind ({@link IndicatorKind.GenericActivity}) because its scoring path does
 * not consult an activity-specific scoring strategy.
 */
public enum ActivityType {
    FORUM,
    UNIVERSITY,
    EVENT
}
