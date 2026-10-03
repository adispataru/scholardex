# H142 — FV Muzică 2026 (Comisia 35) and the CNFIS gaps for music

Status: **Slices 0, 1 and 2 in prod 2026-10-02 (slice 2: image 1922a5eb, `h142_muzica_export.js` run by Adrian,
verified read-only); slice 3 in prod 2026-10-03 (image fd205487; the 4.1 template on the data volume,
`h142_slice3_events.js` run by Adrian); slices 4–6 open.** Scoped, decided and reassessed the same day. Asked by FMT (vice-dean for research,
email of 2026-10-02 in the thread "Intalnire platforma de raportare a cercetarii"). The faculty is the first
vocational one on the platform.

## Sources

What the faculty sent (kept outside the repository, they carry personal data):

- the ORCID / Google Scholar table of the FMT staff, updated (one ORCID added for a person who had no
  identifier at all, one Google Scholar link added);
- their CNFIS 2025 files: Anexa 6 (articles) and Anexa 6.1 (artistic creation, 515 rows for 2021–2024) of the
  Music department, plus the blank 2025 templates and the CNFIS guide for IC2 (January 2025);
- "CNATDCU template 2026-2027.docx": the faculty's grid for Music under the new standards, "valid from October
  2026", for conferențiar and profesor;
- one grid filled in by a lecturer against the conferențiar thresholds (well above every minimum: DID 400,
  CS 1700, RIA 770).

The source of truth is the official text, OM 3.019/2025, COMISIA 35, domain MUZICĂ, in
`data/standards/2026/standarde-{conf,prof,abilitare}-2025.html` (git-ignored, so it is transcribed below).
The faculty's Word template differs from it in two places, and the filled grid follows the official text:

- its list of recognised databases has 13 entries; the official list has 16 (it adds Taylor & Francis Online,
  WoS AHCI and WoS ESCI);
- its recordings minimum omits the streaming alternative ("1 înregistrare video din concert public").

The Theatre domain of the same commission (Teatru și artele spectacolului) has a different grid; the faculty
sends it separately. It is a follow-up, not part of H142.

## The standard (OM 3.019/2025, Comisia 35, Muzică)

Points per item, the whole career, no division by the number of authors. "Activitățile pot fi menționate
într-un singur tabel sau categorie": each activity counts once.

### Table 1 — DID, activitatea didactică și profesională

| Item | Points |
|---|---|
| 1.1 Tratat / studiu amplu / volum de studii, published (*) | 30 |
| 1.2 Capitol într-un volum colectiv | 15 |
| 1.3 Manual, curs, suport de curs, crestomație, colecție, îndrumător metodic, printed (**) | 10 |
| 1.4 Traducere / editare critică / îngrijire redacțională | 20 |
| 2.1 Înregistrare: CD/DVD/vinyl with an authentication code (***) and/or streaming (****) | 30 |

(*) published = publisher of CNSC category A or B, or an equivalent foreign one. (**) printed, under any
imprint. (***) without a code accepted when made under a TV station, film or record company. (****)
different programmes, at least 45 minutes; streaming needs proof of broadcast.

### Table 2 — CS, cercetare științifică / creație artistică

| Item | Points |
|---|---|
| 1.1 Concert / recital / spectacol, international or top national visibility (*) | 20 |
| 1.2 Concert / recital / spectacol, regional or local visibility (**) | 10 |
| 2.1 Article in a journal indexed in one of the 16 databases, or in indexed proceedings | 15 |
| 2.2 Article in an international music lexicon/dictionary, online article on a top international festival's site, abstract for RIPM/RILM/RISM/RIDIM | 10 |
| 2.3 Communication at a conference with a selection committee or peer review | 15 |
| 3.1 Member of a grant / project team (competitive or attracted funding) | 10 |
| 4.1 Large composition, cycle or album published by a specialised publisher | 15 |

Roles that count for 1.1–1.2: composer, conductor, director, ballet master, soloist, concertmaster, member of a
chamber ensemble of at most 10. (*) abroad at traditional international festivals or in the seasons/tours of
professional institutions; at home at prestigious festivals with consistent international participation or
in the seasons of institutions with international visibility. (**) everything else (philharmonic societies,
town halls, associations, museums; national and local institutions).

### Table 3 — RIA, recunoaștere și impact (no mandatory activities)

| Item | Points |
|---|---|
| 1.1 Management function | 10 / year / function |
| 1.2 Director / coordinator of a grant or project | 30 |
| 1.3 Editorial board member / reviewer of indexed publications or publishers | 10 |
| 1.4 Organiser of an international scientific/artistic event | 30 |
| 1.5 Organiser of a national event | 10 |
| 2.1 State distinction or prize (Romania or abroad) | 40 |
| 2.2 Distinction or prize from professional organisations, media | 30 |
| 2.3 Prize at a prestigious creation or performance competition | 40 |
| 3.1 Member of an academy / professional association of prestige | 5 |
| 3.2 Office in an academy / professional association | 10 / year / office |
| 3.3 Jury member (national or international competitions, distinctions) | 10 |
| 3.4 Work bought or commissioned (UCMR, institutions, festivals), with the contract | 10 / work |
| 3.5 Course, masterclass, conference at another institution | 20 at home, 30 abroad in a foreign language |
| 3.6 Portrait / interview as sole guest, national or international media | 5 |
| 3.7 Keynote speaker | 20 national, 30 international |

### Minimums

| | DID | CS | RIA | Total |
|---|---|---|---|---|
| Conferențiar | 70 | 180 | 50 | 300 |
| Profesor | 100 | 240 | 100 | 440 |
| Abilitare | 85 | 210 | 75 | 370 |

Mandatory counts depend on the candidate's profile: **teoretician** (musicologist), **compozitor**,
**interpret**; "practicieni" are composers and performers. The "5 concerts" minimum sits on row 1.1 only
(the merged cell in the official table is the row label, not the minimum).

| Count (item) | Teoretician C / P / A | Compozitor C / P / A | Interpret C / P / A |
|---|---|---|---|
| Books (DID 1.1) | 1 / 2 / 2 | 1 / 1 / 1 | 1 / 1 / 1 |
| Manuals, courses (DID 1.3) | 2 / 2 / 2 | 1 / 1 / 1 | 1 / 1 / 1 |
| Recordings (DID 2.1) | – | 1 / 2 / 1 | 1 / 2 / 1 |
| Concerts, top visibility (CS 1.1) | – | 5 / 8 / 7 | 5 / 8 / 7 |
| Indexed articles (CS 2.1) | 5 / 8 / 6 | 1 / 2 / 1 | 0 / 1 / 1 |
| Communications (CS 2.3) | 5 / 8 / 6 | 1 / 2 / 1 | 0 / 1 / 1 |

C = conferențiar, P = profesor, A = abilitare. Recordings: a CD/DVD/vinyl or, alternatively, a streamed video
of a public concert.

Recognised databases (16): Cambridge Core, CEEOL, DOAJ, EBSCO, ERIH PLUS, JSTOR, Oxford Academic Journals,
Oxford Music Online, Project MUSE, ProQuest, RILM, Sciendo, Scopus, Taylor & Francis Online, WoS AHCI, WoS
ESCI; for interdisciplinary work also the peer-reviewed databases of the related domains. The platform knows
Scopus, the WoS editions, DOAJ and ERIH PLUS; for the other eleven see the plan, step 4.

## What already exists

- **Staff:** FMT is in prod since 2026-09-29 (`H111`): 54 people, Muzică 39 and Teatru 15; ORCIDs stamped.
  Three accounts had no identifier; the updated table gives one of them an ORCID.
- **CNFIS (`H129`):** Anexa 5.1 / 6.1 (artistic creation) from the declared activity "Participare eveniment
  artistic" (fields Tip and N_participanti_universitate), its level from the registry of artistic events (66
  Music events: 29 top, 28 international, 9 national); Anexa 5.3 / 6.3 (humanities), which in the CNFIS guide
  covers code 751 "Muzică (fără interpretare muzicală)", while 75 "Muzică (interpretare muzicală)" is an arts
  code (5.1). The staff table's "Ramura_Stiinta" column gives exactly that split.
- **Engine:** GenericActivity indicators read declared fields; `N_editii` = An_sfarsit − An_inceput + 1 gives
  the years of a function; perspectives combine criteria with AND/OR trees and labelled routes (FEAA);
  thresholds exist for CONF_UNIV, PROF_UNIV and HABIL.

## Measured on the faculty's files

**CNFIS Anexa 6.1 (artistic creation, 2021–2024): 515 rows** (2021: 97, 2022: 139, 2023: 141, 2024: 138). The
faculty ranked 276 as national, 203 international, 29 top; 234 individual projects, 201 collective, 55 group,
10 prizes, 8 nominations. 12 rows carry no mark and 5 carry two (the guide allows one).

- **Registry:** 124 rows (24 %) name an event the registry lists, 106 of them at the faculty's level. The other
  391 are events no list names, which the faculty ranked anyway (the guide's national level also covers events
  organised with UCMR, UCIMR, UNIMIR or funded by the Ministry of Culture or of Education).
- **Events:** 322 distinct strings in the event column, 294 after dropping dates and edition numbers; 39 rows
  hold only a link and 90 no event. Seeding the registry needs a review step, not a blind import: the 390 usable strings cluster (word overlap)
  into about 240 candidate events, about 60 of which match a registry entry (an alias to add) and about 170
  are new — one review pass, with the faculty's level as the suggestion.
- **Attribution:** 155 rows name at least one staff member (360 name nobody: ensembles, students, or no
  performer at all); 21 of the 43 Music staff appear. The institutional table seeds events well and attributes
  activities poorly. Per-person sheets attribute by construction.

