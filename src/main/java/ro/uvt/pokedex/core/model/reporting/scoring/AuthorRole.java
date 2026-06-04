package ro.uvt.pokedex.core.model.reporting.scoring;

/**
 * Role-of-the-researcher filter for {@link IndicatorKind.Publications}.
 *
 * <ul>
 *   <li>{@code ALL} — every publication where any of the researcher's authors appears.</li>
 *   <li>{@code MAIN} — only publications where the researcher is the first author.</li>
 *   <li>{@code CO} — only publications where the researcher appears but is NOT the first author.</li>
 * </ul>
 *
 * Replaces the {@code PUBLICATIONS_MAIN_AUTHOR} / {@code PUBLICATIONS_COAUTHOR} variants of
 * the former {@code Indicator.Type} enum (deleted in H52 slice 11d.5).
 */
public enum AuthorRole {
    ALL,
    MAIN,
    CO
}
