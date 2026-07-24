# H84 — Researcher-flagged publication merges (flag → admin approve → durable across rebuilds)

**Status:** Scoped 2026-07-25. Driver: Florin's FedCSIS duplicate — *An analysis of mOSAIC ontology…*
appears twice in his top-publications list (verified live post-rebuild 2026-07-24):

| | survivor candidate | duplicate |
|---|---|---|
| id | `spub_2a8e2ed54968fc0f5598c63e` | `spub_82a5aa46e85a00152e7ee83d` |
| source | SCOPUS (eid 2-s2.0-83155184718) | OPENALEX (W1480837697) |
| coverDate | 2011-12-14 | 2011-01-01 |
| creator | "Moscato F." | "Francesco Moscato" |
| DOI | none | none |
| cites | 163 | 161 |

Second (latent) pair, same shape: *Enabling Model Driven Engineering… mOSAIC Ontology* (SCPE journal) —
`spub_4c9f1e25…` (SCOPUS, no DOI) vs `spub_4703e3b0…` (OPENALEX, has DOI `10.12694/scpe.v13i1.765`).

## Why they exist and why they persist

Canonical identity (`buildCanonicalPublicationId`) is a deterministic hash of, in priority order:
non-blocklisted DOI → eid → wosId → userSourceId → `title|coverDate|creator|forum`. With no DOI on the
Scopus record, the Scopus side keys on `eid|…` while the OpenAlex MINT branch
(`OpenAlexCanonicalizationService.canonicalizeOne`, byDoi empty → MINT) keys on the last-resort tuple —
different coverDate formats and creator formats guarantee different ids. No identity key can safely
auto-merge these (title-only merge over the whole corpus is dangerous), and full rebuilds replay the same
logic, so the pair resurrects forever. Hence: **human-decided merges, persisted durably, re-applied by
every rebuild** — the same pattern as `publication_dblp_evidence` + `rebuildFromEvidence`.

## Data model — `scholardex.publication_merge_decisions` (SPARED collection, like DBLP evidence)

`PublicationMergeDecision`:
- `status`: `PENDING` | `APPROVED` | `REJECTED`
- `survivor` + `duplicate` sides, each: `{canonicalId, source, sourceRecordRefs [SOURCE:recordId…],
  snapshot {title, eid, doi, coverDate, citedByCount}}` — source-record refs are the durable anchor
  (canonical ids are deterministic today, but refs survive an identity-material change, e.g. Scopus
  later adding a DOI);
- `requestedBy` (userEmail/researcherId) + `requestNote`; `decidedBy`/`decidedAt`/`decisionNote`;
- `identityHint {titleNormalized, coverYear, creatorNormalized}` — last-resort re-resolution + the
  auto-suggest key;
- `lastAppliedAt` (stamped by each executor run — observability for "did the rebuild re-apply this").

Unique index on the (unordered) canonical-id pair to prevent duplicate requests; REJECTED rows suppress
future auto-suggestions of the same pair.

## The merge executor (one service, used by live-approve AND rebuild re-apply)

`PublicationMergeService.apply(decision)` — idempotent, per-document updates (NOT insert-based; the bulk
canonicalize E11000 lesson):
1. Resolve both sides to live canonical ids: by stored canonicalId, else via `source_links` on
   sourceRecordRefs, else identityHint. Missing duplicate → already merged/no-op; missing survivor → skip
   + log (never guess).
