# Scholardex Core

Scholardex Core is a Spring Boot application for managing and reporting academic research activity data (researchers, publications, rankings, and related reporting flows).

## Tech Stack

- Java 21
- Spring Boot 3.2.4
- Gradle (wrapper included)
- MongoDB

## Prerequisites

- JDK 21 installed and available on `PATH`
- MongoDB instance accessible from this app
- Optional: environment-specific overrides for credentials and API keys

## Run Locally

```bash
./gradlew bootRun
```

Application config entrypoint:

- `src/main/resources/application.properties`

For local development, prefer overriding sensitive values using environment-specific configuration and avoid committing real credentials or API keys.

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

## Repository Workflow

- Branch naming: `codex/*` for agent-driven branches
- Target branch for merge: `main`
- Commit convention: Conventional Commits (examples: `feat: ...`, `fix: ...`, `chore: ...`)
- Collaboration and guardrails: see `AGENTS.md`
- Contribution process: see `CONTRIBUTING.md`
