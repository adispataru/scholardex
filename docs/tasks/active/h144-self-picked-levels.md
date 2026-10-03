# H144 — self-picked levels → registries ranked by experts, or derived from data

Status: **Slice 1 BUILT 2026-10-03 (committed on local main, not pushed).** Ships together with H142 slice 3: one
push, then every script (Adrian, 2026-10-03: "we can push after the next task and run everything then"). Slices 2
(grants) and 3 (sport) open.

The rule (Adrian, 2026-10-02, H143 and H142 slice 3): a researcher NAMES the thing — conference, organisation,
body, award, event, university, journal, patent — and never picks its level. The level comes from the lists the app
holds, else from a registry the domain's experts rank once for everyone. See H142's doc, "Slice 3 as built", for the
first registry (artistic events).

## Survey (2026-10-03)

Twenty select fields still let the researcher pick a level (the `Incadrare_solicitata` requests of H143 aside):

| Family | Where it scores | What decides |
|---|---|---|
| Conferences: keynote, committee, organiser | C28 I16 (Psych, Edu), I18 (Edu); C35 RIA 1.4/1.5, 3.7 | Comisia 28's rule, by experts; CORE for CS |
| Organisations: associations, academies, federations, institutions, media | C28 I19, I23 (part); Info D_xv; C35 RIA 3.1/3.2, 3.4, 3.6 | experts |
| Bodies: funders, panels, expert groups, policy commissioners | C28 I29, I30, I10 | experts (organisations registry) |
| Awards | C28 I20; C35 RIA 3.3 (distinctions) | experts |
| Grants (role carries international/national) | ~15 indicators, 8 reports; 77 prod records | slice 2: Comisia 28's rule via a programme registry |
| Sport championships | CNFIS 5.2 | slice 3: sport experts |
| From data | C28 I23 URAP top 500; Fizică A7/A8 + CNFIS patents (office); C35 RIA 3.5 abroad; C25 I.7 journal indexing; C28 I1A/I1B bonus fee | the lists the app holds |
| Artistic events (slice 3) | C35 RIA 3.3 contests, RIA 3.4 festivals, RIA 1.4/1.5 artistic events | existing registry |

Prod (read-only, 2026-10-03): only grants carry data (77 records, 9 people: Membru 30, Director (proiect național)
21, Coordonator local (proiect internațional) 20, Director (proiect internațional) 6) and Info D_x (2, Institutional)
and D_xv (2, National). Every other type is empty, so slice 1 migrates nothing.

The standards' own words:
- **Comisia 28, conferences:** "Conferințele internaționale sunt manifestări științifice care îndeplinesc cumulativ cel
  puțin două dintre următoarele criterii: (a) … organizată sau co-organizată de către o asociație sau o instituție
  științifică / profesională internațională; (b) programul științific, precum și proceedings-urile sau rezumatele sunt
  publicate … într-o limbă de circulație internațională (engleză, franceză, germană sau spaniolă); (c) lucrările
  conferinței sunt desfășurate exclusiv într-o limbă de circulație internațională … Conferințele care nu îndeplinesc
  criteriile … vor avea statut [de conferințe naționale]."
- **Comisia 28, grants:** international if "(a) este gestionată de către o autoritate contractantă de nivel
  internațional … (b) implică o competiție între entități … localizate în țări diferite".
- **Comisia 35 (Music), RIA:** 3.1/3.2 "academii, organizații și asociații profesionale naționale sau internaționale de
  prestigiu"; 3.3 "jurii de concursuri naționale sau internaționale, sau pentru atribuirea de distincții naționale sau
  internaționale"; 3.4 "UCMR sau … alte organisme/instituții de spectacole/festivaluri de prestigiu"; 3.5 "în țară /
  străinătate"; 3.6 "difuzare națională sau internațională"; 3.7 "manifestări științifice de nivel național /
  internațional".
- **CNFIS, patents:** national = OSIM; European = EPO; international = WIPO or other offices; triadic = EPO, USPTO and
  JPO for the same invention (the year of the third grant).

## Decisions (Adrian, 2026-10-03)

1. **One engine with kinds**, generalising slice 3 before its first deploy: one experts' page with a tab per kind, one
   domain mapping, one picker API. Artistic events keep their collection as one kind; conferences, organisations
   (bodies included) and awards share one new collection.
