# H76 — WoS Conference Proceedings Citation Index (CPCI) onboarding

Planning doc (2026-06-25). Parent: `H76` in `TASKS.md`.

## The sourcing problem (why this stalled)

There is **no downloadable CPCI master list.** Clarivate curates *journals* as a list (the Master Journal List →
our `data/wos/mjl/{SCIE,SSCI,AHCI,ESCI}.csv`). Conference proceedings are selected and indexed **per-event** and
exist only inside Core Collection **records**. Searching mjl.clarivate.com or the coverage docs therefore redirects
to journals / scope pages — the artifact being hunted does not exist. The CPCI venue roster has to be **derived from
records**, via either the Analyze-Results view (UI) or the API. We have **UI-only** access → Analyze route.

## What the consumer needs

The WoS h-index (`H67`) classifies each **citing** paper's venue as WoS-indexed. Journals resolve via the Master
List; conference forums currently always resolve `wos=false` (all 26,338 WoS forums are journals; ~1,014 conference
forums are Scopus-indexed but WoS-unknown). H76 tags WoS-CPCI conference forums with `wosForumIds` so the WoS h /
WoS-conference scoring stops undercounting. The numbers stay **indicative** (corpus completeness + partial match).

## Export recipe (WoS web UI — the user runs this)

1. webofscience.com → **Web of Science Core Collection**.
2. Run a broad search that returns proceedings — e.g. Advanced Search `DT=(Proceedings Paper)`, or for the MVP add an
   affiliation filter (below).
3. Left rail **Refine results → Web of Science Index** → tick **Conference Proceedings Citation Index – Science
   (CPCI-S)** and **– Social Science & Humanities (CPCI-SSH)** → Refine.
4. Top of results → **Analyze Results**.
5. Field = **Conference Titles** → set *Results count* high, *Minimum record count* = 1 → **Download data table**
   (tab-delimited: field value + record count).
6. Repeat field = **Source Titles** (catches the book-series proceedings: LNCS, CCIS, AISC/LNNS, Procedia CS,
   AIP Conf Proc, IEEE/ACM proceedings series). Download.

Note: Analyze does **not** include ISSN/ISBN; Records export does but is capped ~1,000/export. So matching is
title/acronym-based. That's fine — see matching below.

### MVP scope first

Add an **affiliation filter** to step 2 — `OG=(University of West Timisoara)` / `OO=(Universitatea de Vest
Timisoara)` (use the WoS "Affiliations" refine to get the exact org name). This yields only the CPCI venues UVT
actually publishes in: a small, high-relevance roster to validate the whole pipeline. Then re-run **without** the
affiliation filter (broad CPCI roster) for wider citing-venue coverage once the pipeline is proven.

Deliver the downloaded `.txt`/`.tsv` files (drop under `data/wos/cpci/`). Implementation is built against the **real
export shape**, not assumptions.

## Data obtained (2026-06-25) — Records export, not Analyze

The user exported **Records** (not Analyze), which is richer: `data/wos/cpci/uvt-proceedings.csv`, **1,984 unique UVT
proceedings papers** (`DT=Proceedings Paper AND OG=West University of Timisoara`, two ≤1,000-row files merged +
de-duped on `UT`). Columns kept: `ut, doi, sourceTitle, conferenceTitle, bookSeriesTitle, issn, eIssn, isbn, year`.
Coverage: Source/Conference Title 100%, **DOI 982 (49%)**, **ISSN 1,277**, **ISBN 1,348**; 813 distinct source
titles, 821 distinct conferences. (`Web of Science Index` came through blank — irrelevant: the whole set is WoS-Core
proceedings by construction.)

## Onboarding design — DOI-first record matching (stronger than venue-name matching)

These are **UVT papers = the same corpus we hold**, so match WoS *records* to our *publications/forums* (exact keys),
not WoS venue *names* to our forum names (fuzzy). Tag the matched forum WoS-indexed. Precedence:

1. **DOI** (exact, ~982) — normalize (`ScholardexPublicationCanonicalizationService.normalizeDoi`) → our publication →
   its forum is WoS-CPCI-indexed. Zero fuzziness.
2. **ISSN / eISSN / ISBN** (~467 distinct) → our forum directly (series like AIP `0094-243X`, AISC `2194-5357`).
3. **Conference title / acronym** (43% carry a trailing parenthetical acronym) → CORE normalized-title / DBLP
   `conf/X` fallback for records with neither DOI nor a matching ISSN/ISBN.

