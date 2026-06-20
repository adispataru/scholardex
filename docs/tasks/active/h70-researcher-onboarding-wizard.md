# H70 — Researcher onboarding wizard (+ de-tangle the publication-claim tool)

## Goal

Replace today's confusing, all-controls-at-once onboarding (buried in the workspace **Profile & Sync** tab) with a
**resume-aware stepped modal wizard**, and **separate onboarding from ongoing publication-claim management**. After
onboarding, the claim tool becomes a clean standalone surface for curating *new* publications — not a place you also
have to do identity/affiliation setup.

## Why now / problem

- **Onboarding and claim are welded together by a hidden gate.** `PublicationAuthorshipDecisionService
  .requiresAffiliationScopeConfirmation()` throws until `profile.affiliationsConfirmedAt` is set — so a researcher
  cannot review *any* publication until they've confirmed affiliations, with no guided flow telling them so. This is
  the root of the "confusing, lots of back-and-forth" feeling.
- The current Profile & Sync UX is JS-rendered (minified `static/assets/app.js`), shows every control at once
  (name, scopus/wos id arrays, affiliation checkboxes, three sync buttons), and has no notion of "what's the next
  step." ORCID is effectively hidden (only used implicitly by the OpenAlex sync).
- The staff CSV import (done) already populates scopus ids → **51/55 imported FMI staff auto-resolve to their
  canonical authors**; only 4 lack a scopus id. So onboarding's job is mostly *confirm + enrich + claim*, not
  *link from scratch*.

## Login model (decided)

- First login is **Keycloak SSO** (e-uvt.ro). Imports assume SSO. Local accounts with the same email may coexist as
  a fallback. The wizard operates on the `User` keyed by email; **verify during build that SSO login resolves to the
  imported `User` by email** (the import created the account; SSO must match it, not mint a duplicate).

## Design — the wizard

A modal in the workspace. On load it computes onboarding completeness and **opens at the first incomplete step**
(extends the existing 0–100% completeness score). Auto-prompts on login when onboarding is incomplete; dismissible;
re-openable from the profile area. Each step has a GET helper (candidates) + a POST (persist), mirroring the existing
`PublicationWizardFacade` convention.

