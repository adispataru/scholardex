package ro.uvt.pokedex.core.service.importing;

/**
 * Builder-logic versions stamped onto derived facts (H54.6b).
 *
 * <p>Each derived fact records the version of the builder that produced it, so stale data (built by
 * an older code version) can be detected and a targeted rebuild driven later. Bump a constant when
 * the corresponding builder's output logic changes in a way that requires re-deriving existing facts.
 * The version is a stable constant per build, so it does not affect rebuild determinism.
 */
public final class BuilderVersion {

    private BuilderVersion() {
    }

    // Stage-2 source facts.
    public static final String SCOPUS_FACT = "scopus-fact@1";
    public static final String WOS_FACT = "wos-fact@1";
    public static final String USER_DEFINED_FACT = "user-defined-fact@1";

    // Stage-3 canonical (Scholardex) facts.
    public static final String SCHOLARDEX_PUBLICATION = "scholardex-publication@1";
    public static final String SCHOLARDEX_AUTHOR = "scholardex-author@1";
    public static final String SCHOLARDEX_AFFILIATION = "scholardex-affiliation@1";
    public static final String SCHOLARDEX_CITATION = "scholardex-citation@1";
    public static final String SCHOLARDEX_FORUM = "scholardex-forum@1";
    public static final String SCHOLARDEX_EDGE = "scholardex-edge@1";
}
