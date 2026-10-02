package ro.uvt.pokedex.core.model.reporting.scoring;

/**
 * Role-of-the-researcher filter for {@link IndicatorKind.Publications}.
 *
 * <ul>
 *   <li>{@code ALL} — every publication where any of the researcher's authors appears.</li>
 *   <li>{@code MAIN} — only publications where the researcher is the first author.</li>
 *   <li>{@code CO} — only publications where the researcher appears but is NOT the first author.</li>
 *   <li>{@code FIRST_OR_CORRESPONDING} — only publications where the researcher is the first author OR a corresponding
 *       author (H63; the physics {@code P = "prim autor sau autor corespondent"} role). Falls back to first-author
 *       alone for publications with no known corresponding author.</li>
 *   <li>{@code FIRST_CORRESPONDING_OR_LAST} — H138: first author, a corresponding author, OR the last author — the
 *       UEFISCDI Anexa 6(d) rule for the bio-medical domains, where the last author counts as principal too.</li>
 *   <li>{@code NOT_FIRST_NOR_CORRESPONDING} — the exact complement of {@code FIRST_OR_CORRESPONDING} over the
 *       researcher's publications: the researcher appears but is neither the first author nor a corresponding
 *       author. The 2026 Psihologie standard (Comisia 28) defines the co-author this way, so a corresponding
 *       author is never counted on both the principal and the co-author indicators (which {@code CO} would do).</li>
 * </ul>
 *
 * Replaces the {@code PUBLICATIONS_MAIN_AUTHOR} / {@code PUBLICATIONS_COAUTHOR} variants of
 * the former {@code Indicator.Type} enum (deleted in H52 slice 11d.5).
 */
public enum AuthorRole {
    ALL,
    MAIN,
    CO,
    FIRST_OR_CORRESPONDING,
    FIRST_CORRESPONDING_OR_LAST,
    NOT_FIRST_NOR_CORRESPONDING
}
