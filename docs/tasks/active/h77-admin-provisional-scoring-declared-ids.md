# H77 — Admin provisional scoring from declared source ids (unvalidated authorship)

Planning doc (2026-06-25).

## Problem

Scoring resolves a researcher's publications via `EffectiveAuthorshipReadService.findConfirmedPublicationsForScoring`
— **only** publications the researcher explicitly **confirmed** in the workspace (`PublicationAuthorshipDecision`).
A researcher with a declared `scopusId` (populated by the staff/Scopus import) but who has **not validated their
canonical author** has zero confirmed publications → **scores 0**.

Why the declared id doesn't already resolve: `ResearcherAuthorLookupService.resolveAuthorLookupKeys` returns the
profile's `scopusId`/`wosId` alongside the canonical ids, but the review path then calls
`findAuthorsByIdIn(keys)` which matches by canonical **`_id`** — a raw `scopusId` is not a canonical id, so it
resolves to nothing. The `scopusId → canonical author` mapping is only materialized at validation time (populating
`ResearcherProfile.primaryScholardexAuthorId` / `confirmedScholardexAuthorIds`).

## Goal

Let an **admin** run a report that scores a researcher from their **declared Scopus ids** even before validation —
**read-only / provisional**: never writes the researcher's confirm/reject decisions, and is clearly labelled
"unvalidated authorship" so it is never mistaken for a validated run. (Decision locked 2026-06-25: read-only
provisional, not admin-auto-validate — the latter would pre-fill and mask the researcher's curation.)

## The real data (2026-06-25) — people come from the org roster, the bridge is name

The dev Mongo has **no `users` collection**, but the **staff/org import populated `department_affiliations`** (e.g.
Departamentul de Matematică `6a35b09…` has 15 people, keyed by UVT email `first.last@e-uvt.ro`). These people have
**no `User`/`researcherProfile`/`scopusId`** of their own — but they resolve to canonical authors by **name**
(author `name` is null; names live in `alternativeNames`, e.g. `"Barbu, Dorel"`), and **those canonical authors
already carry `scopusAuthorIds` + publications** (from the publication import). So "Scopus ids from the import" is
true at the *author* level; the org roster is the bridge, by name.

Coverage over the 15 Matematică affiliations (email→"First Last"→author `alternativeNames`, full-name match):
**9 unique matches, all 9 with `scopusAuthorIds`**; 3 homonyms (Blaga/Popovici/Sasu match 2–3 authors); 3 no-match
almost certainly **diacritics** (Casu→Caşu, Comanescu→Comănescu). So most of a department is scorable now.

**Reframed resolution** (the people source + bridge): iterate `department_affiliations` (the roster) → derive the
person name from the email local-part → match the canonical author by `alternativeNames` with **diacritic-insensitive**
normalization → **disambiguate homonyms** (prefer the author with a UVT affiliation / `scopusAuthorIds` / most
publications) → that author's `scopusAuthorIds` are the "declared Scopus ids", and its publications are the scored
set. The original `scopusId`-on-profile path still applies in environments where the import populates profiles; here
the name bridge is what's available.

## Building blocks (all present)

- `ScholardexAuthorFactRepository.findByScopusAuthorIdsIn(Collection)`, `findByOrcidIdsContains(String)` →
  canonical authors from declared source ids.
- `ScholardexProjectionReadService.findAllPublicationsByAuthorsIn(Collection)` → candidate pubs from canonical
  author ids (already used by `findWorkspaceReviewPublicationsForUser`).
- `ScoringReferenceYearContext`, the H60 scoring chain, the report-run pipeline.

## Slices

1. **Declared-id author resolution** (self-contained, unit-testable): a method that, given a `ResearcherProfile`,
   returns the distinct canonical author `_id`s from (a) the already-canonical `primaryScholardexAuthorId` +
   `confirmedScholardexAuthorIds`, and (b) **declared source ids** resolved via the author facts —
   `scopusId → findByScopusAuthorIdsIn`, `orcid → findByOrcidIdsContains` (WoS optional: add
   `findByWosAuthorIdsIn` if needed; Scopus-first). Mocked-repo unit tests.
2. **Provisional candidate publications**: `findProvisionalPublicationsForScoring(profile)` =
   `findAllPublicationsByAuthorsIn(resolvedAuthorIds)`, read-only (no `PublicationAuthorshipDecision` writes).
3. **Admin provisional report run**: an **admin-gated** entry that scores the provisional candidate set instead of
   the confirmed set. Thread an `authorshipSource ∈ {CONFIRMED, DECLARED}` flag through
   `computeReportScopedIndividualReport` (or a sibling) so BOTH the publication set AND the `researcherAuthorIds`
   used for self-citation exclusion + author-share division come from the resolved canonical authors. Persist the run
   with a new `IndividualReportRunDto.Source` value (e.g. `ADMIN_PROVISIONAL`) + a provisional marker.
4. **Surface the label** in the report view/export: "Provisional — scored from declared Scopus ids; authorship not
   validated by the researcher."

## Caveats

- **Over-inclusion is inherent.** Scoring the auto-resolved set includes any false-positive attributions the
  researcher would have rejected at validation. That is the cost of bypassing validation; the label communicates it.
  Accuracy tracks the declared `scopusId` correctness + author canonicalization (H71).
- **Security:** strictly admin-gated (the provisional path must not be reachable by a normal user scoring themselves).
- **Validation:** unit-test the resolution + provisional-pub selection; **end-to-end needs real user data** — this
  dev Mongo has no `users` collection, so the live check (e.g. a maths-department candidate) must run on the instance
  that holds the accounts.

## Decisions locked (2026-06-25)

- **Scope = per-department batch** (built on a per-person primitive): admin picks department + report definition →
  resolve the roster → score each RESOLVED person → one results table, with AMBIGUOUS/UNRESOLVED flagged (score 0).
- **Persistence = reuse `UserIndividualReportRun`**: subject = affiliation email, `Source.ADMIN_PROVISIONAL` + a
  provisional flag, `triggeredByEmail` = admin. The email is just an identifier (no `User`/profile needed). Reuses the
  run history + export plumbing.
- **Surface = a Thymeleaf admin page** (pick department + report, run, scored table with provisional labels + the
  unresolved/ambiguous flags).

## Refined slices (post-decisions)

- **S2 — per-person provisional scoring primitive.** Extract an authorship-resolution seam from
  `computeReportScopedIndividualReport`: an `AuthorshipContext = (publications, researcherAuthorIds)` with two
  producers — `CONFIRMED` (today: userEmail → confirmed pubs + profile ids) and `DECLARED`
  (`ProvisionalAuthorResolutionService.resolvePerson(email)` → canonical author-ids →
  `findAllPublicationsByAuthorsIn`; the same author-ids are the `researcherAuthorIds` for self-cit + author-division).
  Score with the existing indicator loop. Unit-test the DECLARED context build.
- **S3 — department batch + persistence.** `runProvisionalDepartmentReport(departmentId, reportDefinitionId, admin)`:
  loop the roster, run the per-person primitive for each RESOLVED person, persist each as a `UserIndividualReportRun`
  (`Source.ADMIN_PROVISIONAL`), collect a results table incl. the AMBIGUOUS/UNRESOLVED flags. Admin-gated.
- **S4 — admin Thymeleaf page + label.** Pick department + report → run → scored table with the "provisional —
  unvalidated authorship" label and per-person resolution status; same label on the run view/export.

## Deferred / notes

- WoS/Google-Scholar declared-id resolution: Scopus + name is the v1 driver; add later if needed.
- Diacritic-surname resolution (Casu/Comănescu) still UNRESOLVED — needs an unaccented author-name index (separate).
