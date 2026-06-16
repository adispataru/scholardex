# Cross-domain standards capability assessment (2026-06-16)

Scan of all UVT faculty/domain evaluation standards under `data/standards/` to lock the **shared
abstractions before** building more reports. 4 already implemented/scoped (matematica, informatica,
economie/FEAA, fizica) + 8 deep-scanned (arte, chimie-biologie-geografie, comunicare-guvernare, drept,
lift, muzica-teatru, psihologie-sociologie-asistenta, sport). ~25 distinct domains total.

## The headline finding: two scoring paradigms, we built one

Our platform was built around **bibliometric scoring** — compute a base score (AIS/RIS/IF/quartile) and
formula-transform it (`S/N/Q/M`). That fits the STEM domains. But **roughly half the faculties use a
fundamentally different model**: a **points-table / category rubric** — *flat points per output type*,
usually `× m (relevance multiplier) ÷ n (authors)`, summed into criteria. This is **not a first-class
mechanism today** and is the single biggest gap.

| paradigm | domains | how scored |
|---|---|---|
| **Bibliometric (have it)** | matematica, informatica, fizica, FEAA, chimie, geografie | base score AIS/RIS/IF/SRI → formula |
| **Hybrid (IF-linear points + ÷n + m)** | FSP (psych/edu/sport), sport/FEFS, FSGC (socio/comm/pol), biologie | `3+3·IF`, `(2+4·f)`, `4+7·AIS+c`, then ×m ÷n |
| **Points-table (don't have it)** | **drept, FLIT (filo/filol/istorie/teologie), arte/FAD, muzica-teatru/FMT** | flat points per category × m ÷ n; **no bibliometrics at all** |

## Capability matrix (domain × needs)

| faculty/domain | scoring | h-index | corresp/last author | curated lists beyond ours | artistic / non-academic outputs | criteria complexity |
|---|---|---|---|---|---|---|
| matematica ✅ | SRI/RIS | – | – | – | – | thresholds |
| informatica ✅ | CS strategies | – | – | SENSE/CORE (have) | – | thresholds |
| fizica (H65) | AIS + **Nef** | **✅ h** | **✅ corr+last** | WoS MBL | patents (activity) | A/I/P/C/h/T |
| economie/FEAA ✅ | AIS×M | – | (corr optional) | publisher tiers (have) | – | per-position book cap |
| chimie | **IF (FIC/FICD/FICAP/FICAC)** | **✅ ≥13/9** | **✅ corr-only** | – | patent=IF4 | matrix |
| geografie | AIS cumulative | **✅ Hirsch** | first/corr/equal | GEOREF/ERIH+ | project=article equiv | counts |
| biologie | **points-formula** `4+7·AIS+c` | – | **✅ +last** | CAB/ESCI, publisher tiers | patents, per-item citations | dual Σ thresholds |
| FSP psych/edu/sport | **`3+3·IF`** ×m ÷n | – | **✅ corr+last** | PsycInfo/ERIC/ERIH/GS | patents, policy reports, sport perf | counts+points, caps, post-PhD |
| sport/FEFS | **`3+3·IF`** ×m ÷n | – | **✅ corr+last** | WorldCat/KVK, GS | **sporting performance**, coaching titles, patents | caps, compensation rule |
| FSGC socio/comm/pol | **`(2+4·f)`** ×m ÷n | – | first | 26 BDI (CEEOL/ERIH/DOAJ/…), WorldCat | – | count+point, **post-PhD anchor**, best-of |
| drept (law) | **pure points-table** | – | – | legal DBs (HeinOnline/CEEOL/WestLaw), publisher Lists 1/2 | legislative/service tail | count+point gates, qualitative C1 |
| FLIT (×4 humanities) | **pure points-table** `pts/n` or flat | istorie alt (h≥3 GS) | – | ERIH+/CNCS A/B/C, ~50 publishers, library counts | critical editions, translations (per-page), dictionaries | caps, intl/national split, best-of |
| arte/FAD | **points-table** | – | – | ERIH/JSTOR/MUSE/CEEOL | **exhibitions/curatorial/artistic projects** | count gates + point sums, best-of, lei threshold |
| muzica-teatru/FMT | **points-table** | – | – | – | **concerts/films/compositions/recordings/festivals** (role×venue×scope) | counts+points |

## Cross-cutting gaps, ranked by recurrence (build once, not per-report)

1. **Points-table / category-rubric scoring engine** — *flat points per output category × m ÷ n*, summed.
   Needed by **drept, FLIT×4, FAD, FMT, FSGC, FSP, sport, biologie** (≈8 faculties). Without it, none of the
   humanities/arts/law/social/performing reports are buildable. **The biggest, most foundational gap.**
2. **Curated allowlists / indexing membership** — ERIH-PLUS, CEEOL, JSTOR, EBSCO, ProQuest, Project MUSE,
   DOAJ, Index Copernicus, HeinOnline, PsycInfo, ERIC, SPORTDiscus, **CNCS A/B/C publisher tiers**,
   domain-specific prestige publisher/journal lists, **WorldCat/KVK library counts**, **Google Scholar**.
   Needed nearly everywhere outside pure-STEM. Plus the **"indexed in ≥N databases"** count predicate. Our
   data is WoS/Scopus/CORE/SENSE/CNCSIS only. **Major data gap.**
3. **h-index / Hirsch** — REQUIRED + hard-thresholded: chimie (≥13/9), geografie (≥4..1), fizica, istorie
   (alt). Baseline doesn't compute it. **Own task.**
4. **Corresponding-author + last-author roles** — chimie, biologie, geografie, fizica, FSP, sport. Confirms
   **H63 (OpenAlex)** as genuinely cross-cutting; extend it to **last-author** too.
5. **Relevance/language multiplier `m`** ({3,1,0.5} or {2,1.5,1}) — FSP, sport, FSGC, humanities intl/national
   splits. A derived publication attribute + variable.
6. **Advanced criteria engine**: mixed **count + point** thresholds, **post-PhD temporal anchor**,
   per-indicator/per-edition **caps (plafoane)**, **best-of single-indicator assignment**, qualitative
   **Da/Nu** gates, cross-criterion **compensation**. Recurs across FSGC, law, FSP, sport, arts, FLIT.
7. **Per-item citation variable inside a publication score** (biologie `+c`, FSGC/law point-weighted
   citations) — citations are a separate indicator today.
8. **Artistic-activity model** (FMT, FAD) — output-type × role × venue/visibility-tier × geographic-scope →
   points, with "count-once most-important" dedup. ART_EVENT + generic activities too coarse.
9. **Non-academic output types** — patents (chem/bio/fizica/FSP/sport/law), policy reports (FSP/sport/FSGC),
   **sporting performance / coaching** (sport/FSP), humanities editions/translations/page-count.
10. **Monetary thresholds + FX** — grants ≥ X RON/EUR with bnr.ro conversion (chem/geo/FSGC/law/FSP/sport/
    FEAA). Ties to **H64 (projects)** + currency normalization.

## Roadmap implication

The right order is **capabilities first, reports second** — otherwise we'd build the points-table paradigm
ad-hoc inside report #5 and rework #1–4. Reframed backlog:

- **Validated / sharpen existing**: H63 (corresponding-author → add **last-author**; high cross-cutting value),
  H64 (projects + **FX/monetary thresholds**).
- **New foundational tasks to create**:
  - **Points-table / category-rubric scoring** (the big one) — unblocks ~8 faculties.
  - **Curated allowlists + indexing-membership** (ERIH/BDI/CNCS-A-B-C/publisher-prestige/WorldCat/GS) + "≥N DBs" predicate.
  - **h-index computation** (cross-cutting).
  - **Advanced criteria engine** (count+point, post-PhD, caps, best-of, Da/Nu, compensation).
  - **Artistic-activity model** (FMT/FAD).
  - **Non-academic outputs** (patents, policy reports, sporting performance) — partly via activities.
- **Sequencing**: STEM/bibliometric reports (fizica, chimie, geografie) can proceed on the current engine + the
  small additions (Nef, h-index, author roles). The **humanities/arts/law/social block is gated on the
  points-table engine + allowlists** — do those capabilities before attempting those faculties.

## DB reality check (2026-06-16) — REVISES the gaps above

The above was assessed against the *code* baseline. The **live DB (43 indicators, 18 activity defs, 5
reports)** shows the engine already implements much of the "missing points-table paradigm" via
**`GenericActivity` + the formula engine operating over activity fields**. Evidence:

- **Flat points**: `Info_D_iii = [2]`, `Info_D_xii = [1]`.
- **Role / level / type multipliers**: `Info_D_vii = [Rol=='Membru'?1:2]`, `Info_D_x = [Nivel=='International'?4:National?2:1; X*N_ani]`, `Info_D_xv = [Tip=='National'?2:4]`.
- **Budget tiers**: `Info_D_v = [B=Buget; X=B<50000?1:B<100000?2:…; Rol=='Membru'?X:X*2]`.
- **Points ÷ author count** (the humanities `pts/n`): `Info_D_xiii = [12/max(N_autori-2,1)]`, `Info_D_ii = [S/max(N_editori-2,1)]`.
- **Hybrid IF-linear (FSP/psychology)** already built: `Psiho_I1_I2 = [S>1?(3+3*S):(3+S)]`, `Psiho_I5_I6 = [(3+3*S)/N]`, `Psiho_I16_I17a` citation tiering (IMPACT_FACTOR strategy).
- **Artistic event** exists: `Arte_exemplu_event` (ART_EVENT). **Patents**: `Brevet` activity + `Info_D_xv`.
  **Projects+budget**: `Grant Cercetare` + `Info_D_v`. **CNCSIS books**: `Mate_C1_UVT/C2_UVT`.
- **Per-competition reports** exist beyond the fišă reports: "Eligibilitate Tinere Echipe", "Eligibilitate PD"
  (grant/postdoc eligibility) — the engine is already used for ad-hoc criteria.
- Activity model carries the needed fields: `Buget, Rol, Nivel, Tip, N_autori, N_editori, N_luni, N_ani`.

**So the "points-table scoring engine" is NOT a missing capability** — flat-points, ×relevance-multiplier,
÷author-count, budget/level/type tiers are all expressible *today* via GenericActivity + activity fields +
ternary formulas. Humanities/arts/law/social reports are therefore mostly **config + new activity
definitions + curated allowlists**, not a new engine. This shrinks gap #1 dramatically.

### Revised real gaps (DATA + small extensions, not a new engine)
1. **Curated allowlists / indexing data** (ERIH-PLUS, CEEOL, JSTOR, EBSCO, ProQuest, MUSE, DOAJ, PsycInfo,
   ERIC, Google Scholar, **CNCS A/B/C publisher tiers**, **WorldCat/KVK library counts**) + the
   "indexed in ≥N DBs" predicate. **No such data ingested. Biggest real gap**, needed across
   humanities/social/law/arts.
2. **h-index** — no indicator/data computes it (chimie/geografie/fizica/istorie need it). Real.
3. **Corresponding + last author** — no indicator uses it; not in data → **H63** (extend to last-author). Real.
4. **Advanced criteria nuances** — **post-PhD temporal anchor**, per-indicator **caps (plafoane)**,
   **best-of single-indicator assignment**, mixed count+point thresholds, Da/Nu qualitative gates. The
   criteria/threshold model exists; these specific behaviours are not evidenced → modest extensions.
5. **Per-paper citation count as a variable inside a publication formula** (biologie `4+7·AIS+c`) — niche.
6. **Page-count scoring** (humanities translations) — niche.

### Net correction to the roadmap
Drop "build a points-table engine" (it effectively exists). The remaining foundational work is **data, not
engine**: (a) curated allowlists/indexing + CNCS tiers, (b) h-index, (c) corresponding/last author (H63),
(d) minor criteria extensions (post-PhD anchor, caps, best-of). With those, **most domains become activity
definitions + indicator config + report bindings** — far cheaper than the "few big new engines" the first
pass implied.

## Why this assessment paid off

We were about to build domain reports one-by-one on a bibliometric engine. ~Half the faculties don't use
bibliometrics at all — they need a points-table engine + curated non-WoS/Scopus lists we don't ingest. Found
*before* implementation, this reshapes the roadmap from "N report tasks" to "a few shared-capability tasks,
then reports become config."
