package ro.uvt.pokedex.core.model.user;

/**
 * H105: what kind of account a {@link User} is.
 *
 * <ul>
 *   <li>{@link #INSTITUTIONAL} — UVT staff (the default; every pre-H105 user, and every account
 *       provisioned from an institutional token).</li>
 *   <li>{@link #EXTERNAL} — a candidate to a UVT position (abilitare, concurs) who is not UVT staff.
 *       Such an account has an "applicant affiliation" to the target department so the department's
 *       reports resolve and its head/dean can see the candidate, but it never counts in the
 *       department's numbers (roll-ups, comparison, promotion board, cockpit) and can only ever hold
 *       the RESEARCHER authority.</li>
 * </ul>
 *
 * <p>Set by an admin today (S2); S1 will stamp it from the Keycloak token at provisioning.</p>
 */
public enum AccountKind {
    INSTITUTIONAL,
    EXTERNAL
}
