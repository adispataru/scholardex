package ro.uvt.pokedex.core.model.reporting.uefiscdi;

import ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole;

/** H138 — PN-IV PD/TE 2026, Anexa 6: who counts as a principal author in a competition domain. */
public enum PrincipalAuthorRule {
    /** 6(e): all authors (maths, informatics, economics — the alphabetical-order practice). */
    ALL_AUTHORS_6E(AuthorRole.ALL),
    /** 6(d): first, corresponding, and the last author too (bio-medical). */
    LAST_AUTHOR_TOO_6D(AuthorRole.FIRST_CORRESPONDING_OR_LAST),
    /** 6(a)–(c): first or corresponding author. */
    FIRST_OR_CORRESPONDING(AuthorRole.FIRST_OR_CORRESPONDING),
    /** The social/economic family counts "autor sau coautor" — no principal-author test. */
    ALL_AUTHORS(AuthorRole.ALL);

    private final AuthorRole authorRole;

    PrincipalAuthorRule(AuthorRole authorRole) {
        this.authorRole = authorRole;
    }

    /** The indicator role that implements the rule. */
    public AuthorRole authorRole() {
        return authorRole;
    }
}
