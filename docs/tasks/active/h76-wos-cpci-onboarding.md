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

## Onboarding design (built after the first CSV)

Mirror the journal Master-List onboarding, but write `wosForumIds` onto **conference** forums.

- **Parse** the Analyze export(s) → distinct CPCI venue names (conference titles + source/series titles) + counts.
- **Match** each to our forum registry, in precedence:
  1. **Acronym** — DBLP `conf/X` acronym extracted from the WoS conference title (WoS titles often embed it, e.g.
     "… International Conference on Software Engineering (ICSE)"); reuse the conference acronym/normalization already
     feeding `ComputerScienceConferenceScoringService` / `getConferenceRankings`.
  2. **Normalized title** — reuse `getConferenceRankingsByNormalizedTitle`'s normalization to match the conference
     title to our conference forum names.
  3. **Series source title** — map book-series proceedings (LNCS et al.) to the series forum where we hold one.
- **Tag** matched forums: set a `wosForumIds` sentinel (or a dedicated CPCI marker) so `applyCitationSourceSplit` and
  the forum membership reads count them as WoS-indexed. Read-side only — a derive/projection refresh, no full rebuild.
- **Report** match rate + the unmatched top-count venues (so the next export pass / manual mapping can target them);
  log dropped coverage explicitly (no silent truncation).

Slices: **S1** parse + dry-run match report (no writes) against the MVP CSV — surfaces real match rate + format ·
**S2** apply: tag `wosForumIds` on matched conference forums + projection refresh + tests · **S3** broad roster +
re-validate the WoS h spot-check (Adrian 4→5 expected if his CPCI citing venues now resolve).

## Caveats

- **Partial by construction** — title matching + export scope. Acceptable (indicative consumer). S1's dry-run match
  report quantifies it before any write.
- **No ISSN** in the Analyze export → no exact key; title/acronym matching carries false-negative risk (unmatched
  real CPCI venues) more than false-positive. The dry-run report is the guard.
- Still **does not** make the WoS h "official" — `H76` narrows the conference gap; corpus completeness is the other
  half. Hard WoS thresholds remain H67 S4b territory.
