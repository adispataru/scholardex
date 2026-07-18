# Deployment checklist — RDI cluster (rdi-stage → rdi-prod)

Companion to the infra side's `rke2-overmind/tenants/scholardex/DEPLOYMENT-ANSWERS.md`
(2026-07-16). That file answers the platform questions; this one records the app-side contract
and the day-one procedure. Staging namespace `scholardex` on rdi-stage is live with both DBs and
the data PVC.

Public URL: **https://scholardex.rdi.info.uvt.ro** (Traefik TLS, Let's Encrypt, internet-reachable).

## Images (GHCR)

| Image | Source | Notes |
|---|---|---|
| `ghcr.io/adispataru/scholardex` | repo root `Dockerfile` (CI runs `bootJar` first) | temurin 25 JRE, non-root, `WORKDIR /app` |
| `ghcr.io/adispataru/scholardex-scopus-python` | `scopus-python/Dockerfile` | port 65008, Scopus-free healthcheck |

Built and pushed by `.github/workflows/ghcr.yml` on every push to main (tags: commit SHA — the
tag `deploy-helm.yml` consumes — plus `latest` on main and semver on `v*` tags). Tests are gated
by `quality-gates.yml`, not duplicated in the image build. Deploys run via the in-cluster GitHub
Actions runner (ns `scholardex-ci`); no on-host builds.

⚠️ `deploy-helm.yml` predates the cluster decisions: it references a `charts/core` directory that
does not exist yet and `core-staging`/`core-production` namespaces instead of `scholardex`, and
assumes kubeconfig secrets while the platform uses the in-cluster runner. Reconcile it together
with the Helm chart when the chart is written.

## Environment contract (core app)

Secrets come from Kubernetes Secrets (`envFrom`/`secretKeyRef`), config from ConfigMaps — never git.
⚠️ The namespace quota rejects any container without explicit resources — set requests/limits on
every container, including one-shot Jobs. Suggested app pod: requests 1 CPU / 5Gi, limits 4 CPU / 6Gi.

| Env var | Value (staging/prod) | Source |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `postgres` | ConfigMap |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `framework` | ConfigMap — required behind Traefik |
| `SPRING_MONGODB_URI` | `mongodb://scholardex:$(MONGO_PASSWORD)@scholardex-mongo.scholardex.svc:27017/scholardex?authSource=admin` | secret `scholardex-db` (verify authSource against how the user was created) |
| `POSTGRES_URL` | `jdbc:postgresql://scholardex-postgres.scholardex.svc:5432/scholardex` | ConfigMap |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `scholardex` / — | secret `scholardex-db` |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | bootstrap admin (seeds only an EMPTY user collection) | secret (deliver via §12 channel) |
| `USER_DEFAULT_PASSWORD` | initial password for staff-import accounts | secret |
| `KEYCLOAK_ISSUER_URI` | `https://aai.rdi.info.uvt.ro/realms/scholardex` | configmap `scholardex-oidc-config` (exists) |
| `KEYCLOAK_CLIENT_ID` | `scholardex` | configmap (exists) |
| `KEYCLOAK_CLIENT_SECRET` | — | secret `scholardex-oidc` (exists) |
| `SCOPUS_PYTHON_BASE_URL` | `scholardex-scopus-python.scholardex.svc:65008` | ConfigMap |
| `OPENALEX_MAILTO` | institutional service mailbox | ConfigMap |
| `DBLP_DUMP_FILE` | `/app/data/dblp-2026-03-01.xml.gz` | ConfigMap (env overrides the committed dev path — Spring gives env vars precedence over application.properties) |

Data PVC `scholardex-data` (20Gi, harvester-retain) mounts read-only at **`/app/data`** so the
relative `data/…` paths in application.properties resolve unchanged (standards HTML, CORE/SENSE/
predatory lists, WoS JSON years, OpenAlex bulk, DBLP dump).

scopus-python container env: `SCOPUS_API_KEY` (secret — the rotated key, never the one in git
history), optional `SCOPUS_INST_TOKEN` (fallback if the egress IP `194.102.63.25` isn't in
Elsevier's entitlement — decided by the staging smoke test).

## Health & monitoring wiring

- Kubernetes probes: liveness `/actuator/health/liveness`, readiness `/actuator/health/readiness`.
  `scopusPython` is intentionally **not** in the readiness group (it must not flap the pod or page
  anyone); it is observable at `/actuator/health/integrations` — chart it, don't alert on it.
- Blackbox probe on `/actuator/health` pages on down; optional ServiceMonitor for metrics.

## Initial data transfer (one-off, over VPN)

Dev DB is dump-ready as of 2026-07-18 (Carberry QA corpus purged, projections rebuilt at epoch 9,
96 runs refreshed under the current scoring rules).

1. `mongodump --db scholardex --archive | kubectl exec -i <mongo-pod> -- mongorestore --archive --nsInclude 'scholardex.*'`
   (dump ONLY `scholardex` — the local Mongo also holds `scholardex_h66`, `test`, and
   `autoped_cutover_before`, which must not ship).
2. `pg_dump core | kubectl exec -i <pg-pod> -- psql scholardex` (or restore then run the in-app
   Postgres projection full rebuild instead: `POST /admin/initialization/postgres/projection/runFull`).
3. `data/` folder (+ the DBLP dump) → helper pod mounting PVC `scholardex-data`, filled via `kubectl cp`.
4. **Immediately change the admin password** — the restored dump carries the dev credentials, and
   the `ADMIN_PASSWORD` seeder only fires on an empty user collection.

## Day-one smoke tests (staging first, then prod)

1. Pod starts; `/actuator/health/liveness` UP; readiness turns UP after startup.
2. Open the site through the public URL: login page renders over https, no mixed-content, no
   `http://` redirects (forward-headers proof).
3. Keycloak round-trip: "Continue with institutional account" → broker → back, account provisioned
   with the `@e-uvt.ro` email.
4. **Scopus smoke** (the big unknown): trigger a publication sync for a known Scopus ID from
   Profile & Sync — expect Queued → Running → Done. A 403 `scopus_access_denied` means the egress
   IP isn't entitled → request `SCOPUS_INST_TOKEN` from Elsevier.
5. OpenAlex sync for a real ORCID: Queued → Running → Done with the "Synced N works" message.
6. Evaluation workbench renders a report with data; FV export downloads; re-importing the export
   shows a clean comparison.
7. `/actuator/health/integrations` reflects the Python service (kill the pod, watch it go DOWN
   without the app pod flapping).
8. Backups: run the CronJob once manually, then restore the archive into a scratch namespace.

## Remaining inputs (owner: app team)

1. Google OAuth client for the broker — redirect URI
   `https://aai.rdi.info.uvt.ro/realms/scholardex/broker/google/endpoint` (Adrian, Google
   Workspace console; then the IdP is wired with `hd=e-uvt.ro`).
2. GitHub Actions workflow building + pushing both GHCR images (this repo).
3. Ship the dump + `data/` per the transfer section above.
