# H145 — input integrity: users enter data, the platform and the experts rank and score

Status: **IN PROD 2026-10-03** (image fd205487; Adrian ran `h142_slice3_events.js` and `h144_h145_release.js --restart`;
prod verified read-only — see "Rollout, done"). Everything shipped in ONE push with H144 and H142 slice 3
(Adrian, 2026-10-03, "with everything": no separate hotfix; the access holes stay open in prod until that release).
What was built: "As built" below; what remains: "Open"; how it reaches prod: "Rollout".

Decisions (Adrian, 2026-10-03):
- **Release:** one push, the access-control fixes included.
- **Identity:** "ORCID is fine, we can match this with emails on papers, etc. Indeed, a head can approve it, but I
  don't think this matches the self-reported scores as equally dangerous" — identity (B1, B2) comes after the
  self-reported scores: ORCID accepted, corroborated by the researcher's email on papers, a head as fallback.
- **Google Scholar (I12, I14):** head-approved values (profile link and screenshot; one profile per person).
- Wizard publications that count unverified (B5) are self-reported scores in disguise (a typed title in a picked Q1
  journal earns its AIS): fixed in this release.

The rule (Adrian, 2026-10-03): "Users just input data, not ranking and scoring data. That's the platform and expert's
job. Self-declaring these opens a can of worms." A researcher states facts — title, date, the NAME of a publisher,
journal, conference, organisation or award, ISBN/ISSN/DOI, role, co-authors, a budget with evidence. Never a level, a
category, an impact factor or other metric, a coefficient, points, an "indexed in" claim, a rank, or an eligibility
judgment the standard defines. Those come from the platform's lists and derivations, from registries the experts rank
(H144), or — where no list can decide — from a request a head approves (H143), read as the APPROVED value.

## How it was reviewed

Four adversarial reviewers, read-only, on HEAD 7f2ede16 (H144 committed, not pushed), every FV except Informatică:
Comisia 28 (Psihologie 2016/2026, Științe ale Educației 2026); Comisia 25 Sociologie 2026, FEAA 2016/2026 and the
UEFISCDI PD/TE reports; Comisia 35 Muzică 2026 and every CNFIS input; Fizică 2026, Matematică 2016/2026 and the
publication side for all reports. Each mapped report → indicator → type → field → formula variable and tried to inflate
a score with values a researcher can enter. The critical findings were re-checked in the code (marked **confirmed**);
the rest are as reported, consistent across reviewers where they overlap.

## Prod exposure (read-only, 2026-10-03, counts only)

- No record carries a picked `Coeficient_m` or a typed `IF_sursa`.
- Year fields (`An_inceput`/`An_sfarsit`) only on three Informatică types, all plausible (2006–2026); no Music record.
- 8 wizard publications, none with a DOI; none landed on a publication another source holds — no overwrite yet.
- 1,091 confirmed authorships: 1,016 on publications listing one of the researcher's author ids; **73 list none** (to
  review — many may be split identities); 1 points to a missing publication.