- **The CNFIS kind cannot be read from the text:** a word rule (choir/orchestra → collective, duo/trio → group,
  recital/soloist → individual) agrees with the faculty on 185 of 485 single-kind project rows, guesses nothing
  for 146 and disagrees on 154. The kind needs picked inputs (role, ensemble size) or the per-person 5.1 sheet,
  which carries it; an import review must let the person set role and ensemble size for many rows at once.

**CNFIS Anexa 6 (articles):** about 45 rows, 20 with a DOI (some malformed: "DOI  10…", truncated), 22 with an
ISSN, no author names. A DOI row can be resolved through Crossref or OpenAlex and attributed by the authors'
ORCIDs.

**The corpus barely knows the Music staff:** by ORCID, OpenAlex links works to 5 of the 42 Music staff who have
one, 27 works in all, a few of them visibly someone else's (a chemistry journal, a 2007 concrete-structures
conference). The faculty's own Anexa 6 lists about 45 articles for 2021–2024 alone, mostly in Romanian music
journals whose authors deposited no ORCID. So for Music the corpus sync is a minor source: the DOIs of Anexa 6
(authors from Crossref, matched to the faculty's staff) and the grids carry the articles.

**Where Music publishes:** the journals of Anexa 6 are Romanian music journals and proceedings (Quaestiones
Romanicae, Tehnologii informatice și de comunicație în domeniul muzical, Bulletin of the Transilvania University
of Brașov series VIII, MUSiQ, the International Musicology Congress); OpenAlex flags none of them as DOAJ or
Scopus, and half are not in OpenAlex at all. The faculty itself marked 12 rows ERIH PLUS and 3 Arts & Humanities.
Journals like these are typically in CEEOL, EBSCO and RILM, so those three lists matter most for Music.

**The filled grid (one lecturer):** split on bullets, dashes and line breaks, it gives 194 items, and the count
of each grid row matches the points claimed in it exactly (12 recordings = 360, 14 top concerts = 280, 139
regional concerts = 1390, 2 communications = 30, 12 professional distinctions = 360, 8 competition prizes =
320, 3 juries = 30, one office over six years = 60). 189 items carry a year, 12 a link. The row gives the item
type and, for concerts, the visibility; the role and the CNFIS kind can be guessed from words like "dirijor",
"solist", "recital coral", "duo", and need the person's review.

**Already automatic for grants:** the project workspace proposes the registry projects whose director's name
matches the person (UEFISCDI/brainmap, CORDIS) and imports one as a "Grant Cercetare" in one click; members
find theirs by search. Cultural grants (AFCN) are not in the registry.

**Report transfer (`H50`):** a run exports to a faculty's own template and an uploaded file is verified
against the run (read-only); it does not create records from the file.

**Not there yet:** the event registry is read-only (a JSON import and a listing; no aliases, no basis, no
editing); the declared-publication wizard takes a DOI only as a key and fetches nothing (a Crossref client
exists); Anexa 4.1 (citations of artistic works) does not exist — the CNFIS sheet header holds only the
three Hirsch values.

## Decisions (Adrian, 2026-10-02)

1. Profiles as labelled routes in a perspective, like the FEAA routes: the report says which route is met.
2. Concerts and prizes: one activity shared by the CNATDCU report and CNFIS.
3. Events missing from the list: added to the registry by an admin or a head — and populate the registry from
   the faculty's own data.
4. The other journal databases: support them if at all possible.
5. **Reduce manual work as far as possible.** The argument Adrian made against a competitor: "if we need to do
   stuff manually, why do we build an app? Isn't Excel the same thing?"
6. Anexa 4.1: yes, with the CNFIS work.

## Principle

Every fact enters once, from the best source that holds it; people review instead of typing. Typing is left
for what no source holds, and then with pickers and derived fields. What makes the app more than Excel after
the import: it scores, checks the thresholds and the routes, writes the CNFIS annexes from the same records,
lists a shared performance once in the faculty's Anexa 6.1, and lets heads see who is ready.

## Where each item comes from

| Item | Source, in order |
|---|---|
| DID 1.1 books, 1.2 chapters | grid import; ISBN lookup (Open Library) and the CNCS publisher categories classify them; ORCID works with a DOI |
| DID 1.3 manuals, 1.4 translations, editions | grid import; declared |
| DID 2.1 recordings | grid import; a barcode, catalogue number or link fills the rest (Discogs, MusicBrainz, YouTube, Deezer) |
| CS 1.1 / 1.2 concerts | grid import and per-person CNFIS 5.1 import (no public source holds them); visibility derived from the event (registry) |
| CS 2.1 indexed articles | Anexa 6 DOIs and grid import first (the corpus knows little for Music); corpus by ORCID; membership by ISSN (Scopus, WoS, DOAJ, ERIH PLUS, and the title lists below); declared by DOI (fetched from Crossref) |
| CS 2.2 lexicon / RILM abstracts, 2.3 communications | grid import; declared |
| CS 3.1 grant member, RIA 1.2 grant director | project registry (director proposed automatically, member by search); grid import |
| CS 4.1 published compositions | grid import; the ISMN or publisher identifies a specialised publisher; declared |
| RIA 1.1 management functions | current headships from the org data; grid import for the past |
| RIA 1.3–3.7 (boards, organiser, prizes, memberships, offices, juries, commissions, masterclasses, interviews, keynotes) | grid import; competition prizes from CNFIS 5.1; UCMR lists for works bought (3.4), UCMR prizes (2.2) and members (3.1); declared |
| CNFIS 4.1 citations of artistic works | per-person 4.1 sheet import; declared |

The concert record carries three inputs, mostly picked: the event (picker over the registry), the role, and the
size of the ensemble. Visibility (CS 1.1 or 1.2), the CNFIS level and the CNFIS kind (individual, group of 2–4,
collective of 5+) are derived from them. A prize or a nomination is the same record with a result instead of a
role.

## Plan

1. **FV Muzică 2026 report.** 27 indicators (DID 5, CS 7, RIA 15) and count helpers; the three tables and the
   total for CONF_UNIV, PROF_UNIV and HABIL; the minimum counts as a perspective of three labelled routes
   (Teoretician, Compozitor, Interpret). Activity types "(Comisia 35, …)"; the concert/prize type shared with
   CNFIS ("Participare eveniment artistic" gains role, ensemble size and result; its CNFIS kind becomes derived);
   "Grant Cercetare" shared (member → CS 3.1, director → RIA 1.2). Delivered as H124: local build, seed,
   one idempotent prod script with a guard, rehearsal on a scratch database. Acceptance: a synthetic candidate
   with the example grid's counts scores DID 400, CS 1700, RIA 770; one case per route and position, just
   above and just below.
   **Export in the faculty's own grid** (the xlsx the candidate hands in, as the filled example), through the
   report-transfer bindings of `H50` (Informatică xlsx; Matematică, Fizică, FEAA docx): the file becomes an
   output of the app, listed and totalled — the answer to "why not Excel".
2. **Grid import.** Built on the same binding as the export. **The normal flow (Adrian, 2026-10-02): every
   colleague imports their own fișă de verificare**; a head or an admin can upload a department's files at
   once when the faculty has them, which only speeds things up. The person uploads their filled grid (the faculty template); each cell becomes activities of
   the row's type, with year, link and text kept; role and ensemble size are proposed where a word makes them
   obvious and otherwise asked once for a whole selection ("all these are choir concerts I conducted"); they land
   as imported records to review, and a re-import does not duplicate. An admin or head can upload a batch for a department. The same
   importer reads a person's CNFIS 2025 sheets: Anexa 5.1 (each row has its year, kind and level) and Anexa 4.1.
3. **Event registry.** Aliases, the basis of a level (CNFIS list; UCMR/UCIMR/UNIMIR partnership; MC/MEC funding;
   international participation; added by a head), country and organiser; an admin/head page to add, merge and
   rank; a candidate queue fed by imports (the faculty's 294 events first, with the level they gave as a
   suggestion); matching by normalised name and aliases; the event picker on the activity form.
4. **Journal databases.** Membership by ISSN, next to the existing Scopus, WoS (AHCI, ESCI…), DOAJ and ERIH PLUS
   checks, from public title lists loaded by an admin operation (refreshed before each use, like the CPCI index).
   Checked 2026-10-02 (each URL fetched):

   | Database | List | Format, size | Coverage |
   |---|---|---|---|
   | Cambridge Core | KBART "Cambridge Journals: All journals" (cambridge.org/core/services/librarians/kbart) | KBART, ~900 | full |
   | JSTOR | jstor.org/kbart/collections/all-archive-titles?contentType=journals (or the head-title list) | KBART or XLSX, ~2,900 titles | full; archive with a moving wall: check the year |
   | Project MUSE | about.muse.jhu.edu/…/muse_journal_metadata_2026.tsv | TSV, ~870 | full |
   | ProQuest | tls.search.proquest.com title lists, per product (Music Periodicals Database: 669 titles) | KBART or TSV | full per product |
   | EBSCO | about.ebsco.com/title-lists, one file per database (316 databases) | XLS/HTM, ONE ISSN column | full per database |
   | RILM Abstracts | api.rilm.org (the list behind rilm.org/resources.php; undocumented) | pipe-separated, ~3,650 music + ~16,000 other | full; core/secondary/tertiary grades |
   | Oxford Academic | OUP KBART zips (2025 A–Z, 2026 current collection, open access) | KBART, ~600 | partial: no public 2026 A–Z |
   | Taylor & Francis | own lists behind Cloudflare; GOKb KBART of the Jisc list (CC0), 2,443 titles | KBART | partial |
   | CEEOL | none from CEEOL; the German national-licence list, 1,304 of ~3,100 journals | KBART | partial (~42 %); crawling forbidden by CEEOL's terms — ask CEEOL for its KBART |
   | Sciendo | none (now reference-global.com, De Gruyter Brill) | — | ask for the KBART; fallback: a DOI resolving to the platform |
   | Oxford Music Online | not a journal database (Grove, Oxford Companion, Oxford Dictionary of Music) | — | its entries belong to CS 2.2 (lexicon articles), declared |

   Matching: normalise ISSNs (JSTOR's head list drops the hyphen; EBSCO gives one ISSN per row; many RILM rows
   have none) and compare against both ISSNs of the venue; respect coverage start/end against the publication
   year. **To decide:** which EBSCO and ProQuest databases count — the standard names only the vendor, and
   Academic Search Ultimate (~20,000) or ProQuest Central would admit almost any journal. Proposed: the
   music, arts, humanities and Central-European ones (Music Index, RILM Full Text, Art Full Text, Humanities
   Source, Central & Eastern European Academic Source, Music Periodicals Database) plus Academic Search
   Ultimate. The lists serve every domain whose standard names such databases (Sociology's three-database rule,
   Visual arts, the humanities).
5. **Lookups by identifier, and the music lists** (checked live 2026-10-02; searching by NAME finds little for
   Romanian classical musicians, so the person gives an identifier and the platform fills the rest):
   - **Publisher categories (S):** the official CNCS lists, all text-extractable PDFs — 2026 (OM 5.100/26.08.2026,
     164 rows; Music A: MediaMusica, Editura Muzicală GRAFOART, Editura UNMB; B: Risoprint, Artes, Eikon,
     Eurostampa, Presa Universitară Clujeană), 2020 (155 rows), 2013 (arts, B/C only) and the UEFISCDI list of
     foreign publishers of international prestige (349, incl. Bärenreiter, Ricordi, Universal Edition, OUP, CUP).
     They classify every book of DID 1.1 ("publicat" = CNSC A or B, or an equivalent foreign publisher) without
     a question. Editura Universității de Vest is NOT classified for Music (B for Theatre in 2026).
   - **ISBN (S):** Open Library, keyless (title, publisher, year, pages; Editura Muzicală 450 works, Editura UVT
     132); Google Books needs a key.
   - **Recordings (M):** a barcode, a catalogue number or a link fills the release from Discogs (token; release
     data CC0; carries the UCMR-ADA licence and ORDA numbers, which fit "cod de autentificare") and MusicBrainz
     (keyless, CC0 core, 1 request/s). Coverage: moderate for Electrecord, Casa Radio and the UCMR anthologies,
     low for recent and self-released CDs (10 Romanian classical releases on Discogs for 2024).
   - **Streaming (S):** a YouTube link checked through the Data API (key; 1 unit per lookup of 10,000 a day):
     duration ≥ 45 minutes, date, channel, live-broadcast times as the proof of broadcast; store the video id
     and the check date only (the API terms want data refreshed within 30 days). Deezer, keyless, for audio
     albums (barcode, label, duration).
   - **UCMR lists (S–M):** works bought by UCMR (PDF per session, 2016–) → RIA 3.4; UCMR prizes (HTML, since
     1967) → RIA 2.2; the member list → RIA 3.1. For composers and musicologists.
   - **ORCID works:** works on 15 of the 42 Music staff records (60 items: 39 articles, 6 chapters, 5 books, 1
     artistic performance), most with a DOI; distinctions, memberships and fundings on at most 4 records. Import
     the works with a DOI as declared publications; skip the rest.
   - **Concerts:** no usable public source. Filarmonica Banatul has a keyless events API (2022–2025, performers
     only in titles), the Opera's pages would need a scraper; together they touch about 6 % of the faculty's
     rows — use them to seed the event registry, not the person's record. Bachtrack forbids scraping, Operabase
     is commercial, iabilet has no performers.
   - **Not usable:** AFCN lists (only the applicant organisation is named), press archives for Anexa 4.1
     (Arcanum is subscription-only), WorldCat (subscription), the National Library catalogue (CAPTCHA), ORDA
     (no public search). The UCMR-ADA repertoire search works but needs UCMR-ADA's permission.
6. **CNFIS Anexa 4.1.** The declared activity, its writer, the total on the CNFIS page, in the frozen copy and
   on the unit page; filled by the import of item 2.
7. **Faculty data.** Stamp the new ORCID and the Google Scholar ids; set each Music staff member's CNFIS domain
   from the staff table (75 performance, 751 the others; Teatru 73); resolve the Anexa 6 DOIs; start the
   OpenAlex sync for the faculty's staff (54 people, light) instead of waiting for each one; expect little
   for Music and some misattributed works to reject.
8. **Theatre**, when the faculty's grid arrives.

## Implementation (2026-10-02)

Six slices; each ships on its own (release by Adrian, then its prod script if it has one). Sizes are relative:
slice 1 is about the size of the Sociology report (`H124`).

### Slice 0 — faculty data (script only, no release; small)
`h142_fmt_data.js`, idempotent, writes only where a value is missing: the new ORCID; `researcherProfile.scholarId`
from the Google Scholar links (54); the CNFIS domain of each FMT person for the open editions, from
"Ramura_Stiinta" (75 performance, 751 the other Music staff, 73 Theatre). Dry run on the local app first.

### Slice 1 — FV Muzică 2026 (large: configuration plus two engine additions)
- **Engine:** in `ActivityReportingService`, two derived variables beside `Interval_buget` / `N_editii`:
  `Vizibilitate` (the event's rank in the registry when listed — top or international → CS 1.1 —, otherwise
  the declared value, otherwise regional/local) and `Tip_CNFIS` (role + ensemble size → individual / group 2–4 /
  collective 5+; a result of nomination or prize wins). One shared function also used by
  `CnfisReportingFacade.arts()`, which today reads the declared "Tip"; a declared "Tip" keeps working.
- **Strategy `MUSIC_INDEXED_JOURNAL`** (publications): article, review or proceedings paper in a venue indexed in
  Scopus, any WoS edition, DOAJ or ERIH PLUS; slice 4 adds the title lists behind the same check.
- **Activity types "(Comisia 35, …)":** one per item of the three tables; "Participare eveniment artistic" gains
  Rol, Marime_formatie, Rezultat (participare / nominalizare / premiu) and Vizibilitate (declared fallback);
  "Grant Cercetare" shared (member → CS 3.1, director → RIA 1.2); per-year items use An_inceput / An_sfarsit.
- **Indicators:** 27 scoring, plus count helpers (formula `1`, declared and corpus) for books, manuals,
  recordings, top-visibility concerts, indexed articles and communications.
- **Criteria:** DID, CS, RIA and Total for CONF_UNIV / PROF_UNIV / HABIL; 12 count criteria (books and manuals
  for theoreticians and for practitioners; recordings and top concerts for practitioners; articles and
  communications for theoreticians, composers and performers), each with its thresholds per position.
- **Perspectives:** the three tables, the total, and "Activități minimale obligatorii" = ANY of the labelled
  routes Teoretician / Compozitor / Interpret, each the ALL of its counts (the FEAA "Ruta" pattern).
- **Delivery:** built on the local agent-dev app, exported to `seed/precious-config`, descriptions in
  `indicator-descriptions/muzica-2026.json`, one idempotent prod script `h142_muzica_2026.js` with a guard,
  rehearsed on a scratch database.
- **Tests:** a definition test with a synthetic candidate holding the example grid's counts (DID 400, CS 1700,
  RIA 770); each route at each position just above and just below its counts; the formula literal-type guard;
  unit tests of the two derived variables and of the shared kind function.

