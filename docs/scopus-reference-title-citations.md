# Scopus reference-title citation search (H106 S5)

Second phase of every Scopus citations sync (`ScopusCitationsUpdate` task). The first phase asks Scopus
`REF(<eid>)` per work, which returns only references Scopus has **linked** to that document — the same set
Scopus's own "Cited by" count sees. This phase searches the reference **text** instead and finds what the
link-based pass cannot:

- citations of works that are not Scopus documents (arXiv items reached through OpenAlex): there is no EID
  to link to;
- references Scopus failed to link (mangled author strings such as `Forti��, A.E.`, truncated titles, wrong
  years) — these leave no count mismatch, because the cited-by count counts linked references only;
- fresh citing documents whose reference-linking batch has not run yet.

## Flow

1. `ScopusReferenceTitlePlanner` builds one item per work of the author (EID or not) with ≥ 3 title words:
   the exact title, the author's surname variants (from the canonical author's names, plus the
   diacritics-stripped form: `Fortiș` → `Fortiș`, `Fortis`), the newest citing cover date already known
   (sync mode applies as in the EID pass: FULL = no floor, PERIOD = period start) and the citing EIDs already
   held, so the service never re-reports them.
2. `POST /v1/citations/by-title` on the Python service runs, per item,
   `REFTITLE("<title>") AND (REFAUTH(<s1>) OR REFAUTH(<s2>) …) AND PUBYEAR > <floor-1>` (loose phrase, not
   `{exact}`: references drop subtitles and punctuation), drops known EIDs, and **verifies every remaining hit**
   against its own reference list (`AbstractRetrieval(eid, view="REF")`): the reference's title or full text
   must contain the normalised title AND a surname (a surname of ≥ 5 letters also matches on its stem minus
   the last letter, so truncated references still pass). Items come back with `verified`,
   `matched_reference` and `search_query`.
3. The scheduler ingests **verified hits only** as a PUBLICATION event (`SCOPUS_PYTHON_REFTITLE_PUBLICATION`)
   plus a CITATION event (`SCOPUS_PYTHON_REFTITLE_EDGE`, payload carries `provenance=SCOPUS_REFTITLE` and
   the matched reference text). Unverified hits are counted and logged, not stored. The task message ends
   with `Reference-title pass: N new citation links from M works (K unverified hits skipped).`; a failure of
   this phase is reported in the message and never fails the EID pass.

## Cited-side key (`CitedWorkKey`)

`ScopusCitationFact.citedEid` keeps holding a Scopus EID for Scopus works. For a cited work without an EID
it holds `doi:<normalized doi>` (resolved through `doiNormalized`; stable across full rebuilds because the V2
canonical id derives from the DOI) or, for DOI-less works, the canonical `spub_…` id. Both canon paths
resolve all three shapes: the incremental `ScholardexCitationCanonicalizationService` (scheduler batches) and
the V2 `CanonicalGraphBuilder.buildCitations` (full rebuild). The citing side is always a Scopus EID.

## Cost

One extra Scopus search per work per sync (doubles the search calls) plus one abstract retrieval per NEW hit.
Known citing EIDs are excluded before any retrieval, so a steady-state sync retrieves almost nothing.

## Verifying in production

Run a FULL citations sync for a researcher with known gaps (alexandra.fortis@e-uvt.ro: the ontologies arXiv
paper should gain the Web Intelligence 2019 and the ICIT 2016 citations; the Smart Glasses paper the FSI
Digital Investigation 2026 one), then check the task message and
`db.getCollection("scopus.citation_facts").find({source: "SCOPUS_PYTHON_REFTITLE_EDGE"})`.