2. **Scope of slice 1:** conferences, organisations, bodies, awards, Music's eligibility gates, and the fields the app's
   own lists decide (URAP, patent office, abroad, journal indexing, fee). Grants (slice 2) and sport (slice 3) next.
3. **Gates:** an item the standard admits only for national/international or prestigious entities (Music juries,
   commissioned works, organisations, media) is **not counted until ranked**.
4. **Conferences outside CS:** experts apply Comisia 28's rule — they tick the criteria once per conference, the ticks
   are the recorded basis; CORE decides for CS.

Following from them: a conference is national until shown international (Comisia 28's own default), so a waiting
conference counts national; elsewhere a waiting name counts at the floor the formula gives (C28's national values) or,
behind a gate, not at all.

## Slice 1 — build

- **Engine.** `RegistryKind` (ARTISTIC_EVENT, SCIENTIFIC_EVENT, ORGANIZATION, AWARD): its reference field, levels,
  categories, criteria, bases and floor. `RegistryItem` implemented by `ArtisticEvent` and the new `RegistryEntry`
  (`scholardex.registry_entries`); `RegistryStatus` and `RegistryChange` shared. One review service, one access
  service (`@registryAccess`), one domain mapping, one experts' page with tabs (`/user/registry/review`), one admin
  page (`/admin/registry/experts`, which can also add a domain), one picker API (`/api/entities/registry`), one
  level block in the workspace. Slice 3's paths are renamed before they ship.
- **Reference fields** `CONFERENCE_NAME`, `ORGANIZATION_NAME`, `AWARD_NAME` (with `EVENT_NAME`, `UNIVERSITY_NAME`,
  `FORUM_ISSN`); the level fields leave the types.
- **Scoring variables** (every activity): `Nivel_entitate` (INTERNATIONAL / NATIONAL / LOCAL / null), `International`,
  `Recunoscut` (national or international), `Premiu_stiintific`, `In_strainatate`, `Top500_URAP`,
  `Universitate_numita`, `Tip_brevet`, `Revista_cu_taxa`, `Revista_WoS`, `N_baze_date`. Formulas rewritten on them.
- **Types and formulas:** C28 I10, I16, I18, I19, I20, I23, I29, I30, the I1A/I1B bonus; C25 I.7; Info D_xv; Fizică
  A7/A8 and CNFIS Anexa 5 patents; C35 RIA 1.4/1.5, 3.1/3.2, 3.3, 3.4, 3.5, 3.6, 3.7.
- **Prod:** one guarded script after H142 slice 3's; descriptions say how each level is decided.

## Slice 1 as built (2026-10-03)

- **Engine.** `model/registry`: `RegistryKind` (ARTISTIC_EVENT, SCIENTIFIC_EVENT, ORGANIZATION, AWARD: reference
  field, levels best first, floor, categories, criteria, bases), `RegistryItem` (implemented by `ArtisticEvent`,
  collection unchanged, and by `RegistryEntry`, `scholardex.registry_entries`), `RegistryStatus`, `RegistryChange`,
  `RegistryDomainExperts` (`scholardex.registry_domain_experts`: slice 3's collection, renamed before it shipped).
  Scoring reads `RegistrySupport` (static, loaded on first use, `RegistryRegistrar`; artistic events still through
  `ArtisticEventRankSupport`): a ranked name has its level, a waiting one the kind's floor (conferences NATIONAL,
  none elsewhere), a rejected one none.
- **Experts.** One page, `/user/registry/review?kind=…`, a tab per kind with its waiting count
  (`RegistryReviewService`, `RegistryReviewController`, `@registryAccess`). A conference takes Comisia 28's criteria
  as ticks: on that basis it is international exactly when two hold (CORE or another basis otherwise); an award needs
  its category. An entry of no domain (the initial list) is every expert's to see and to merge spellings into, an
  admin's to change; an admin ranks a name of no domain (its researchers' departments map to none) as such a shared
  entry (found in the live check: it was refused). The admin page `/admin/registry/experts` maps domains to
  departments, names experts and adds a domain (spaces collapsed, at most 80 characters).
- **Workspace.** `CONFERENCE_NAME`, `ORGANIZATION_NAME`, `AWARD_NAME` (with `EVENT_NAME`, `UNIVERSITY_NAME`,
  `FORUM_ISSN`) are pickers over `/api/entities/registry?kind=&q=` (the level label resolved on the server; a
  spelling picks as written and follows its entry). A record's panel says what the registries and lists say
  (`/user/workspace/activities/registry-levels`): each entity's level and basis, or waiting, or rejected with the
  note; a journal's indexing (WoS, ESCI, Scopus, databases, IF, fee); a university's URAP and world rank.
- **Variables** (`RegistryScoringSupport.bind`, every activity): `Entitate_numita`, `Nivel_entitate`,
  `International`, `Recunoscut`, `Premiu_stiintific`, `In_strainatate` (the registry's country, else the
  university's), `Universitate_numita`, `Top500_URAP`, `Top1000_mondial` (the world rankings loaded: QS and ARWU),
  `Tip_brevet` (CNFIS: OSIM national, EPO European, WIPO and other offices international, EP + US + JP triadic; from
  the codes and the office), `Incadrare_aprobata`, `Revista_cu_taxa`, `Revista_WoS`, `Revista_WoS_CC`,
  `Revista_Scopus`, `N_baze_date`, `IF_revista`. Lookups in `RegistryScoringLookups` (rankings, forum indexing,
  DOAJ fee, JCR impact factor by ISSN).
- **Types, formulas, descriptions:** 25, 35 and 35 — the survey's list plus what the definition tests turned up:
  C28 I15 and I22 (journal indexing and IF), C25 I.7 reviews and I.10/I.11 (three databases), C25 I.14 (top 1000;
  THE through a request), C25 I.15/I.16 (abroad from the conference's country). Where no list decides, H143's
  request a head approves (`Incadrare_solicitata`, `Dovada_incadrarii`): C25 I.7, I.10/I.11, I.14; C28 I15, I22.
  CNFIS Anexa 5 reads the patent office too (a declared type still counts for older records). Music's grid import
  suggests a level to the experts (jury, organiser, keynote) instead of setting one.
- **Initial list:** 16 bodies the standards name, shared (`seed/precious-config/scholardex.registry_entries.json`):
  UNESCO, UNICEF, the World Bank, OECD, the European Commission, ERC, the Council of Europe, COST and the IOC
  (international); CNATDCU, CNCS, ARACIS, UEFISCDI, the Ministry of Education, the Romanian Academy and UCMR
  (national).
- **Left for later:** Music CS 2.3 (a selection committee is a property, not a level), Info D_x (the team's level),
  grants (slice 2), sport (slice 3).
- **Verified on the local app (2026-10-03, test data removed):** the script applied to the local database;
  UNESCO picked by its name and by an alias, its level shown; an unknown conference joined the queue, was refused to
  its own proposer and counted national meanwhile; ranking it international was refused on one criterion and
  accepted on two; a leadership position at UNESCO added 4 points to FV Info 2026 (20 → 24); a domain added on the
  admin page. Full suite green (3,867 tests); CI's guardrails green.
- **Prod (read-only, 2026-10-03):** the 25 types and 35 formulas are as before H144; no registry entries; the only
  records of the changed types are the two leadership positions (Tip National), which keep their 2 points.
- **Prod script** `rke2-overmind/feaa-2026-scripts/h144_slice1_registries.js` (guard `H144_IMAGE_IS_DEPLOYED`, run
  with `--restart`): sets the 25 types (fields, reference fields), the 35 formulas with their hashes and the 35
  descriptions, by name, and inserts the 16 bodies when missing; writes nothing when a type or formula is neither as
  before nor as committed. Rehearsed on a scratch database holding the pre-H144 seed (the leadership type re-keyed,
  two records): the guarded run writes nothing, the first run applies everything, the second changes nothing, the
  result equals the committed seed and the records are untouched.
- **Prod order (with H142 slice 3):** push and deploy; copy the 4.1 template to the data volume; flip and run
  `h142_slice3_events.js`; flip and run `h144_slice1_registries.js --restart`; on `/admin/registry/experts` map
  Muzică → Departamentul de Muzică and Teatru şi artele spectacolului → Departamentul de Teatru, add the other
  standards' domains (Psihologie, Științe ale educației, Sociologie, …) and map FPSE's and FSAS's departments, name
  experts where a faculty has no heads (FMT); optionally seed FMT's Anexa 6.1.