### Slice 2 — the faculty's grid, out and in (large)
- **Export:** `Muzica2026ReportTypeImportSupport` with `report-templates/muzica-2026/{template.xlsx, binding.json}`
  — a clean template in the faculty's layout, built from the official text (no personal data). New binding
  kind: the items of a row listed in ONE cell, with the row's points (the faculty's format); render and parse.
- **Import into records (new; `H50` only verified):** the parser splits each cell into items (year, links,
  text kept); each item becomes an activity of the row's type, marked as imported with its source file, and a
  re-import of the same file does not duplicate (person + type + normalised text). Role and ensemble size are
  proposed where a word makes them obvious.
- **Review:** the activities page filters "imported, to check"; the person selects many rows and sets role,
  ensemble size or visibility at once, or deletes.
- **Who imports:** each colleague their own fișă (the normal flow); a head or admin can upload several files,
  each matched to a person by the name in its header, or chosen.
- **Per-person CNFIS sheets:** Anexa 5.1 rows (year, work, event, kind and level marks, participants) import as
  "Participare eveniment artistic"; Anexa 4.1 rows as citations (slice 3).
- **Tests:** a synthetic grid fixture shaped like the example (same separators, same row counts); idempotent
  re-import; header name matching; a 5.1 fixture round-trip.

### Slice 3 — event registry ranked by experts, picker, Anexa 4.1 (large) — REDESIGNED 2026-10-02, BUILT 2026-10-03

Reviewed with Adrian before building ("it may be on the wrong foot, just as the self-reported category was"). Found:
CS 1.1 / 1.2 trust the researcher's own `Vizibilitate` for an event the registry does not list (the H143 flaw
again); CNFIS 5.1 is already registry-only and leaves an unranked event out; prod holds no performance record yet.
The standard's footnotes rank HOSTS, not only festivals: top = abroad, festivals with tradition or the season/tour of
any professional institution; in Romania, festivals of great prestige with substantial international participation, or
institutions with international visibility; regional/local = seasons of philharmonic societies, city halls,
associations, museums, … (abroad) and of institutions with national/local visibility, city halls, foundations, firms,
museums, … (Romania).

