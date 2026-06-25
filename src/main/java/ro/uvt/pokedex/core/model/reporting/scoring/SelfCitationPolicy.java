package ro.uvt.pokedex.core.model.reporting.scoring;

/**
 * H61: how a citation indicator treats self-citations. The exclusion set widens left→right
 * ({@code ANY_COAUTHOR ⊇ CANDIDATE_ONLY ⊇ ∅}):
 *
 * <ul>
 *   <li>{@link #NONE} — count every citation ({@code CITATIONS}).</li>
 *   <li>{@link #CANDIDATE_ONLY} — exclude a citation when the citing work shares an author with the <b>candidate</b>
 *       (the candidate's resolved author ids) ({@code CITATIONS_EXCLUDE_SELF}; Ordin 6129/2016 Matematică).</li>
 *   <li>{@link #ANY_COAUTHOR} — exclude a citation when the citing work shares <b>any</b> author of the <i>cited</i>
 *       publication, not only the candidate (Scopus "exclude self-citations of all authors";
 *       {@code CITATIONS_EXCLUDE_COAUTHORS}; upcoming Informatică standard).</li>
 * </ul>
 *
 * <p>Overlap is matched by canonical author id at both the score and display filter sites. Co-authors and citing-side
 * authors are less likely to be canonicalized than the candidate, so {@code ANY_COAUTHOR} <i>under-excludes</i> when
 * those ids are unlinked — a known data-quality limitation of this mode.</p>
 */
public enum SelfCitationPolicy {
    NONE,
    CANDIDATE_ONLY,
    ANY_COAUTHOR
}