2. Re-key onto the survivor: `source_links` (all of the duplicate's links), `ScholardexAuthorshipFact` +
   `ScholardexPublicationAuthorAffiliationFact` edges, `ScholardexCitationFact` edges BOTH directions
   (citedPublicationId and citingPublicationId), `publication_authorship_decisions`,
   `publication_dblp_evidence`. Every re-key is duplicate-aware: if the survivor already has the
   equivalent edge/decision (e.g. same citing paper, same userEmail decision — unique indexes), DROP the
   duplicate's row instead of moving it. This is what collapses Florin's 163-vs-161 citation lists into
   one deduplicated union.
3. Enrich the survivor from the duplicate where the survivor is empty: DOI (+doiNormalized — the SCPE
   pair's OpenAlex DOI lands on the Scopus survivor, letting future canon converge naturally), abstract,
   keywords, `originalForumId`, OA fields; `citedByCount` = max (monotonic, same rule as
   `bumpCitedByCount`).
4. Delete the duplicate fact; record `duplicate.canonicalId → survivor.canonicalId` in an in-memory +
   collection-backed alias lookup; mark BOTH ids projection-dirty and trigger dirty-projection rebuild.

## Durability — two mechanisms, both needed

- **Re-apply pass**: `publicationMergeService.reapplyApproved()` chained in
  `ScopusCanonicalMaterializationService` right after `dblpConferenceResolveService.rebuildFromEvidence()`
  (full-maintenance only, same guard). Rebuild re-mints both pubs from source → pass re-merges them.
- **Resurrection guard on the incremental paths**: an on-demand OpenAlex/Scopus sync would re-MINT the
  duplicate between rebuilds. Cheap seam: before writing a pub fact, both canonicalization services
  resolve the computed canonical id through the merge-alias lookup (`resolveMergeAlias(id)`) — an aliased
  id redirects to the survivor (enrich/link semantics, like the byDoi==1 foreign branch). One small hook
  per service; the V2 dual-path memory applies — hook BOTH `ScholardexPublicationCanonicalizationService`
  and `OpenAlexCanonicalizationService` (and the user-defined path for completeness).

The decision doc acts as an identity hint, enriching rebuilds rather than fighting them (per the original
H84 registration).

## Slices

**S1 — executor + durability (backend core).** Model + repository, `PublicationMergeService` (resolve,
re-key, enrich, delete, alias), materialization chaining, alias guard in both canon paths, admin REST
endpoints (`POST /admin/publications/merge` direct-merge for the two known pairs;
`GET/POST /admin/publications/mergeRequests/{id}/approve|reject`). Tests: unit (re-key dedupe on citation
+ authorship-decision collisions; enrich rules; idempotent re-apply) + the canon integration harness
(Testcontainers: seed both source facts → runFull order → assert one pub, union citations → run again →
still one). **Deliverable: Florin's two pairs merged in prod and surviving the next rebuild.**

**S2 — admin queue UI.** `admin/publication-merges.html` (pattern: conflicts.html): PENDING queue with
side-by-side compare (title/source/coverDate/DOI/eid/cites/forum), Approve/Reject with note; a
"Merged" tab showing APPLIED decisions + lastAppliedAt; a manual "merge two ids" form (admin-initiated,
no researcher request). Controller → facade only (Z1 architecture rule).

**S3 — researcher flow (workspace publications screen).** Two entry points in
`workspacePublications.js`:
- **Auto-suggest**: among the researcher's own effective publications, group by exact `titleNormalized`
  (coverYear ±1 tolerance) → pairs not covered by an existing decision → banner "possible duplicates (N)"
  + panel with per-pair "Request merge" (fuzzy title matching deliberately OUT of scope — exact-normalized
  covers both known pairs; revisit only on evidence).
- **Manual flag**: per-row action "Flag as duplicate of…" → picker over their own publications.
Both create a PENDING request (default survivor = the Scopus-anchored/richer side; admin can swap sides
at approval). Researcher sees "merge requested — awaiting admin" on both rows until decided.

**S4 (optional, later) — corpus-wide duplicate report.** Admin-side same-titleNormalized sweep across all
pubs (not just one researcher's) feeding the same queue. Throttled/paginated; separate decision because
the corpus-wide pair count is unknown.

## Ordering & verification

S1 → S2 → S3 (each independently shippable; S1 alone + two direct-merge curl calls already fixes Florin's
list). Verify after S1: his top-publications shows ONE mOSAIC entry with the union citation count; SCPE
pair merged with DOI backfilled onto the survivor; derive-only rebuild leaves both merged; a forced
re-sync of W1480837697 does NOT resurrect the duplicate (alias guard).

## Out of scope

- Fuzzy/similarity title matching for suggestions (exact-normalized only).
- Merging FORUMS or AUTHORS (separate machinery exists: forum reconcile, H71/H72).
- Unmerge/undo — an approved decision can be flipped to REJECTED to stop re-apply, but the executed merge
  is only fully reversed by a rebuild after the flip (document this in the admin UI).
- Historical report runs are not rewritten — the next refresh reflects the merge.