Decisions (Adrian, 2026-10-02):
1. **The researcher names the event or institution, never its level.** `Vizibilitate` is removed from the record
   type; an imported grid's CS 1.1 / 1.2 row becomes a suggestion shown to the expert, never a score.
2. **An unknown name is a proposal to the registry**, shared by every record that names it (counts, years, evidence
   links) — not a request attached to one record.
3. **Experts rank events into the registry, once, for everyone:** by default the heads of the event's domain (dean,
   vice-dean for research, department directors — FMT for Music), plus experts an admin names per domain. Rank
   (top-international, international, national, **local** — new, so a known local host never counts as national for
   CNFIS), type (festival, competition, institution season, tour), country, basis and a note; or merge into an
   existing event as a spelling variant; or reject. Every record naming the event, past and future, follows; changes
   keep a history (who, when, why, the previous rank). Whoever proposed an event cannot rank it.
4. **While an event waits:** CS 1.2 (regional/local, 10 p) — the floor the standard gives any eligible public
   performance; ranking only raises it. CNFIS 5.1 keeps leaving it out until ranked.
5. **Other self-picked levels** (keynotes, juries, conference committees, associations, evaluation panels — about a
   dozen types across standards): the same pattern, as a separate task after this slice (`H144`).

Build:
- **Model:** `ArtisticEvent` gains aliases, kind, country, organiser, domains, status (proposed / confirmed /
  rejected), basis, decidedBy/At, note and history; rank gains `LOCAL`; the 303 CNFIS events become confirmed, basis
  "Lista CNFIS" (startup migration, raw Mongo).
- **Matching:** normalised name and aliases, one matcher for scoring, the CNFIS sheet, imports and the picker.
- **Expert page:** the proposal queue per domain (counts, years, evidence, the suggestions of imported grids), bulk
  ranking, merge, reject, edit confirmed events; access = heads of the domain + named experts (admin page to name
  them), never one's own proposal.
- **Seeding:** an admin operation reads an institutional Anexa 6.1 file and creates proposals from its event column
  (event names only, nothing personal); FMT's file gives about 240.
- **Picker:** `EVENT_NAME` autocomplete like the university picker, showing the level; free text creates or joins a
  proposal.
- **Anexa 4.1:** activity type "Citare sau cronică a unei creații artistice (CNFIS 4.1)" (year of the work, work,
  publication, issue, year of the citation, proof); its writer from the CNFIS template (data volume; small copy as a
  test fixture); the total on the CNFIS page, in the frozen copy and on the unit page.
- **Prod:** a script removes `Vizibilitate` from the type (no record carries it yet); descriptions of CS 1.1 / 1.2
  say how visibility is decided.

### Slice 4 — publishers and journal databases (medium to large; serves every domain)
- **Publisher categories:** `report-data/cncs-publishers-{2013,2020,2026}.csv` and the UEFISCDI foreign list,
  transcribed from the official PDFs with aliases; `PublisherCategoryService` gives a book its category (rule
  below); DID 1.1 reads it, so "publicat" needs no question. ISBN lookup (Open Library, keyless) fills a declared
  book.
- **Title lists:** collection `journal_database_memberships` (database, ISSN, eISSN, title, coverage from/to,
  policy); loaders for KBART, TSV, EBSCO XLS/HTM and RILM's pipe format; an admin operation that loads the
  lists from their URLs or from files on the data volume, before each use; `JournalDatabaseService.indexedIn(
  issns, year)` behind `MUSIC_INDEXED_JOURNAL` and a declared-article type that takes a DOI (filled from
  Crossref) or an ISSN.
- **Tests:** loader fixtures per format; ISSN normalisation; coverage years.

### Slice 5 — lookups by identifier (large; optional per source)
Recording from a barcode, catalogue number or link (Discogs with a token, MusicBrainz keyless; label, year,
format, tracks, credits, UCMR-ADA / ORDA numbers); a YouTube link checked through the Data API (duration,
date, live times; store the id and the check date); Deezer for albums; the UCMR lists (works bought, prizes,
members) as suggestions to confirm; ORCID works with a DOI as declared publications to confirm. Clients behind
deadlines, as the other external calls (`H112`).

### Slice 6 — Theatre
After the faculty's grid arrives: the same machinery, the shared types, a second report.

### Order and dependencies
Slice 0 any time. Slice 1, then 2 (it needs slice 1's types). Slice 3 can run beside 2; slice 1 starts with
exact event names and gains aliases from slice 3 without change. Slice 4 improves slice 1's CS 2.1 and DID 1.1
when it lands. Slice 5 last, source by source.

## Slices 0 and 1 as built (2026-10-02)

- **Slice 0** — `h142_fmt_data.js` in the ops folder, generated from the faculty's table joined to the imported
  staff by name (54 of 59 rows; the other five were never imported, their addresses unconfirmed). Writes only
  missing values; a different existing value is reported, never overwritten.
- **Slice 1, engine** — `MusicIndexedJournalScoringService` (`MUSIC_INDEXED_JOURNAL`); `ActivityReportingService`
  binds `N_ani` always and the four performance variables for types with an `EVENT_NAME` reference (the visibility
  basis — registry, declared, default — is noted on the row for formulas that read it);
  `ArtisticPerformanceSupport` (result, role, visibility, CNFIS kind); `ArtisticEventRankSupport` +
  `ArtisticEventRankRegistrar` (static registry, loaded on first use, reloaded after the events import);
  `CnfisReportingFacade.arts()` derives the kind and matches the event by normalised name when the exact name
  fails.
- **Slice 1, configuration** — as specified above: the 20 types, the merged shared type, 35 indicators, the report
  with 16 criteria and 6 perspectives; `indicator-descriptions/muzica-2026.json`; seed exported; pinned by
  `Muzica2026ReportDefinitionTest` (thresholds, members, routes, the points of every item, the example grid's
  totals). `SeedReportDefinition` learned to pass an event and to find a criterion without a code.
- **Readings chosen while building:** a book needs its publisher category (no category, no points; since H143
  the category is derived from the CNCS lists and the international list, not picked — see
  `h143-publisher-categories.md`); a recording with
  a declared duration under 45 minutes does not count, one without a duration does; an organiser or a keynote
  without a level counts as national; a membership without a role is a membership (5 points).

## Slice 2 as built (2026-10-02)

- **Import into records.** `ActivityFileImportService` reads two kinds of file: the faculty's fișă de verificare
  (`MusicGridParser` finds the header row by its columns and each row by its keywords, `GridItemSplitter` splits a
  cell into items — bullets, dashes, line breaks, year headers, a link alone on its line —, `MusicGridLayout` names
  the 27 rows and their activity types) and a person's CNFIS Anexa 5.1 (`CnfisArtsSheetParser`: year, work, event,
  the marked column = kind and level, participants). Each item becomes a record of its row's type with its text,
  date (full date, else the first year, else none) and links; `ActivityInstance` gained `importSource` (the file,
  and who uploaded it when a head did), `importKey` (SHA-256 of person, type and folded text — a re-import adds
  nothing) and `needsReview`. Fields are filled only where a word makes them obvious: one role named ("dirijor",
  "solist"; two named → none), the ensemble from duo/trio/cvartet/cvintet, the visibility and result from the row,
  the event when the registry names it inside the text; everything else is left to the review. The institutional
  Anexa 6.1 is refused (it attributes rows to nobody).
- **Who imports.** Each colleague, from the Activities tab ("Importă fișa (.xlsx)",
  `POST /user/workspace/activities/import-file`). A head, many files at once, from the unit row of the supervisor
  workspace ("Import fișe", `/supervisor/{departments|divisions}/{id}/activity-import`, the access rule of the unit's
  CNFIS page): a file goes to the member whose last name and one first name its heading or its name carries; a
  file naming nobody or two members is not imported, and a single file can be given a member.