- 77 grant records, 26 with an "international" role (from H144's survey).
- H144 and H142 slice 3 are not deployed: their flaws below (group E, except E5–E6 and E8, which H143 and H142 slice 1
  shipped) are not live.

## Findings

### A. Access control and writes — live in prod (security)

| # | Finding | Status |
|---|---|---|
| A1 | Any signed-in user can update or delete ANY activity record by id: `/user/workspace/activities/update`, `/delete/{id}` and the legacy `/user/activities/update`, `/delete/{id}` (`UserActivityInstanceFacade` 125–146: no owner check) | confirmed |
| A2 | Legacy `POST /user/activities/create` binds the whole `ActivityInstance` (`@ModelAttribute`): the request sets the owner (plants records in anyone's report), the id, `eventLevelSuggestion`, and a head's approval (`publisherClaim.status=APPROVED`), which `PublisherClaimSupport.reconcile` keeps when the requested option and evidence match | confirmed (code; not executed) |
| A3 | Single-record saves validate nothing: fields the type does not declare, any `ReferenceField`, select values outside `allowedValues`, any number (`Infinity`, `1e308`, negatives). Only the bulk review path validates | confirmed (refs); reported (rest) |
| A4 | Activity scores have no finite check (`ActivityReportingService` 186–197; publications have one): an infinite score passes every threshold and reaches org-unit roll-ups | confirmed |

### B. Publication side: identity and the shared corpus

| # | Finding | Status |
|---|---|---|
| B1 | Identity is self-declared: profile Scopus/WoS/Scholar ids, ORCID (format-checked only) and `/profile/author-match` ids are trusted by `ResearcherAuthorLookupService.resolveAuthorLookupKeys` → another author's whole record, first/corresponding roles included | confirmed |
| B2 | `/publications/{id}/authorship/confirm` (and bulk) accepts any publication; scoring counts every CONFIRMED one | reported ×3; prod: 73 confirmations list none of the researcher's ids |
| B3 | A wizard DOI equal to an existing publication's OVERWRITES that shared record: authors, author count, corresponding authors, subtype, venue (unless DBLP), date, citation count (`UserDefinedCanonicalizationService` ~205–257, 311); the UI has no DOI check; re-applied after every full rebuild | confirmed; not happened in prod |
| B4 | A wizard ISSN equal to a forum's overwrites the shared forum's name, ISSN/eISSN, ISBN, aggregation type, publisher (same service, ~105–147) | reported ×2 |
| B5 | Wizard publications score at once: DOI optional and never resolved; type, date, venue, author list and order typed; `approved`/`reviewState` are never read anywhere; authorship auto-confirmed. A real journal name with ISSN 0000-0000 inherits AIS and quartile by name | confirmed (flags unread); reported (rest) |
| B6 | Same-title items are keyed by title for display but each adds to the total (`ScientificProductionService` 133–141): k copies = k× the score, one row shown | confirmed |

### C. Scoring data typed or picked by the researcher

| # | Finding | Where |
|---|---|---|
| C1 | `IF_sursa` typed, unbounded: (0.2 + 4·IF)·2 per citation (IF 25 meets C.7 and C.9; 1e308 → ∞) | Soc I.9 declared |
| C2 | `Coeficient_m` picked (2 / 1.5 / 1) on 7 types, 9 indicators; corpus books get m derived (≤ 1.5, wizard books 1) | Soc I.2–I.8, I.17, I.19 |
| C3 | Google Scholar citations and h-index typed (`Citari_GS`, `Citari_WoS`, `h_GS`): h = 15 meets the Education professor total; blank `Citari_WoS` double-counts I11; negative adds | Psiho/Edu I12, I14 |
| C4 | CNFIS sheet header: typed CNATDCU score and Hirsch indices (GS, WoS, Scopus), frozen into copies; Music must type its score (its report has no criterion contributing to the total) | CNFIS 5/5.1–5.3 |
| C5 | Grants: international/national inside `Rol` (I24: 81 vs 27); blank `Rol` = director (FEAA criterion 4, Fizică A10, Mate C3, I25); project import defaults to director, `ec|erasmus|…` regex makes Erasmus+ international; budget without currency, evidence or bound (Fizică A10: 1e9 → 20,000); "won in competition"/programme exclusions only for linked projects | many |
| C6 | The type is the claim, flat points, nothing checked: Soc I.2 declared ("≥ 3 databases"), Music CS 2.1 declared (database pick not even read), CS 2.3 (selection committee), RIA 1.3 (indexed publications), RIA 2.1 state distinctions (40), RIA 2.2 professional/media prizes (30), CS 4.1 and DID 1.4 ("publicat" = CNCS A/B publisher, unchecked), CS 2.2 (`Tip` chooses whether the festival gate applies), C28 I7 (registration/accreditation), I25 Chair (9) / Fellowship (4.5), preregistration bonus not tied to a scored article, Soc C.10 (one empty record passes), Soc I.7 dictionary terms, Soc I.8 "fundamental work" | Muz, Soc, C28 |
| C7 | CNFIS levels and kinds picked: 5.1 kind (`Tip` wins: orchestra → "individual prize" ×10), 5.2 sport level and record, patent type from free-text office words plus an undeclared `Tip` fallback, CNFIS domain code | CNFIS |
| C8 | Music RIA 2.3 (prize, 40) ignores the event; `Rezultat` falls back to `Tip`; imports set PREMIU; imported records score before they are reviewed (`needsReview` not filtered) | Muz |

### D. Unbounded facts and defaults that favour the researcher

| # | Finding |
|---|---|
| D1 | `N_ani` unbounded both ways (start year 1 → RIA 1.1 = 20,260 from one record; end year not capped: 2020–9999 = 7,980 years); Music RIA 1.1/3.2, Info D_x — **confirmed** |
| D2 | `N_editii` (Soc I.10/I.11) and `N_numere_speciale` unbounded; indexing checked only for the record's year, so every year of an editorship is paid |
| D3 | Blank counts become 1 (`N_autori`, `N_coordonatori`: a 10-author book scores as single-author; a UEFISCDI 7c book left blank gives 60); blank selects fall into the higher branch (C28 I19, I25, FEAA, Mate C3, Soc I.12) |
| D4 | Unbounded counts and no "one per" rule (I22 `N_articole`; one role per journal/conference, one aspect per university, one GS profile, one bonus per article) |
| D5 | Music DID 2.1: empty `Durata_minute` passes; authentication code, label and broadcast proof never read |

### E. Flaws in H143/H144/H142 slice 3 (my own work)

| # | Finding |
|---|---|
| E1 | Blank, unknown or expert-rejected names still score a floor: formulas never read `Entitate_numita`/`Recunoscut` (C28 I10, I16, I18, I19, I20, I23, I29, I30; Music RIA 3.5, 3.7; Soc I.16 counts rejected names). The conference floor — Comisia 28's rule — also applies to Music (RIA 1.5: an invented name earns 10). Decision 3 ("not counted until ranked") was applied to Music only, not to I20 "de prestigiu", I10, I30.2 |
| E2 | `RegistryScoringSupport.bind` takes the best level among ALL references on a record, declared or not: a second name lifts the item (RIA 1.4/1.5 to 30 by adding a festival) |
| E3 | `N_baze_date` counts every membership row: each WoS edition separately, DBLP, OpenAlex fee rows (ESCI + DOAJ + DBLP = "3 databases") — not each standard's recognised list |
| E4 | Patent type parser: office words matched as substrings ("depozit" → EPO, "compilatie" → OMPI) and two-letter codes found in prose ("de 15.06.2021" → Germany) — **confirmed**; applications count as granted |
| E5 | A head's approval is not tied to the facts it approved: publisher, title, type, authors, m, ISSN, university, years can change afterwards and the approval stays (H143 in prod) |
| E6 | Publisher names: containment fallback ("Editura Mirton, distribuită de Polirom" → A2) and the WoS Master Book List one-word match ("Oxford", "Editura") — **confirmed** (H143/H99 in prod) |
| E7 | An expert's own-name check runs only at decision time |
| E8 | Artistic event ranks merge across domains (a top film or visual-arts event lifts a concert) — in prod since H142 slice 1 |

### F. Double counting

- Music CS 2.1: an article found AND declared counts twice; the grid import creates the declared copy.
- Declared vs corpus: Sociology `_decl` books, an I27.2 course that is also a book, an edited volume added through the
  wizard as a book (I3A 48) and declared at I17.
- CNFIS institutional tables: a concert, competition, patent or book declared by k colleagues appears k times.

### G. Formulas that differ from the standard (no user input)

Physics A threshold 1 where the standard requires ≥ 2 (Profesor, abilitare); reviews counted in I and P (the standard:
original articles; reviews belong to A2); proceedings chapters (LNCS "ch") counted as A2 chapters. Mate_C1_UVT reads
only the forum's publisher (book venues score 0) and counts a chapter as half a book; Mate C3 reaches the Profesor
threshold with member grants (the standard: two as director); Mate26 C1/C2 does not check the cited article's list A.
Fizică A7/A8: a blank `N_autori` scores 0 although the description promises Nef = 1. C28: N = 1 always at I1A/I1B/I2;
Education I8/I9 apply the two-per-edition cap per indicator. CNFIS 5.3: "Ediție critică" and "Îngrijire redacțională"
filed as translations, no publisher gate, Music DID 1.1/1.2 not reported.

## Fix plan (proposed)

1. **Hotfix, alone, from the deployed code** (groups A, D1, B3, B4, B6): owner checks on update and delete; retire the
   legacy `/user/activities/*` write routes; never take the owner, id, approval or suggestion from a client; one
   server-side validator for every write (the type's own fields and references, allowed values, finite numbers in
   range, years start ≤ end ≤ reference year, a floor); non-finite guard on activity scores; the wizard links to an
   existing publication or forum by DOI/ISSN and never overwrites it; totals keyed by publication id.
2. **Identity and authorship** (B1, B2, B5): identity keys verified (ORCID sign-in, or a head approves Scopus/WoS ids
   and author-match ids that do not follow from the ORCID); a confirmation counts only for a publication listing one
   of the researcher's verified ids, else a head approves it; wizard publications count once their DOI or ISBN
   resolves to the same work (metadata from Crossref/OpenAlex), else after a head approves; review the 73.
3. **Scoring data derived** (C, D2–D5, E — folded into H144 before its push): IF from the citing source's ISSN; m from
   the language (a fact) and the publisher's country (lists), "international peer review" by A1 publisher or request;
   GS metrics by request; CNFIS header from the run and the corpus; grants by funder/programme (H144 slice 2 pulled
   in) and linked projects; every "the type is the claim" item derived (ISSN → indexing), registry-ranked or requested;
   RIA 2.3 gated on a ranked competition; CNFIS kinds derived, sport by registry (H144 slice 3); patents from anchored
   granted codes, no `Tip`; blank counts and selects score 0; uniqueness rules; approvals snapshot the facts they
   approve; publishers picked from the lists, a non-exact name goes to a head; floors only where the standard says so,
   rejected names never; each type reads its own entity only; `N_baze_date` per the standard's list and year.
4. **Double counting and formula defects** (F, G).

## As built (2026-10-03, on top of 7f2ede16; ships with H144 and H142 slice 3)

### A. Writes
- One server-side validator for every single-record write (`ActivityRecordValidator`): only the type's own fields and
  references, select values among the allowed ones, numbers plain, finite and in a range per field (years 1950 to the
  reference year + 10, `N_ani` 1–60, counts, budgets, minutes, citations, h), text and reference lengths, at most one
  named entity, a start year after the end year refused. The workspace answers 422 with the problems.
- Update and delete check the owner (404 otherwise); the legacy `POST /user/activities/create|update|delete` routes are
  gone; a saved record never carries a client's approval (`publisherClaim` cleared on create).
- A non-finite activity score counts 0 (warning in the log).

### B. Publications
- The wizard refuses a DOI the platform already holds (409, unless it is the researcher's own wizard entry); a wizard
  entry naming a journal or a publication another source holds only links to it, never rewrites it.
- **B5:** a wizard publication counts only once verified (`WizardPublicationReviewService`, spared collection
  `scholardex.wizard_publication_reviews`, keyed by the entry's source record id): at submit, Crossref is asked when the
  entry has a DOI (same title by word overlap, a year within one, the researcher's name among the authors or editors,
  the journal's ISSN or the book's ISBN among Crossref's); otherwise a head approves it on `/supervisor/declarations`
  (title, type, year, venue with ISSN/ISBN, authors in order, DOI; the decision names the facts the page showed). The
  approval holds while the entry states the same (fingerprint of the entry as submitted). The gate sits in
  `EffectiveAuthorshipReadService.findConfirmedPublicationsForScoring` (every report, CNFIS sheet, transfer). A startup
  pass opens a pending review for each older entry (prod: the 8 books). Researchers read "added manually — counts once
  verified".
- B1, B2 (identity, the 73 confirmations): after this release, as decided. B6: not refactored; duplicates are stopped
  where they enter (DOI refusal, link-only, the review).

### C–E. Scoring data derived, approved or ranked (with H144)
- C1/C2: no typed impact factor or coefficient: `IF_revista` from the journal's ISSN, `Coef_m` from the language (a
  fact) and the country of the journal (OpenAlex) or publisher (lists).
- C3: one Google Scholar profile per researcher (`singlePerResearcher`); its values count once a head approves the
  request (profile link and evidence); I12/I14 read the approval.
- C4: the CNFIS sheet header types nothing: the score comes from a CNATDCU report the researcher sees (no typed
  fallback; another report's id is refused), the unmet criteria from the same run at the staff position (the evaluation
  page's rule), the Hirsch values from the platform (Web of Science and Scopus over the confirmed publications) and from
  the approved Google Scholar record. Music's three tables count toward its total, so its score comes from the run.
- C5: a blank role scores nothing; director-only items check the role; the project import makes a researcher director
  only when the project registry names them director. Grants by funder and programme stay with H144 slice 2.
  Exception (Adrian, 2026-10-03, H142 slice 7): for the Music concerts a blank role counts when the ensemble's size
  answers for it — alone or a group of 2–4 — since a faculty's CNFIS 5.1 sheet marks only that; 5+ needs the role.
- C6/C8: every "the type is the claim" item reads a named entity (ISSN, conference, organisation, award, event) or a
  request a head approves; RIA 2.3 needs a ranked competition; `Rezultat` no longer falls back to `Tip`; imports write
  the ensemble's size, not a kind.
- C7: CNFIS 5.1 kinds from the result, the ensemble's size and the role; patent kinds from granted codes only (a record
  without one is left out of the sheet with the reason). Open: sport levels (H144 slice 3); the CNFIS domain and the
  choice of report are still the researcher's (they should come from the staff list — see Open).
- D: `N_ani`, `N_editii` bounded (1950 to the reference year, typed `N_ani` 1–60), indexed years counted per year
  (`N_ani_WoS_Scopus`, `N_ani_BDI`); blank counts, roles and selects score 0; one record per researcher where the
  standard says one; Music DID 2.1 needs at least 45 minutes, an authentication code and a ranked publisher (D5).
- E1–E4: formulas gate on `Entitate_valida`/`Recunoscut`; no floors for blank, waiting or rejected names; a record
  reads only its own declared entity; `N_baze_date` per the standard's list (Comisia 28 its own; otherwise WoS once,
  Scopus, ERIH, DOAJ); the patent parser matches whole office words and anchored codes, applications are not granted.
- E5: a head's approval is fingerprinted with the record's facts (`PublisherClaim.facts`); a change sends it back
  (startup stamp for older approvals). E6: a publisher name with words beyond the listed name matches only when those
  words are an address (city, legal form); an exclusion (Lambert) still matches any name that contains it. E7, E8: open.

### F. Counted once
- A record that declares a publication (`publicationRecord` on 9 types: C28 I17, Sociology I.2/I.3–I.6/I.5/I.8, UEFISCDI
  7c, Music DID 1.1/1.2 and CS 2.1) and a confirmed publication of the list that is the same work (same DOI, or same
  title within a year; titles of at least three words, a subtitle allowed) count once: the record scores 0
  (`IN_PUBLICATION_LIST`, explained on the evaluation page) unless a head approved its category, and then the list's copy
  steps aside (`DeclaredPublicationCopies`, applied in the run, the indicator detail and the indicator page, before the
  affiliation filter). The course types stay out: they are shared with Informatică and Matematică, where a course and
  a book may both count.
- CNFIS institutional tables: the same patent (granted codes, else title and year), performance (work, event, year),
  sport result and humanities work (DOI, else titles and year) declared by several colleagues appears once, with the
  larger count of university authors or participants (at least the number of colleagues who declared it).

### G. Formulas against the standards (verified against OM 3019/2025 and the CNFIS guide)
- **Physics 2026:** A ≥ 2 for Profesor and for the habilitation (it was 1; Anexa 2 p. 144, Anexa 3 p. 275); I and P count
  original articles only (`docType == "ar"`; a review counted three times — A2, I, P); A2 and A5 leave proceedings papers
  out (precizare 5; new `proceedings` variable: conference-paper subtype, a Conference Proceeding venue, or a volume or
  series named "… Proceedings …" — "Lecture Notes in Physics/Mathematics" stay chapters); a patent without its author
  count scores 0 (the formula threw and logged an error; the description promised Nef = 1); C leaves out only citations
  from articles the candidate signed (`CANDIDATE_ONLY`; the text and the description said so, the kind said otherwise).
- **Mathematics 2026:** C1/C2 count citations of the candidate's list-A articles only (`citedScieIndexed &&
  !citedFeeJournal`, new variables) and leave out only the candidate's own citing articles (`CANDIDATE_ONLY`; H97 had
  mapped "candidatul … autor sau coautor" to any co-author by analogy with Informatică 2026, whose own text differs).
- **Education 2026 I8/I9 (and every per-edition cap):** an edition is the proceedings forum AND the year — a series with
  an ISSN (EDULEARN, INTED, …) was one bucket for all its years, so two papers counted in total; I8 and I9 share the two
  places (`PerEditionCapAfterPrincipal` on I9: principal-author papers first, then co-authored ones, fewest authors
  first). The admin indicator form offers every selector, so an edit no longer saves "ALL" over a cap.
- **CNFIS Anexa 5.3:** a critical edition has its own column (the guide's note 8), editorial care none (left out with the
  reason); books, edited volumes and chapters only at a FOREIGN publisher of international prestige (the CNCS list for
  the arts and humanities; KVK holdings stay the person's evidence); critical editions and translations only at a
  publisher CNCS rates A or B in the person's domain on the latest list before the edition; Music DID 1.1/1.2 reported,
  with their ISBN; a declared copy of a work the list holds is not reported twice.
- **Music's publisher category:** the CNCS lookup takes the same house only (equal words, a shorter form, or the name
  with an address), never a listed name inside a longer one.

### Open (not in this release)
- **B1, B2** — identity and the 73 confirmations listing none of the researcher's ids: next, as decided.
- **Comisia 28 N at I1A/I1B/I2** — the standard's N is the number of PRINCIPAL authors (1 up to two of them in Psychology,
  up to three in Education); the platform keeps N = 1, as the descriptions say. Counting principal authors needs the
  corresponding authors, known only from OpenAlex flags; count how many publications carry three or more before
  switching (`N_principal`, fix designed).
- **FV Matematică (2016, legacy):** C1_UVT reads the forum's publisher only (a book venue scores 0) and counts a chapter as
  half a book; C3 reaches the Profesor threshold with member grants (needs a director criterion). Only 2026 standards
  go forward (decision of 2026-09-29).
- **CNFIS:** the domain and the CNATDCU report are still the person's choice (the score comes from a report they can see,
  any of them); both belong in the staff list the heads keep. 5.3: whether WoS Master Book List or SENSE houses also
  count as prestige publishers, and whether critical editions need A only (the guide says A or B in 3.3, A on p. 10).
- **Physics A6** (papers in WoS-indexed proceedings, 0.2/nᵉᶠ) is not computed; such papers score 0 in A.
- **FEAA 2026 C** keeps the any-co-author exclusion: its standard was not re-read here.
- **E7** (an expert's own-name check at decision time only), **E8** (artistic ranks shared across domains), sport levels
  (H144 slice 3), grants by funder (H144 slice 2), course records shared with Informatică and Matematică.

## Rollout (one push with H144 and H142 slice 3)

**Prod script** `rke2-overmind/feaa-2026-scripts/h144_h145_release.js` (guard `RELEASE_IMAGE_IS_DEPLOYED`, `--restart`),
generated from the committed seed against c17c1060 by `gen_release.py` (scratchpad): 50 activity types (fields,
references, `singlePerResearcher`, `publicationRecord`), 86 formulas with their hashes, 5 flags, 4 indicator settings
(Education I9's selector; Mathematics C1/C2 and Physics C citation policy), 90 descriptions, 4 report criteria (Music's
three tables count toward the total; Physics A ≥ 2), the 16 bodies of the organisations registry. Matched by name; it
writes nothing unless every item is as before or already as committed. Rehearsed on prod's own chain — the seed of
3ea75d16 (what prod holds), then `h142_slice3_events.js`, then the script twice: the guarded run writes nothing, the
first run applies everything, the second changes nothing, and the result equals the committed seed document by
document. Prod checked read-only with the script's own preconditions: everything holds but the performance type,
which `h142_slice3_events.js` changes first.

**Order:** push (a release: the image builds) → deploy → copy the 4.1 template to the data volume → flip and run
`h142_slice3_events.js` → flip and run `h144_h145_release.js --restart` → at startup the app stamps the facts of older
head approvals and opens a pending review for each older wizard publication → refresh the report runs (admin batch
refresh per department/division) so the stored scores follow the new rules → registries: map the domains to departments
and name experts (H144).

**What changes for people in prod** (read-only counts, 2026-10-03; 157 declared records in all):
- the 8 books added through the wizard (two researchers) stop counting until a head approves them on
  `/supervisor/declarations`;
- the one Google Scholar record counts once a head approves its values;
- the two leadership positions (Informatică D_xv) count once the organisation is named and an expert ranks it;
- every one of the 77 grant records names a role, so the blank-role rule changes none of them; records keep their
  data — the validator checks a record when it is next saved;
- Physics: the A threshold of 2 for Profesor and the habilitation, reviews out of I/P, proceedings out of A2/A5;
  Mathematics 2026: C1/C2 lose citations of articles outside list A and regain co-authors' citations; Education:
  proceedings counted per edition (more papers count where a series spans years, fewer where I8 and I9 shared an
  edition).

### Rollout, done (2026-10-03)

- Image fd205487 deployed; at startup the core opened the 8 pending wizard reviews (no head approvals existed to stamp).
- The 4.1 template copied to the data volume; `h142_slice3_events.js`: `Vizibilitate` removed from the performance type
  (no record carried it), the 4.1 type added, 3 Music descriptions.
- Precheck re-run read-only after slice 3: every precondition held. `h144_h145_release.js --restart`: 50 of 50 types,
  86 of 86 formulas, 5 of 5 flags, 4 of 4 settings, 90 of 90 descriptions, 4 of 4 criteria, 16 of 16 registry entries;
  the core restarted cleanly.
- Verified read-only against the committed seed: every field the release touches matches (formulas, hashes,
  descriptions, kinds, selectors, flags, types, criteria, registry entries). Left as they are: the optimistic-lock
  `version` counter (48 indicators) and FV Muzică 2026's indicator ids, which prod minted itself and which resolve to its
  35 `Muz26_` indicators.
- **Still to do (UI):** «Reîmprospătează tot» for the reports with stored runs — Informatica: FV Info 2026, FV Info 2016,
  FV Matematică, FV Matematică 2026; Fizică și Matematică: FV Info 2026, FV Matematică, FV Matematică 2026; FPSE: FV
  Psihologie 2026; ICAM: FV Psihologie 2016 ("stale" does not see formula changes). Registries: map the domains to
  departments and name experts. Heads: decide the 8 wizard books and the Google Scholar record.