- **Tag** matched forums: set a `wosForumIds` sentinel so `applyCitationSourceSplit` + the forum membership reads
  count them WoS-indexed. Read-side only — derive/projection refresh, no full rebuild.
- **Report** match counts per key + distinct forums tagged + distinct already-WoS (journals mixed into the source
  titles are harmless no-ops) + top unmatched venues. No silent truncation.

Slices: **S1** parse + **dry-run match report** (no writes) via a maintenance endpoint — DONE · **S2** title-containment
matching + apply (tag `wosCpciIndexed`) — DONE (applied live) · **S3** broad roster (same recipe minus `OG=`) +
projection refresh + re-validate the WoS-h spot-check (Adrian 4→5 if his CPCI citing venues now resolve).

## S2 result (2026-06-25) — applied live to `scholardex`

- **Title-containment matching** added (WoS conf/source title ⊆ forum name, ≥30 chars, shortest forum on ties): lifts
  net-new **182 → 211** by recovering per-edition Scopus proceedings forums (SYNASC etc.) that exact-title equality
  missed. Live dry-run == the Python estimate exactly (DOI 917, ISSN/ISBN 273, exact-title 14, containment 98).
- **Marker = a new `ScholardexForumFact.wosCpciIndexed` boolean**, NOT `wosForumIds` (which is unique-indexed +
  joined as WoS journal ids by the B2 projection). Only `applyCitationSourceSplit` reads it → a citation from a CPCI
  conference counts as WoS-venue.
- **Applied via a throwaway agent-dev instance** (`:8181`, all three schedulers disabled so it couldn't double-poll
  the live `:8080`): `POST /admin/initialization/wos/cpci/apply` → **211 forums tagged** (verified in Mongo: IEEE
  Semiconductor Conf/CAS, Ultrasonics Symposium, INDIN, Neural Networks proceedings — the Scopus-but-not-WoS CS/eng
  venues we targeted). Idempotent (re-apply tagged 0). Instance stopped.

**NOT yet visible:** `wosCitationCount` / the WOS-venue h-index only recompute on a **projection refresh**
(`applyCitationSourceSplit` runs during projection build). Deferred so as not to rebuild on the shared Postgres while
`:8080` is serving — run the reporting projection refresh when convenient, then the WoS-h spot-check (Adrian 4→5).

## S1 result (2026-06-25) — dry-run against live `scholardex` (74,908 forums / 149,899 pubs)

`WosCpciOnboardingService` built + tested + exposed at `POST /admin/initialization/wos/cpci/dryRun`. The headline
numbers below were produced by a **faithful Python replica of `match()`** against the live DB (the running `:8080`
app predates the endpoint; the Java service is equivalent by construction — same normalizers):

- 1,984 records → **1,204 matched (61%)**: DOI 917, ISSN/ISBN 273, title 14; **780 unmatched (39%)**.
- **263 distinct forums** hit; 81 already WoS (journals/series via the Master List — no-op, confirms no double-tag);
  **182 NET-NEW WoS conference forums** would be tagged.
- DOI carried 76% of matches — the DOI-first design was the right call.

**Unmatched splits two ways:** (a) venues we hold **no forum** for (niche SSH proceedings — un-taggable, fine);
(b) **recoverable misses** where the forum exists but the title matcher missed it — e.g. **SYNASC** (20 per-edition
forums, `scopus=True, wos=False`, the exact H76 target): our forum is `"Proceedings - 9th International Symposium …
SYNASC 2007"`, the WoS title is `"18th International Symposium …"` — the `Proceedings -` prefix + embedded edition
number + trailing acronym/year defeat exact normalized-title equality, and SYNASC has no DOI/series-ISSN. **S2 fix:
add acronym extraction/matching** (both sides carry "SYNASC") + strip `Proceedings`/embedded ordinals so per-edition
Scopus forum names match. Expect 182 net-new to rise once acronym matching lands.

## Caveats

- **Partial by construction** — title matching + export scope. Acceptable (indicative consumer). S1's dry-run match
  report quantifies it before any write.
- **No ISSN** in the Analyze export → no exact key; title/acronym matching carries false-negative risk (unmatched
  real CPCI venues) more than false-positive. The dry-run report is the guard.
- Still **does not** make the WoS h "official" — `H76` narrows the conference gap; corpus completeness is the other
  half. Hard WoS thresholds remain H67 S4b territory.