- **Review.** The Activities tab shows "imported" and "to check" on each record, a "To check (N)" view, and in it a
  bar to set one field on many records (options and numbers only), mark them checked, or delete them
  (`POST /user/workspace/activities/bulk`, the person's own records only). Saving one record marks it checked. Found
  on the way and fixed: a select of the record panel showed its first option for an unset field, and Save stored
  it — an imported book became "Editură CNCS categoria A"; it now shows an empty choice and empty fields are not
  stored.
- **Export and verification.** Binding kind `ITEMS_IN_CELL` (render: every item of a row in one cell, one per line,
  the row's points beside it — the run's block total, which honours a cap; parse: each row found by its label, the
  points as typed, "360 p" included, shared equally among the row's items). `Muzica2026ReportTypeImportSupport`,
  `report-templates/muzica-2026/{template.xlsx, binding.json}`: one role per table, one block per row, named like
  the importer's rows ("CS 1.2"). The template was built from the faculty's grid by a script that copies only the
  official text of columns A–D and the databases list, with the faculty's styles; no candidate cell and no file
  metadata (the source carried the author's name and path). `GridItemDescription` writes what the person wrote
  (not the type's name, not the shortened import name) and the year. The report's export settings are in the seed
  (`reportTypeKey` `muzica-2026`, verification on, 28 indicators on 27 rows, the 7 counters not exported).
- **Verified on the lecturer's real grid (local only, removed afterwards):** 193 records, 4 without a date; bulk
  set, mark and delete; a re-import after deleting two adds exactly those two. The run exported to the grid
  reproduces the file's own totals for CS (1700) and RIA (770) row by row; DID shows 370 of 400 until the book gets
  its publisher category. Verifying the same file against the run: 8 rows match, 2 differ (the book, and a course
  the file gave no points). The verification page no longer says "no scored rows" when only activity rows were
  compared, and its section is "Activities, by section of the sheet" (was "Perspectiva D").
- **Known:** a re-import brings back records the person deleted (import keys are not remembered after a delete).

## Slice 3 as built (2026-10-03)

Generalised by H144 slice 1 before it shipped (one engine for every registry, artistic events one of its kinds;
see `docs/tasks/active/h144-self-picked-levels.md`): the classes, paths and collection below carry H144's names.

- **Registry.** `ArtisticEvent` gained aliases, kind (festival, competition, season, tour, other), country, organiser,
  status (none or `CONFIRMED` = the CNFIS list; `PROPOSED`, `REJECTED`, `MERGED`), basis (the CNFIS list or one of
  its rules, or the standard's footnote the expert applied), note, source, who proposed and decided it and when, and
  a history of every decision (who, when, the previous rank and the new one, why). Rank gained `NATIONAL_TOP` (a
  festival or an institution in Romania with international visibility) and `LOCAL`. No migration: the 303 CNFIS
  events carry no status, which reads as confirmed. `ArtisticEventRankSupport` matches only confirmed, ranked events
  (name or alias, normalised) and knows the rejected and the waiting names (`statusOf`: `RANKED`, `REJECTED`,
  `AWAITING_RANK`).
- **Scoring.** The researcher names the event or the institution, never its level: `Vizibilitate` left the type. CS
  1.1 counts an event ranked top international, international or national-top; anything else, and an event nobody
  has ranked yet, counts CS 1.2 (`ArtisticPerformanceSupport.VisibilityBasis`: `REGISTRY`, `AWAITING_RANK`,
  `NO_EVENT`). CNFIS 5.1: national-top is national, a local event is no CNFIS level, a waiting or rejected one is left
  out and says why. An imported grid's CS 1.1 / 1.2 row and a CNFIS 5.1 level become `eventLevelSuggestion` on the
  record — shown to the experts, never scored. The rankings hub lists confirmed events only.
- **Experts.** `RegistryAccessService` (`@registryAccess`): a platform admin; the experts an admin names
  per domain; the heads of the departments an admin maps to the domain (directors, and the faculty's dean and
  vice-deans through `canManageDepartment`). Admin page `/admin/registry/experts` (collection
  `scholardex.registry_domain_experts`, one document per registry domain). Experts' page
  `/user/registry/review?kind=ARTISTIC_EVENT` (sidebar item for whoever can rank): the queue — stored proposals plus every name
  records use that the registry does not know, grouped by normalised name, with counts, researchers, years, evidence
  links and the suggestions of imported files —, bulk ranking (rank, basis, kind, country, domain, note), bulk merge
  of the ticked names into any ranked event of one's domains as its spellings (aliases; the list holds every ranked
  event, grouped by domain — not the table's first 50 rows, which hid the Enescu festival in the live check), reject
  (a note is required), edit a ranked event, reopen a decision, and the decisions with their history (a name
  proposed from a file is no decision and is not listed). Nobody decides on an event their own records name or that they proposed; a
  domain outside one's own is refused.
- **Workspace.** `EVENT_NAME` is a picker over confirmed events, aliases and waiting proposals
  (`/api/entities/registry?kind=ARTISTIC_EVENT&q=`), showing the rank or "waiting"; free text stays allowed and joins the queue.
  The record panel shows the event's level, its basis and note, "waiting" (counted regional/local meanwhile) or
  "rejected" with the experts' note (`/user/workspace/activities/registry-levels`). The picker inputs (event and
  university) now fill their field.
- **Seeding.** `ArtisticEventSeedService`, from the admin page: reads only the event column of an institutional
  Anexa 6.1 ("Date de identificare ale evenimentului") and cuts each cell to the event's name — a festival or a
  contest first, then a series (season, gala, tour), then a host institution written with a capital ("Opera Română",
  not "opera La Traviata"); the name runs to the first separator, date or edition; a genitive kind becomes nominative
  ("în cadrul Festivalului X" → "Festivalul X"); a name of only generic words is dropped. Names the registry knows
  (ranked, rejected or waiting) are left alone; the others become proposals of the chosen domain, source
  "Anexa 6.1: <file>". On FMT's file: 427 cells, 98 proposals once normalised, 121 cells name no event (bare
  concerts, venues, links); the registry's own spellings differ ("Festivalul „George Enescu” (România)" vs
  "Festivalul Internațional George Enescu"), so experts merge such names rather than rank them.
- **Anexa 4.1.** Activity type «Citare sau cronică a unei creații artistice (CNFIS 4.1)» (`An_creatie`,
  `Detalii_creatie`, `Publicatie`, `Numar_publicatie`, `Dovezi`; the record's name is the work, its date the
  citation's). The CNFIS page shows the citations of the whole career up to the edition's reference date (B: year of
  the work, C: the work and its details, D: publication, issue, year; one point each) and those left out (no year of
  the work, no publication, no year of the citation); frozen copies keep them (`artsCitationRows`); downloads live
  and frozen (`generateAnexa41`, template `data/templates/AC2025_Anexa4.1-Impact_creatie_artistica-2025.xlsx`, the
  form's own total formula grows with the rows); the unit page shows each member's count when any member has one
  (the per-person total of the institution's Anexa 1). The file import reads a person's Anexa 4.1 (each citation a
  record; the same work cited in two publications is two records; a long identification is cut at its first comma,
  the rest kept as details).
- **Verified on the local app (2026-10-03, test data removed afterwards):** the script applied to the local
  database; the admin page saved Muzică → Departamentul de Muzică; FMT's Anexa 6.1 gave 98 proposals; three Enescu
  spellings merged at once into the registry's festival; Filarmonica Banatul ranked national-top (season, institution
  in Romania with international visibility); a photography festival rejected with its note; a performance record
  picked the event from the picker (ranked and waiting spellings offered) and showed its level and basis; CNFIS
  showed it in Anexa 5.1 as national; a citation record appeared in Anexa 4.1 and its download filled row 11 with the
  form's total formula grown to the new row.
- **Prod (2026-10-03, read-only check):** the performance type still has `Vizibilitate` and no record yet; no 4.1
  type; the three Music indicators exist once; 303 events in five domains (Muzică 66, Teatru şi artele spectacolului
  63, Arte vizuale 102, Cinematografie şi Media 55, Arhitectura 17), none ranked by an expert; no experts named; FMT
  (`6abb58092fb0d9482997fab3`) has Departamentul de Muzică (`…fab4`) and Departamentul de Teatru (`…fab5`), with no
  heads recorded — until heads are set or experts named, only admins can rank. The 4.1 template is not on the data
  volume yet.
- **Prod script** `rke2-overmind/feaa-2026-scripts/h142_slice3_events.js` (guard `H142_SLICE3_IMAGE_IS_DEPLOYED`, no
  restart): removes `Vizibilitate` from the performance type (typed values stay on records, unread, and are counted),
  adds the 4.1 type with the seed's id, sets the three changed Music descriptions. Rehearsed on a scratch database
  holding the pre-slice-3 seed with the performance type re-keyed and records carrying the field: the guarded run
  writes nothing, the first run applies all three, the second changes nothing; the result equals the committed seed,
  records untouched. **Prod order:** push and deploy; copy the 4.1 template to the data volume
  (`copy-to-data-pvc.sh templates data/templates/AC2025_Anexa4.1-Impact_creatie_artistica-2025.xlsx`); flip the
  guard, run the script; on `/admin/registry/experts` map Muzică → Departamentul de Muzică and Teatru şi
  artele spectacolului → Departamentul de Teatru, name experts or record FMT's heads; optionally upload FMT's
  Anexa 6.1 to seed the queue.

**The merge list as a search (2026-10-03).** Adrian found the dropdown of every ranked event unusable (303 locally,
several hundred in prod). The select stays, as the submitted field and the no-JavaScript fallback, but
`registryMergeSearch.js` turns it into a search box:
- it ignores diacritics, case and punctuation (the registry's own normalisation), so «garana» finds «Gărâna Jazz
  Festival»;
- every word typed must match, in any order;
- it also searches the entry's other spellings, and shows which one matched («Scris și: …»);
- it lists at most 15 results; arrows and Enter pick one, and Enter never submits the ranking form.

`node scripts/test-registry-merge-search.js` covers the matching.

## Slice 4 as built (2026-10-03) — journal databases from their title lists

Built 2026-10-03, not yet committed at the time of writing. **Decisions (Adrian, 2026-10-03):**
- **EBSCO and ProQuest:** their subject databases count (Music Index, RILM Full Text, Art Full Text, Humanities
  Source, Central & Eastern European Academic Source, Music Periodicals Database), and so do the general ones
  (Academic Search Ultimate, ProQuest Central).
- **How the lists go in:** like the DOAJ, ERIH, WoS and Scopus data. The files are downloaded by a person, placed on
  the data volume and imported by an admin step; the server fetches nothing.
- **Why nothing is fetched automatically:** the terms of EBSCO, OUP, Taylor & Francis and CEEOL forbid robots, and
  JSTOR, OUP and T&F answer scripts with a challenge.

The publisher half of the planned slice was done by H143.

**Files.** Each database has its own folder under `data/journal-databases/<DATABASE>/`: `CAMBRIDGE_CORE`, `CEEOL`,
`EBSCO`, `JSTOR`, `OXFORD_ACADEMIC`, `PROJECT_MUSE`, `PROQUEST`, `RILM`, `SCIENDO`, `TAYLOR_FRANCIS`. A folder may
hold any number of lists, as the vendors publish them:
- KBART;
- tab, pipe, semicolon or comma text with a header row (notes above the header are fine);
- an HTML table;
- `.xls`, or `.xlsx` (read as a stream);
- a ZIP of any of these.

`TitleListParser` skips rows without an ISSN, and rows that are not serials (books, newspapers, reports, theses,
recordings, websites).

**Import.** On `/admin/initialization`, «Import journal databases' title lists» (`POST /general/journalDatabases`,
`JournalDatabaseDataService`) writes `journaldb.journal_facts`:
- one fact per journal and database, keyed `DATABASE:ISSN`;
- rows that share an ISSN are one journal;
- coverage years are kept for reference only.

What happens to each database:
- **All its files read:** its facts are replaced.
- **A file does not read:** its previous facts stay, and the message names the file.
- **No folder:** it is left untouched.

The collection is reference data, like `doaj.journal_facts`: it is outside the rebuild's wipe lists. The same step
then onboards the journals (`JournalDatabaseOnboardingService`), create-or-match through `ForumMergeEngine`, the
DOAJ path; it also runs inside `ScholardexForumBuilder`, after DOAJ and before WoS, so a rebuild keeps it. An ISSN
match tags the forum's `journalDatabaseIds`. A journal only the lists know becomes a forum, so a researcher who
names it by ISSN finds it.

**Publish.** Scopus → 3 (build projections) writes `scholardex_forum_membership_view` rows (`database` = the
database, `source` = `TITLE_LIST`), matched by ISSN like DOAJ.

**Scoring.** Scoring reads the memberships through `getForumIndexingDatabases`, like DOAJ's. Membership is the
lists' present state, as for DOAJ and Scopus; coverage years do not count.
- Music, CS 2.1, found automatically (`MUSIC_INDEXED_JOURNAL`): an article in a journal of any of the ten databases
  counts; the category is the database's name.
- Music, CS 2.1 declared and its count: the `muzica2026` flag makes `N_baze_date` count the ten.
- Sociology, I.2 found automatically (`SOC_INDEXED_JOURNAL`): EBSCO, ProQuest, CEEOL, JSTOR, Project MUSE and
  Taylor & Francis count towards the three databases (Informa and Tandfonline are one database).
- Sociology, declared I.2, the C.4 count and I.11's years: the `sociologie2026` flag does the same.
- Other standards are unchanged, since each filters to its own list: CNFIS reads ERIH only; Psychology and
  Educational Sciences use the Comisia 28 lists; the general rule counts WoS, Scopus, ERIH and DOAJ.

**Tests.**
- `TitleListParserTest`: KBART, pipe, CSV, `.xlsx`, ZIP, HTML, a header after notes, non-serials.
- `JournalDatabaseDataServiceTest`: merging, a database that keeps its previous facts, no folder.
- `JournalDatabaseOnboardingServiceTest`: tagging and creating a forum.
- `ScholardexProjectionBuilderServiceTest`: the membership rows.
- `ScholardexForumBuilderTest`: the order of steps, and the dedup a tag triggers.
- `GeneralInitializationServiceTest` and the initialization controller and security contract tests: the step.
- The Music and Sociology scorer tests and both report-definition tests.

**Prod script.** `h142_slice4_journal_databases.js` (guard `H142_SLICE4_IMAGE_IS_DEPLOYED`, no restart) sets five
flags (`muzica2026` on `Muz26_CS_2_1_decl` and `Muz26_N_articole_decl`; `sociologie2026` on `Soc26_I2_decl`,
`Soc26_C4_articole_decl` and `Soc26_I11`) and five descriptions (`Muz26_CS_2_1`, `Muz26_CS_2_1_decl`, `Soc26_I2`,
`Soc26_I2_decl`, `Soc26_I11`).
- **Rehearsed on HEAD's seed:** the guarded run writes nothing; the first run sets the 5 flags and the 5
  descriptions; the second changes nothing; the result equals the committed seed.
- **Precheck, read-only against prod (2026-10-03):** 5 of 5 flags and 5 of 5 descriptions to change; prod equals
  the committed base.

**Sources (checked 2026-10-03; HTML pages only, no list fetched).**

| Folder | List(s) | Format | Terms of the list |
|---|---|---|---|
| `CAMBRIDGE_CORE` | cambridge.org/core/services/librarians/kbart, "Cambridge Journals: All journals" | KBART | metadata CC0 |
| `JSTOR` | jstor.org/kbart/collections/all-archive-titles?contentType=journals (Complete Title History) | KBART or XLSX | "for reference use only"; site behind a JS challenge |
| `PROJECT_MUSE` | about.muse.jhu.edu/static/org/local/holdings/muse_journal_metadata_2026.tsv | TSV, CSV, XLSX | site licence: not to be incorporated into a retrieval system unless stated |
| `PROQUEST` | tls.search.proquest.com/titlelist/ListForward?…&format=tab: Music Periodicals Database (1007570) and ProQuest Central (its component ids, from the product page's form) | tab text | none stated; use the tab output with citation/abstract dates (KBART is full-text holdings) |
| `EBSCO` | about.ebsco.com/m/ee/Marketing/titleLists/: `mah-coverage.xls` (Music Index), `mft-coverage.xls`, `aft-coverage.xls`, `hus-coverage.xls`, `hsi-coverage.xls`, `e5h-coverage.xls`, `asn-journals.xls` | XLS (an HTM twin) | site terms forbid systematic or automated collection without EBSCO's written consent |
| `RILM` | api.rilm.org/ibis/200/marketing/products?product=ram&type=music (and `type=nonmusic`, `product=rft`) | pipe lines; check for a header line, add one if missing | none found |
| `OXFORD_ACADEMIC` | fdslive.oup.com/www.oup.com/academic/content/librarian/: `OxfordUniversityPress_Global_2026JournalsCurrentCollection.zip`, `OUP_2025_JournalsAllTitles.zip` | ZIP of KBART | site notice forbids robots |
| `TAYLOR_FRANCIS` | GOKb, Jisc list: gokb.org/gokb/packages/kbart/06f8a279-01f5-48b2-b695-3ca85b061a7a?exportType=title (2,443 titles); T&F's own KBART is behind Cloudflare | KBART | GOKb: CC0 |
| `CEEOL` | GOKb, German consortium package: gokb.org/gokb/packages/kbart/35c3a7d9-d507-44f3-8339-952baa6edf13?exportType=title (1,304 of ~3,100 journals); CEEOL publishes none | KBART | GOKb: CC0 |
| `SCIENDO` | none public (KBART behind a librarian login at reference-global.com) | — | ask the library to request it |

**Downloaded 2026-10-03.** With Adrian's go-ahead, these lists were downloaded to the local
`data/journal-databases/` (git-ignored). The server answered a plain request each time, with no challenge, and no
robot clause applies to them:

| File | Journals | Rows skipped (no ISSN, or not a serial) |
|---|---|---|
| `CAMBRIDGE_CORE/cambridge-journals-all-journals-2026-10-03.txt` | 884 rows → 737 journals | 1 |
| `PROJECT_MUSE/muse_journal_metadata_2026.tsv` | 868 | 0 |
| `PROQUEST/music-periodicals-database-2026-10-03.txt` | 600 | 69 |
| `PROQUEST/proquest-central-2026-10-03.txt` (11.8 MB) | 26,073 | 9,958 |
| `RILM/rilm-abstracts-music-journals-2026-10-03.txt` | 2,603 | 900 |
| `RILM/rilm-abstracts-nonmusic-journals-2026-10-03.txt` | 10,634 | 3,697 |
| `TAYLOR_FRANCIS/gokb-jisc-taylor-francis-read-and-publish-2026-10-01.txt` | 2,443 | 0 |
| `CEEOL/gokb-ceeol-t10-nationalkonsortium-2026-09-23.txt` | 1,272 | 34 |

Three of the files needed parser work:
- **Project MUSE:** notes come before the header.
- **ProQuest:** the tab export is wrapped in `<pre>` and encoded as Windows-1252. Its non-serials are skipped:
  reports, newspapers, blogs, wire feeds, books, working papers.
- **RILM:** there is no header row.

**The local run.** The import step processed 43,382 journals in 32 s:

| Database | Journals |
|---|---|
| ProQuest | 26,090 |
| RILM | 11,989 |
| Taylor & Francis | 2,443 |
| CEEOL | 1,255 |
| Project MUSE | 868 |
| Cambridge Core | 737 |

It tagged 25,898 existing forums and created **17,483 new ones**, so the registry grew from 75,302 to 92,784, with no
identity conflicts. Of the new forums:
- 8,737 come from ProQuest only, mostly ProQuest Central's trade journals and magazines;
- 7,313 come from RILM only;
- the rest come from CEEOL (423), Cambridge (144), MUSE (112), T&F (71) and combinations.

After Scopus → 3, 37,961 forums carry a title-list membership, and 18,373 of them are in Scopus or WoS too.

**Downloaded by Adrian in a browser (2026-10-03).**

| File | Journals | Rows skipped |
|---|---|---|
| EBSCO `asn-journals.xls` (Academic Search Ultimate) | 20,176 | 323 |
| EBSCO `hsi-coverage.xls` (Humanities Source Ultimate) | 4,062 | 897 |
| EBSCO `hus-coverage.xls` (Humanities Source) | 4,061 | 897 |
| EBSCO `e5h-coverage.xls` (Central & Eastern European Academic Source) | 4,045 | 136 |
| EBSCO `aft-coverage.xls` (Art Full Text) | 1,971 | 495 |
| EBSCO `mah-coverage.xls` (Music Index) | 879 | 60 |
| EBSCO `mft-coverage.xls` (Music Index with Full Text) | 879 | 60 |
| `JSTOR_Global_AllArchiveTitles_2026-10-03.txt` | 5,021 | 0 |
| `tandf_Global_AllTitles_2026-10-03.txt` | 6,103 | 6 |

Five of the EBSCO workbooks label the identifier column "ISSN / ISBN"; the parser now recognises it.

**With every list loaded (local, 2026-10-03).** The import read 77,556 journals in 43 s:

| Database | Journals | Lists |
|---|---|---|
| EBSCO | 27,712 | 7 |
| ProQuest | 26,090 | 2 |
| RILM | 11,989 | 2 |
| JSTOR | 4,630 | 1 |
| Taylor & Francis | 4,275 | 2 |
| CEEOL | 1,255 | 1 |
| Project MUSE | 868 | 1 |
| Cambridge Core | 737 | 1 |

The registry grew from 75,302 to **102,192 forums**: 26,891 of them now exist only because of the lists. By the
first list naming them, those come from EBSCO (9,838), ProQuest (7,341), RILM (6,627), JSTOR (1,894) and the rest
(1,191).

**Still to download in a browser:** OUP's 2026 current-collection and 2025 A–Z ZIPs.

**Rollout, done 2026-10-03.** Adrian pushed and deployed image `f98af4d8`.
- **Script.** `h142_slice4_journal_databases.js` (guard flipped) ran: 5 of 5 flags, 5 of 5 descriptions.
- **Lists.** The eight folders were copied to the data volume. The loop's eighth copy failed because the helper pod
  of the previous run was still terminating; `copy-to-data-pvc.sh` now waits for it to go first.
- **Import.** «Import journal databases' title lists» loaded 77,556 journals (the same counts as locally). The
  forum matching created 26,916 forums (registry 75,340 → 102,255); 56,166 forums carry a list id. Adrian's browser
  lost the connection during the step, but the server finished it.
- **Projection.** Scopus → 3 took 7 minutes (689,213 rows, 0 errors). The read model holds the memberships:

  | Database | Forums |
  |---|---|
  | EBSCO | 27,666 |
  | ProQuest | 26,039 |
  | RILM | 11,850 |
  | JSTOR | 4,618 |
  | Taylor & Francis | 4,268 |
  | CEEOL | 1,252 |
  | Project MUSE | 868 |
  | Cambridge Core | 737 |

  That is 55,922 forums with a list membership.
- **Refresh.** Adrian refreshed both reports at 13:26–13:34 UTC: 54 Music runs and 39 Sociology runs, the first
  either report ever had.
- **No visible change yet.** None of the 54 Music staff and only 1 of the 39 Sociology staff have confirmed
  publications (the "no confirmed publications" warning); the declared CS 2.1, I.2 and I.11 types have no records in
  prod.
- **What the lists will add**, once staff confirm their publications or declare articles by ISSN (prod read model):
  - 27,377 journals count for Music CS 2.1 only through a list (they are in none of WoS, Scopus, ERIH, DOAJ);
  - 2,065 journals outside Scopus reach Sociology I.2's three databases only with the lists (398 did before).
- **Left:** OUP's two ZIPs.

**Rollout order.**
1. Push and deploy.
2. Flip the guard and run `h142_slice4_journal_databases.js`.
3. Download the lists (in a browser) and copy each folder to the data volume, for example
   `./copy-to-data-pvc.sh journal-databases/EBSCO mah-coverage.xls asn-journals.xls`.
4. On `/admin/initialization`, run «Import journal databases' title lists».
5. Run Scopus → 3.
6. Refresh the FMT Music and FSAS Sociology reports («Reîmprospătează tot»).

**Open.**
- ~~Size of the forum registry~~ — **decided 2026-10-03 (Adrian): keep them all.** The general lists (EBSCO
  Academic Search Ultimate, ProQuest Central, RILM's non-music periodicals) bring about 27,000 new venues, the way
  DOAJ brought its own, so that a journal any list knows can be named by ISSN.
- **Terms of use.** Is this use of the lists covered? KBART lists exist to be loaded into library systems, and
  the platform uses them the same way (matching ISSNs for an internal evaluation). But EBSCO's, MUSE's, T&F's and
  CEEOL's site terms are restrictive. A question for the library (which of these UVT subscribes to) and for
  Adrian.
- **Educational Sciences.** Its list of databases names JSTOR and CEEOL too; `Comisia28Rules` still counts
  SCOPUS, ERIH and DOAJ only. A decision for the faculty.
- **More of Sociology's definition [7].** It also names De Gruyter (Sciendo's owner), ScienceDirect, SpringerLink,
  Cairn.info, EconLit, PubMed, RePEc, Persée and HeinOnline. Several publish lists; none are loaded yet.
- **Sciendo.** It has no public list; its journals count through DOAJ (most are open access) or a request a head
  approves.

## Slice 7 as built (2026-10-03) — the faculty's CNFIS 2025 reports

Simona Negru sent FMT's CNFIS 2025 submission, one folder per teacher: 41 in Music and 6 in Theatre. Each holds
Anexa 5 (articles and patents), 5.1 (artistic performance) and 4.1 (citations of artistic works), plus the signed
PDFs and the proofs. **Decisions (Adrian, 2026-10-03):**
- The files were already submitted, so their records are imported as confirmed.
- The levels the faculty reported rank the events in the registry.
- The sheets' "Toma Iulia-Magdalena" is the platform's Tecu Iulia-Magdalena.
- Five people without an account are left out until Adrian asks the faculty.

Slice 5 (lookups by identifier) is postponed. ISBN lookup belongs in the researcher workspace for every faculty, as
would an import of ORCID works.

**Built:**
- **The name inside a CNFIS sheet.** `CnfisSheets.personName` reads the value under "Nume şi prenume". It skips the
  guidance line of Anexa 5.1 and stops at a label, note or job title, so a blank name stays blank. `headingOf`
  returns it, so the bulk import matches a file by the name inside, not only by its file name.
- **Anexa 5 → declared CS 2.1 articles.** `CnfisArticlesSheetParser` reads year, title, DOI (a resolver URL
  stripped), WoS code, journal, ISSNs and ISBNs. It skips patents, and the person's classification marks are not
  read (H145). Each article becomes a «… (Comisia 35, CS 2.1)» record: its journal named by the first ISSN, so the
  title lists and the corpus decide its databases; the other ISSNs, the WoS code and the ISBNs go to the evidence.
- **5.1 rows without a year.** A numbered row with an empty year takes the last year its texts name; guidance rows
  have no number.
- **Event names.** A 5.1 row's event is the festival, series or host the cell names, cut like Anexa 6.1's. Otherwise
  it is the cell without its leading date, so the Christmas concert reported for four years is one name.
- **The faculty's submitted files** (`ActivityUnitImportFacade.importFacultySubmission`; an option on the unit
  import page, for platform admins only, with the domain of new events). Records land checked (no "to check"), with
  the source "Raportare CNFIS 2025 (depusă de facultate) — Anexa …". Then `ArtisticEventFacultyRanking` ranks the
  events once per batch:
  - **one level reported:** confirmed at that level, basis `FACULTY_CNFIS_REPORT`, the counts in its note;
  - **different levels reported:** a proposal for the experts;
  - **a new name holding every distinctive word of a ranked event** ("Festivalul Internațional GEORGE ENESCU" vs the
    CNFIS list's "Festivalul «George Enescu»"): a proposal, noted as a likely spelling;
  - **already ranked** (the CNFIS list, an expert): unchanged;
  - **rejected or merged:** unchanged.
- **Faculty tables.** An institutional table (Anexa 6, 6.1) is never one person's file, whatever its columns (the
  Music Anexa 6.1 used to read as a citations sheet).

**Dry run** (the real files through the real code, against the prod roster of the two departments: 39 Music, 15
Theatre):
- All 120 files of the upload set match exactly one person: 40 people, none unmatched, none ambiguous.
- They hold 416 performances and prizes, 1,187 citations and 41 articles.
- 166 distinct events are outside the CNFIS list: 158 reported at one level (76 international, 4 top
  international, 78 national), and 8 at different levels.
- The 8: Eufonia, Meridian, Intrada, Remus Georgescu, George Enescu, The Brave New Music, Timișoara Muzicală and the
  ICONS tour. The experts (admins, as long as FMT has no heads) decide them on `/user/registry/review`.

**Upload set.** In `~/Downloads/Raportare CNFIS 2025/_de_incarcat/`:
- **The upload folders:** `Muzica_1` (54 files, 18 people), `Muzica_2` (54 files, 18 people) and `Teatru` (12 files,
  4 people). Each file is copied as «Nume Prenume - original.xlsx» after the roster, so initials-only file names
  match too.
- **Duplicates:** one copy per annex; the newer of Ioachimescu's duplicates and Rădoiaș's resubmitted set.
- **Not uploaded:** the faculty tables, the index file and Dorobanțu's unreadable `DOROBANTU_I_5 2021-2024 (2025).xlsx`
  (not a workbook; his three annexes are in).
- **Set aside:** `_fara_cont/` holds the files of the five without an account.

**Prod steps** (after the push and deploy):
1. On `/supervisor/departments/6abb58092fb0d9482997fab4/activity-import` (Departamentul de Muzică), tick «Fișe pe
   care facultatea le-a depus deja», keep the domain Muzică, and upload `Muzica_1`, then `Muzica_2`.
2. On `/supervisor/departments/6abb58092fb0d9482997fab5/activity-import` (Departamentul de Teatru), do the same
   with `Teatru` and the domain «Teatru şi artele spectacolului».
3. Rank the 8 conflicting events on `/user/registry/review`.
4. Refresh the Music report («Reîmprospătează tot»).

**First upload, refused (2026-10-03).** Image `002365e4` refused the 54 files of `Muzica_1` with HTTP 413. Tomcat
allows 50 parts per multipart request (`server.tomcat.max-part-count`); nothing was written. The limit is now 250,
and a local run took 60 files at once.

Fixed at the same time: with Music in two uploads, a second batch saw the first batch's ranks as final. Now a later
batch of the same report adds up (same level: the counts grow) or sends the event to the experts (another level),
and an event an earlier batch left to the experts stays theirs.

**In prod (image `18f6bf01`, 2026-10-03).** The three uploads brought 1,638 confirmed records (415 performances,
1,182 citations, 41 articles) for 39 people; Cinc's sheets are empty. 124 events were ranked and 41 waited for the
experts: 7 reported at different levels and 34 "likely spellings".

**Re-check (2026-10-03).** The upload had two faults:
- **The spelling check matched one common word.** «teatru» took every concert of the «Facultatea de Muzică și
  Teatru» for the National Theatre Festival. «iasi» took every recital in Iași for FITPTI, and «noi» took the
  Orăștie sacred-music festival («Cu noi este Dumnezeu») for Zilele Muzicii Noi. The check now needs at least two
  distinctive words: those outside the parentheses, or all of them when fewer than two are outside.
- **The upload recorded the uploader as the proposer of its 165 events.** The experts' page refuses a proposer
  their own names, so the uploader could decide none of them. An upload's new events now have no proposer; the
  source says where they came from.

`h142_slice7_recheck.js` (ops scripts, run with `--restart`) repairs prod:
- clears the proposer of the upload's events;
- ranks the 12 names that are no spelling at the single level reported (concerts named by venue, the Orăștie
  festival, a symposium, the WePerform season);
- merges 16 spellings into their listed event, as the experts' page would, and only where the registry gives the
  level the faculty reported.

The tightened check misses five of the 16: in each, the listed name has one distinctive word and its city in
parentheses. Their records name them:
- Meridian (concerts in București and Timișoara), twice;
- SIMN (Radio București);
- TESZT;
- FITPTI ("…Publicul Tanar, Iasi. FITPTI").

**Run in prod (Adrian, 2026-10-03, with `--restart`).** The run cleared 165 proposers, ranked 12 names and merged
16, as rehearsed. A read afterwards showed the upload's events at 136 confirmed, 16 merged and 13 waiting; the 13
are named by 47 records.

Left for the experts (13):
- **Reported at different levels (7):**
  - George Enescu: 8 × top international, 1 × national; the CNFIS list ranks it top international.
  - Meridian «de Muzică Contemporană»: 4 × international, 3 × national.
  - Remus Georgescu «de Muzică Nouă»: 4 × international, 2 × national.
  - Intrada: 2 × international, 2 × national.
  - Timișoara Muzicală: 1 × international, 2 × national.
  - The Brave New Music: 1 × international, 1 × national.
  - The ICONS national tour: 1 × international, 10 × national.
- **Three links:** two pages on FMT's chamber-music festival and one Gărâna Jazz page.
- **«Iași, 17-19 noiembrie 2022»:** a place and dates that name two events: the national Caudella competition and an
  international conference at UNAGE Iași.
- **The «Elite musicians» jury:** reported national, while the same report ranks the competition international.
- **«Orchestra simfonică Remus Georgescu»:** the Banatul Philharmonic's orchestra in a May concert, not the October
  festival, though it holds the festival's words.

Some events the faculty spelled two ways became two entries, each ranked at the level reported, so scores do not
change: «Festivalul Internațional BRAVE NEW MUSIC» and «Festivalul BRAVE NEW MUSIC», for example.

**Scores after the upload (2026-10-03).** After the merges and rejections and a full refresh, 40 of FMT's 54
people scored 0 in FV Muzică 2026. A read of prod gave the reasons:
- **Performances (415).** A performance counted only when its record states the person's role (H145, C5), and the
  CNFIS 5.1 sheets carry none. They mark only alone, a group of 2–4 or a collective of 5+: 147, 47 and 206 records
  (the import keeps that as the ensemble's size, 1, 2 or 5).
  - **Decision (Adrian): the size decides for a blank role.** One person or a group of up to four counts (soloist or
    creator, chamber member). A larger ensemble still needs the role, which the person sets on their records.
  - This holds for typed records too. 194 performances count now.
- **Prizes (10).** A prize counts (RIA 2.3) only at an event the registry calls a competition. The upload ranked
  the events without a kind.
  - **Decision (Adrian): a prize the faculty reported makes its event a competition.** The upload does this from
    now on, but only for a ranked event without a kind: an expert's kind stays, and a waiting name gets its kind
    from the expert who ranks it.
  - `h142_slice7_scoring.js` (ops scripts) marks the 8 existing events: Caudella, Crizantema de Aur, Rapsodii de
    Toamnă, Te aștept pe același drum, Te Deum Laudamus, Orăștie, Golden Plaque and Elite Musicians. It also
    updates the descriptions of CS 1.1, CS 1.2 and RIA 2.3.
- **Citations (1,182).** They feed CNFIS Anexa 4.1 only; the Music standard has no citation item.
- **Not performances (86).** These «collective» rows have no event: workshops, Erasmus and teaching courses,
  certificates. They stay at 0.
- **No records (15 people).** Eleven in Theatre (no Theatre report yet, slice 6) and four in Music: Cinc's sheets
  were empty, and three have no file in the submission.

**What a record lacks, supplied by its owner (2026-10-03).** Adrian chose that people supply what is missing
themselves, rather than the platform guessing it from text (for example an ISSN written in a title).
- **Decisions:**
  - A correction applies at once: it is data the person enters (H145).
  - Each change is kept on the record.
  - Built now: the filter with its reasons, and the move to another type.
  - Not now: bulk edit outside «de verificat», and notifications.
- **«Nu se punctează încă».** A filter on the activities panel, with a badge per record and the reasons in the open
  record (`ActivityGapsFacade`, `GET /user/workspace/activities/gaps`). The reasons follow the scoring's own rules:
  - a performance in an ensemble of 5+, or of unknown size, without a role;
  - an article without an ISSN;
  - an ISSN that no list covers. The fix is the classification request that already existed («Încadrare
    solicitată», with evidence, decided on `/supervisor/declarations`). A request the head is deciding is no gap.

  A type a standard does not score (citations in Music) is no gap either.
- **«Mută la alt tip».** For a record filed under the wrong type, e.g. a critical edition filed as an article
  (`POST /user/workspace/activities/move`).
  - The name, date, source and import key stay, so a re-import does not bring the record back.
  - The values the new type declares go with it. The others go to «Dovezi» and to the history.
  - A request the new type has no field for lapses.
  - The type list is a search (the merge list's).
- **History.** `ActivityInstance.changes` holds `EDITED` entries (each value, old → new; single and bulk saves) and
  `MOVED` entries (from, to, the values that had no field). The open record shows the last 10.

**Waiting for the faculty (Adrian asks Simona):** five teachers sent files but have no account in prod: Fănel Ignat,
Vlad Popescu and Manuela Mihăilescu (Music), Otilia Huzum and Florin Vidam (Theatre). Their files are in
`_fara_cont/`. Once they are added with the staff import, one more upload with the same option brings them in;
deploy the re-check's code first, so the upload proposes nothing in the uploader's name.

## Still to decide

1. ~~Which EBSCO and ProQuest databases count~~ — decided 2026-10-03: the subject databases and the general ones
   (slice 4 as built).
2. Which CNCS publisher list applies: the 2026 one, or the one in force when the book came out; and whether the
   Music list only, or any domain's list. **H143 implements the proposal below as a reading to confirm.** Proposed: the best category the publisher had in any list from 2013
   on, in the Music domain first and any domain second — it matters for Editura Universității de Vest.
3. Keys: a YouTube Data API key and a Discogs token, kept like the other keys (not in the repository).
4. Requests: Sciendo for its KBART file (CEEOL is covered in part by a GOKb list, slice 4); UCMR-ADA for the
   repertoire (later).

## What to ask the faculty

Nothing is required: each colleague imports their own fișă de verificare. If the faculty already holds the
filled grids of the Music staff, or the per-person CNFIS 2025 sheets (Anexa 5.1 and 4.1) from the archive the
guide asks every teacher to send, an admin import of them speeds everything up. The Theatre grid is announced.

The reply to the vice-dean, left as a draft in Adrian's Gmail, in her thread (2026-10-02):

> Bună, Simona,
>
> Mulțumesc pentru documente, ne sunt de mare folos. Lista de personal a facultății este deja încărcată în
> platformă, iar acum urmează fișa de verificare pentru Muzică, după noile standarde.
>
> Ca nimeni să nu fie nevoit să reintroducă manual activitatea, fiecare coleg își va putea importa în platformă
> fișa de verificare pe care o are deja completată, în formatul facultății, iar activitățile vor fi preluate
> automat.
>
> Dacă aveți deja fișele colegilor sau fișele individuale CNFIS 2025 (Anexele 5.1 și 4.1), ne-ar ajuta să le
> primim: le putem importa pentru toată facultatea dintr-odată, iar totul s-ar accelera.
>
> Grila pentru Teatru o aștept când este gata.
>
> O zi frumoasă,
> Adi

## Not in scope

- A 2016 Music report. CNFIS 2025 asked for the CNATDCU score under OM 6.129/2016; that edition is past, the
  sheet accepts a typed score, and only 2026 standards are built (decision of 2026-09-29).
