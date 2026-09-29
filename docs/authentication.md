# Authentication

Status: active authentication and SSO configuration reference.

## Login Modes

ScholarDex has no local password login (H84): form login and HTTP basic are disabled in `WebSecurityConfig`, and
every sign-in goes through one Keycloak OIDC client.

- Default: `/oauth2/authorization/keycloak`, with `kc_idp_hint` (default `google`) so the user lands directly on the
  brokered Google Workspace identity provider.
- Break-glass: `/oauth2/authorization/keycloak?direct`, without the hint, for a realm-local Keycloak user.

The app talks to a single Keycloak realm/client. Keycloak handles institutional identity selection and federation
outside ScholarDex. **The application does not check the email domain:** any verified email Keycloak lets through is
provisioned as a `RESEARCHER`, so restricting sign-in to the university's accounts is the realm's job (the Google
identity provider's hosted domain).

## Keycloak Configuration

Set these environment variables to enable institutional sign-in:

```env
KEYCLOAK_ISSUER_URI=https://keycloak.example/realms/scholardex
KEYCLOAK_CLIENT_ID=spring-scholardex
KEYCLOAK_CLIENT_SECRET=
KEYCLOAK_SCOPES=openid,profile,email
```

With `KEYCLOAK_ISSUER_URI` or `KEYCLOAK_CLIENT_ID` blank there is no way to sign in; local development uses the
`agent-dev` profile instead.

The Keycloak client must allow the authorization-code flow and this redirect URI:

```text
{app-base-url}/login/oauth2/code/keycloak
```

For local development that is normally:

```text
http://localhost:8080/login/oauth2/code/keycloak
```

## Local User Bridge

After Keycloak login succeeds, ScholarDex requires a verified email claim:

- `email` must be present and non-blank.
- `email_verified` must be `true`.

The email is normalized to lowercase before local lookup. Existing local users keep their local roles and profile. First-time Keycloak users are created as `RESEARCHER` accounts with a generated password that is not shown to the user. Keycloak roles and groups are ignored; role elevation remains local/admin-managed.

Locked local users cannot sign in through Keycloak.

### Supervisor rights come from the appointment

A user who heads a faculty or a department, or supervises a group, signs in with the `SUPERVISOR` authority even when the account stores only `RESEARCHER`. The right is derived at sign-in from the unit (`headUserIds`, group supervisors) and is never written to the account, so appointing or removing a head is the only step; it takes effect at that person's next sign-in. A `SUPERVISOR` role stored on an account keeps working as before. External candidate accounts never receive it.

What a head may open is decided per unit: a department director reaches the department, a faculty head reaches the faculty and every department in it. The report pages of a unit live under `/admin/divisions/{id}/reports/**` and `/admin/departments/{id}/reports/**`; they are the only paths under `/admin` open to supervisors besides `/admin/groups/**`.

### Who decides on a declaration of principal authorship

A researcher may declare that they are a principal author of a publication where no data source shows it (corresponding author, or a contribution equal to the first author's). The declaration counts in the reports only after it is approved on `/supervisor/declarations`. The URL rule lets supervisors and platform admins reach the page; which declarations each of them sees and may decide is checked per researcher by `declarationAccess.canDecide`: a head of a department the researcher is affiliated to, a head of the faculty above it, or a platform admin. A supervisor of a group is not enough. Nobody decides on their own declaration, a platform admin included, so a department director's declarations go to the faculty.

## Logout

Logout is app-only. `POST /logout` invalidates the ScholarDex session, deletes `JSESSIONID`, and redirects to `/login?logout`. It does not end the Keycloak SSO session.
