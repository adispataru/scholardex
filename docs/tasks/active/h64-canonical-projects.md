# H64 Canonical projects (unification across sources)

**Status:** Planning
**Created:** 2026-06-16

## Goal

Introduce a canonical **`ScholardexProject`** entity that researchers reference across **every report that
scores projects** (physics A9/A10, FEAA project budget, CS Info_D, etc.), unifying project identity +
attribution from multiple sources. **The primary value is project *unification* (one trusted project
record + who led it), not budget** — budget is one opportunistic attribute.

## Why (investigation 2026-06-16)

Projects today are loose, free-text `Grant Cercetare` activity instances (`Rol`/`Buget`/`Nume Proiect`
strings) — no shared identity, no trust, re-typed per report. Source landscape (all verified live):

| project type | identity/metadata | director/role (attribution) | per-partner budget |
|---|---|---|---|
| **EU (EC)** | CORDIS + OpenAIRE | ✗ org-only → user-declared | ✅ CORDIS `ecContribution` |
| **RO national (UEFISCDI/PN-III)** | **brainmap only** | ✅ **brainmap** (director person) | ✗ → admin/user |

- **OpenAIRE has NO Romanian national projects** (OPTILAB / `PN-III-P2-2.1-BG-2016-0046` / title all → 0;
  SERRANO → 1, so search works). OpenAIRE = EU-funder only.
- **CORDIS** has EU projects with **per-partner `ecContribution`** (confirmed UVT/SERRANO = €270k, matching
  the user-entered figure) + activityType/PIC/role + EuroSciVoc classification; free bulk CSV, no key.
- **brainmap** is the *only* programmatic source for RO national projects and is rich: acronym, code, plan,
  programme, competition, funder, domains, coordinator + partners with roles, **director as a person+role**,
  contract no./date, dates, website, bilingual abstract. **No budget shown.**

## Source strategy (post-roast)

- **brainmap → offline dump generator** (the `scopus-python/dumper.py` model), NOT a live dependency.
  Playwright login validated (natural homepage→Log In flow defeats the WAF `550`; creds from gitignored
  `scopus-python/.env`, never logged). **Gentle pacing to avoid account lock**; ToS caveat; prefer a
  UEFISCDI data-export if obtainable.
- **CORDIS → bulk import** (EU metadata + per-partner budget). Highest-value, cleanest source.
- **OpenAIRE → deferred** to "EU discovery + publication↔project links" (nice-to-have, not core).

## Pipeline (same 4 stages as publications/affiliations)

| stage | Project addition |
|---|---|
| **Events** | `cordis.import_events`, `brainmap.import_events` (+ user/admin) |
| **Source facts** | `cordis.project_facts`, `brainmap.project_facts`, `user_defined.project_facts` (+ per-source partner/contribution) |
| **Canonical facts** | `scholardex.project_facts` + **`scholardex.project_partner_facts`** (project↔affiliation+contribution, like `author_affiliation_facts`); merge by grant code with **per-field provenance**; resolve each partner to a canonical `scholardex.affiliation_facts` |
| **Projection** | `reporting_read.scholardex_project_view` + `scholardex_project_partner_fact` (keyed by affiliation) — what scoring reads |

- **Affiliation tie** reuses the existing canonical multi-source identity (`ScholardexAffiliationFact`:
  `scopusAffiliationIds[]`/`wosAffiliationIds[]`/`nameNormalized`/`aliases`/`country`): add **PIC** /
  brainmap-org-id as cross-source ids; match **PIC primary, normalized-name+country fallback** (CORDIS uses
  the Romanian legal name → normalizes onto ours).
- **Researcher↔project** = a join fact (declared role: director/responsabil/membru), mirroring
  `authorship_facts`. brainmap supplies the director person for RO projects; EU projects stay user-declared
  with **CORDIS "UVT participated ✓" verification**.

## Open decisions (settle before building — from the roast)

1. **Budget semantics**: does the indicator want the **org's contribution** or the **person's led-team
   share**? If team-share, *no source gives it* (CORDIS is org-level) → it stays user-declared and the
   "trusted budget" framing is dropped. **Resolve first** — it decides whether budget ingest is worth it.
2. **Architecture weight**: full event-sourced pipeline vs. a lighter **periodic reference-import + picker**
   (projects are low-volume slow reference data, unlike streaming publications). Pipeline gives
   provenance/auditability consistency; the lighter path is far cheaper. Justify the choice explicitly.
3. **brainmap production mechanism**: admin-run import tool vs. UEFISCDI data-export request (not a live scraper).
4. **Currency normalization** (RON↔EUR, bnr.ro rate per year — already specified in the physics methodology).
5. **Attribution trust**: keep researcher↔project role explicit + verified (existence/budget trusted, role audited).

## Decoupling

Physics A9/A10 + FEAA project indicators **ship now on the existing `Grant Cercetare`/`Buget` activity** —
this task is independent and must not block the reports.

## Exit criteria

- Canonical `ScholardexProject` exists with partners tied to canonical affiliations; CORDIS (EU) +
  brainmap (RO) ingested via offline dumps → the pipeline; per-field provenance; researcher↔project
  references with role.
- Reports that score projects can reference a canonical project; budget resolved by precedence
  (CORDIS → admin → user) with provenance shown; admin can edit/add budget + create manual projects.
- brainmap dump generation is offline, rate-limited, credential-safe; no live dependency.

## Dependencies / relation

Builds on the existing ingestion pipeline + canonical affiliation identity. Sibling to [H63](h63-openalex-enrichment.md)
(OpenAlex enrichment). Consumers: physics, FEAA, CS project indicators.
