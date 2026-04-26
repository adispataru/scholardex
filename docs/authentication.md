# Authentication

Status: active authentication and SSO configuration reference.

## Login Modes

ScholarDex supports two login paths on `/login`:

- Local account login posts to `/login` with `username` and `password`.
- Institutional sign-in starts at `/oauth2/authorization/keycloak` and uses one configured Keycloak OIDC client.

The app talks to a single Keycloak realm/client. Keycloak handles institutional identity selection and federation outside ScholarDex.

## Keycloak Configuration

Set these environment variables to enable institutional sign-in:

```env
KEYCLOAK_ISSUER_URI=https://keycloak.example/realms/scholardex
KEYCLOAK_CLIENT_ID=spring-scholardex
KEYCLOAK_CLIENT_SECRET=
KEYCLOAK_SCOPES=openid,profile,email
```

Leave `KEYCLOAK_ISSUER_URI` or `KEYCLOAK_CLIENT_ID` blank to run with local login only.

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

## Logout

Logout is app-only. `POST /logout` invalidates the ScholarDex session, deletes `JSESSIONID`, and redirects to `/login?logout`. It does not end the Keycloak SSO session.