| Step | Does | Reuse / New |
|---|---|---|
| **1 · Identity ids** | Scopus/WoS ids (usually pre-filled from import). Confirm or add. | Reuse `profile.scopusId`/`wosId` |
| **2 · ORCID** | Enter + `OrcidSupport.normalize` → save → kick OpenAlex author sync (async enrichment) | Reuse `profile.orcid` + `POST /profile/sync/openalex-authors`; **new: surface it** |
| **3 · Affiliations** | Show *observed* affiliations (resolved authors' `affiliationIds`) → confirm current/past → **set `affiliationsConfirmedAt`** (unblocks the claim tool) | Reuse observed-affiliation lookup; **new: clean confirm/deny UI** |
| **4 · Match author record(s)** | Show candidate canonical authors (resolved by scopus/orcid) → researcher confirms which are them → persist a **confirmed set** + designate a primary | **New — no picker today; resolution is silent** |
| **5 · Publication auto-claim** | With identity pinned, fetch candidates, score confidence (**author-id + affiliation overlap only — ignore names, they have artefacts**), present a recommended **bulk confirm/deny** → one click applies; tail flagged for review | Reuse `EffectiveAuthorshipReadService` + bulk-decision; **new: recommendation engine** |

Wizard works off **data already in the system** (the import already resolved most). ORCID/OpenAlex/Scopus syncs are
async enrichment kicked during the flow; their results improve recommendations on a later re-open — they are **not**
blockers for completing onboarding.

## Model change (decided)

- **Add `confirmedScholardexAuthorIds: List<String>` to `User.ResearcherProfile`** — the researcher's confirmed set
  of canonical author records (e.g. Dana Petcu's two). `primaryScholardexAuthorId` stays as the designated one of
  that set. **Non-destructive**: no canonical author merge from a user action; lookup keeps pooling via the set.
- `resolveAuthorLookupKeys` extends to include `confirmedScholardexAuthorIds` (additive to scopus/wos/primary).
- Hard-merge of same-person author records stays the job of the reconcile pass (`AuthorReconcileService`), not the
  wizard.

## Auto-claim confidence model (decided)

A candidate publication is **recommend-confirm** iff it is attributed to a **confirmed author id** AND shares an
**affiliation** with the researcher's confirmed affiliation set. **No name matching** (mojibake / name-order
artefacts make names unreliable — same reason the fuzzy author dedup leans on co-author overlap, not names).
Everything else → **recommend-review** (not auto-denied). One-click "accept all recommendations" applies the bulk
confirm; the researcher can still adjust.

## Claim-tool bug fixes (in scope)

Fix the back-and-forth bugs the exploration found, so the separated claim tool is solid:
1. **Orphaned decisions on re-sync** — a rejected pub re-synced under a new `publicationId` loses its decision; the
   old REJECTED record no longer applies. Need decision continuity keyed on a stable identity (EID/DOI), not the
   transient `publicationId`.
2. **Bulk-reject fails if any selected pub already has a decision** (`PublicationAuthorshipDecisionService` ~line
   147 only accepts PENDING in bulk) — make bulk idempotent / allow re-decision.
3. **Affiliation gate throws instead of guiding** — once the wizard owns affiliation confirmation, the claim tool
   should assume it's done; if not, route to the wizard rather than throwing `IllegalStateException`.
4. Snapshot staleness (decision `snapshot` captures title/DOI at decision time) — note + refresh-on-read or
   re-snapshot policy.

## Separation of concerns (the core re-architecture)

- **Onboarding wizard** = one-time identity + affiliations + initial bulk back-catalog claim. Sets
  `affiliationsConfirmedAt`, the confirmed author set, the primary.
- **Claim management tool** = ongoing curation of *new* candidates from later syncs. Standalone; assumes onboarding
  done; no identity/affiliation setup mixed in.
- The **Profile & Sync tab** slims to: edit identity fields + trigger syncs + see task status. The onboarding steps
  move into the wizard.

## Slices

1. **Model + resolve** — add `confirmedScholardexAuthorIds`; extend `resolveAuthorLookupKeys`; onboarding-completeness
   computation (which step is next). Tests.
2. **Wizard shell + steps 1–3** (identity ids, ORCID + surface OpenAlex sync, affiliation confirm → sets the gate).
   Mostly reuse; the win is the guided flow + unblocking the gate cleanly.
3. **Step 4 — author-record matcher** (candidate authors by scopus/orcid → confirm set + primary). New endpoint +
   picker UI.
4. **Step 5 — auto-claim recommendation engine** (author-id + affiliation confidence → recommended bulk confirm/deny
   → one-click apply). New service + UI.
5. **Claim-tool de-tangle + bug fixes** (decision continuity on re-sync; idempotent bulk; gate-routes-not-throws;
   snapshot policy). Slim the Profile & Sync tab.

## Exit criteria

- A researcher logging in via SSO with an incomplete profile is guided through the wizard, resuming at the first
  incomplete step, and finishes with: confirmed author set + primary, confirmed affiliations
  (`affiliationsConfirmedAt` set), and the back-catalog bulk-claimed by recommendation.
- The claim tool works standalone for new pubs without re-doing identity/affiliation, and the three bugs are fixed
  (re-sync keeps decisions; bulk is idempotent; the gate guides instead of throwing).
- 51/55 imported staff complete onboarding with **zero manual id entry** (scopus already in); the 4 without scopus
  resolve via ORCID/OpenAlex in step 2–4.

## Out of scope / deferred

- Destructive canonical author merge from the wizard (stays in the reconcile pass).
- Google Scholar onboarding (that is **H20**).
- Name-based publication matching (explicitly rejected — artefacts).

## Open assumptions to verify during build

- SSO email → existing `User` resolution (no duplicate account minted on first SSO login).
- The minified `app.js` build path for the new wizard UI (confirm where the workspace JS source lives / how it's
  built before adding a modal).
