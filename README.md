# Scholardex Core

Scholardex Core is a Spring Boot application for managing and reporting academic research activity data (researchers, publications, rankings, and related reporting flows).

## Tech Stack

- Java 25
- Spring Boot 4.0.2
- Gradle (wrapper included)
- MongoDB

## Prerequisites

- JDK 25 installed and available on `PATH`
- MongoDB instance accessible from this app
- Node.js/npm (for frontend asset checks/build)
- Optional: environment-specific overrides for credentials and API keys

## First-Run Quickstart

1. Install frontend dependencies:

```bash
npm install
```

2. Build/verify frontend assets:

```bash
npm run build
npm run verify-assets
npm run verify-template-assets
```

3. Compile backend:

```bash
./gradlew compileJava
```

4. Run app:

```bash
./gradlew bootRun
```

5. Optional smoke check in another terminal:

```bash
./gradlew test --tests "*CoreApplicationTests"
```

## Run Locally

```bash
./gradlew bootRun
```

Application config entrypoint:

- `src/main/resources/application.properties`

For local development, prefer overriding sensitive values using environment-specific configuration and avoid committing real credentials or API keys.

Common override options:

1. Environment variables (Spring relaxed binding), for example:
   - `SPRING_DATA_MONGODB_URI`
   - `SCOPUS_API_KEY`
   - `POSTGRES_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`
   - `SPRING_PROFILES_ACTIVE=postgres`
2. Runtime args:
   - `./gradlew bootRun --args='--spring.data.mongodb.uri=mongodb://localhost:27017/scholardex'`
3. Extra config file:
   - `./gradlew bootRun --args='--spring.config.additional-location=optional:file:./config/local.properties'`

### Local `.env` setup (recommended)

The app now auto-loads an optional project-root `.env` file via:

- `spring.config.import=optional:file:.env[.properties]`

Setup:

1. Copy `.env.example` to `.env`.
2. Update DB credentials/URLs for your machine.
3. Start from IntelliJ or terminal (`./gradlew bootRun`).
   - IntelliJ note: keep the Run Configuration working directory at the project root so `.env` is discovered.

For PostgreSQL profile testing with your brew install, ensure:

- `SPRING_PROFILES_ACTIVE=postgres`
- `POSTGRES_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD` are valid
- `APP_REPORTING_READ_STORE=mongo` unless you intentionally cut over reads

## Run Tests

```bash
./gradlew test
```

## Frontend Assets

This repository is migrating from `/vendor/*` references to bundled `/assets/*` files.

Commands:

```bash
npm install
npm run build
npm run verify-assets
npm run verify-template-assets
```

Generated assets are served from:

- `src/main/resources/static/assets/`

## Health Endpoints

Actuator probes are available at:

- `/actuator/health`
- `/actuator/health/liveness`
- `/actuator/health/readiness`

Other actuator endpoints are restricted by security policy.

## Troubleshooting

1. App fails on startup with Mongo errors:
   - verify Mongo is reachable and `spring.data.mongodb.uri` (or equivalent properties) points to a valid instance.
2. Frontend/template asset checks fail:
   - run `npm run build`, then rerun `npm run verify-assets` and `npm run verify-template-assets`.
3. Security/login redirects look wrong locally:
   - confirm you are using `/login` and that cookies are enabled for localhost.
4. Guardrail/CI parity check:
   - run `npm run verify-h09-baseline` before opening CI-sensitive PRs.

## Repository Workflow

- Branch naming: `codex/*` for agent-driven branches
- Target branch for merge: `main`
- Commit convention: Conventional Commits (examples: `feat: ...`, `fix: ...`, `chore: ...`)
- Collaboration and guardrails: see `AGENTS.md`
- Contribution process: see `CONTRIBUTING.md`
