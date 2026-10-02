# H143 — the category of a declared book's publisher comes from the lists, not from the researcher

Status: **BUILT 2026-10-02 (not pushed), review round the same day.** Asked by Adrian the same day ("I want the real
fix"), after the H142 slice 2 review showed that a researcher picked their own book's publisher category, and that the
record panel stored the top category by default. Prod checked read-only first: no record of any affected type exists,
so no score was ever inflated. Review round (Adrian, before the push): international lists and rankings, "like we do
with SENSE for computer science", decide before any head is asked; and where a standard leaves the list open, the
platform's reading is stated in the description the researcher reads.

## What was wrong

Four 2026 reports let the researcher classify the publisher of a book declared as an activity, and the formulas
trusted the pick:

| Report | Activity type | Picked field |
|---|---|---|
| FV Muzică 2026 | Tratat … (Comisia 35, DID 1.1) | Incadrare_editura (CNCS A, B, foreign equivalent, other) |
| FV Sociologie 2026 | Carte sau capitol declarat (Comisia 25, I.3, I.4, I.6) | Incadrare_editura (Lista A1, A2, WorldCat) |
| FV Sociologie 2026 | Colecție de carte (Comisia 25, I.12) | Lista (A1, A2) |
| FV Psihologie / Științe ale educației 2026 | Carte coordonată (Comisia 28, I17) | Categorie_editura (A1, A2, B) |

The add form and the record panel both preselected the first option, the top category in every case. Three more
rows ignored the publisher altogether although their standard requires a listed one: Sociology I.5 (coordinated
books, definition [4]), Sociology I.8 translations ("la edituri din lista A2") and Educational Sciences I21
(collections "în edituri clasificate A1, A2 sau B").

The corpus path was already right: `PsychologyBookScoringService` classifies a corpus book's publisher from the
commissions' 2026 lists and the WoS Master Book List.

## What the platform does now

- **The category is derived** (`PublisherRules`, `PublisherCategoryService`, `PublisherCategorySupport`): from the
  publisher the researcher TYPES (`Editura`), under the rules of the indicator's standard (its 2026 flag), into the
  formula variable `Categorie_editura` — the best of the listed category and an approved request, null when
  nothing counts. The row notes the category and its basis; a zero says "publisher not classified".
- **International prestige** (Comisia 25 Lista A1, Comisia 28 A1, Comisia 35's foreign equivalent): the WoS Master
  Book List, else an international list or ranking (`InternationalPublisherListService`, read through the static
  `InternationalPublisherSupport`), in the order of `report-data/international-publisher-lists.csv`:
  - SENSE, ranks A and B (C to E do not count): the `senseRankings` collection the Computer Science book scorer
    reads, imported from the admin initialization page; loaded on first use, looked at again at most once a minute
    while empty or unreachable;
  - UEFISCDI, publishers for the social sciences (PD/TE 2026, Anexa 7c, 253);
  - UEFISCDI, publishers of international prestige in the arts and humanities (349).
  A further ranking is one fixture (a `name` column, and a `level` column for a ranking) and one row of the index
  (key, label, source, the levels that count). The same lists serve corpus books (`PsychologyBookScoringService`,
  `tierBasis` = the list's key), so a book gets the same category whether found or declared. Lambert Academic
  Publishing never counts.
- **A Romanian house is never looked up on those lists:** a name written «Editura …», or named as on a Romanian list
  (CNCS, the commissions' lists, the four Romanian houses of the Master Book List). The lists hold short foreign names
  that collide with Romanian ones: Anexa 7c's French *Economica* would make Editura Economică A1, an Italian house
  would make Paideia foreign. A Romanian name of generic words only (CNCS rates an "Editura University Press") must
  match exactly, or it would make Oxford and Harvard Romanian.
- **Names on those lists** are compared as token sets: equal; the listed name inside the typed one when it holds an
  identifying word ("Routledge, London"); the typed name inside the listed one only when the listed name adds generic
  words ("Polity" → Polity Press; not "Business Press" → Harvard Business School Press, nor "University of Arizona" →
  Arizona State University).
- **Comisia 25 (Sociologie):** the annex's A2 list, else A1 for a house of international prestige — exactly as for
  corpus books.
- **Comisia 28 (Psihologie, Științe ale educației):** the domain's 2026 list (A2, B), else A1 for a house of
  international prestige (indicative, as for corpus books).
- **Comisia 35 (Muzică):** "publicat" = a publisher CNCS classifies A or B, or an equivalent foreign one:
  - the CNCS classifications of publishers in the humanities and arts, 2026 (OM 5.100/26.08.2026, 168 rows),
    2020 (155) and 2013 (performing arts, 23): the best category in the **Music domain of 2020 and 2026** when the
    publisher has one there (Universitaria, rated C in Music twice, does not count), else its best category in any
    domain of the three lists (Editura Universității de Vest: A in Filologie 2026);
  - foreign: a foreign house of international prestige, as above (the Master Book List's Romanian houses — Excelsior
    Art, Argonaut, … — are not foreign; Bärenreiter, Casa Ricordi, Universal Edition are on the UEFISCDI arts list).
- **Names on the Romanian lists** are matched on their distinctive words (no diacritics, no "Editura"/"SC"/"SRL", no linking words): equal,
  the listed name inside the typed one, or a typed name of two or more words inside the listed one. A few short
  forms are aliases (`report-data/publisher-aliases.csv`: UVT, UNMB, PUC, UNATC, UNArte, Grafoart, Ricordi).
- **Where no list decides**, the record may ask for a category its standard grants one book
  (`Incadrare_solicitata` + `Dovada_incadrarii`): Sociology A1 (an international house on none of the lists), six
  WorldCat libraries (as A2), or a book published before the current list at a house of the earlier list (as A2, see
  below); Comisia 28 A1 by 25 EU/OECD university libraries in WorldCat, A2 or B by the complementary route, A1 for a
  collection; Music an equivalent foreign publisher on none of the lists. The request is PENDING until a
  head of the researcher's department or faculty (or an admin, never the researcher) approves or rejects it on
  `/supervisor/declarations` (now "Declarații de verificat"); changing what the record asks sends it back. A
  request never lowers a listed category. Reasons are required to reject or to take an approval back.
- **The researcher sees** the category per standard, its source and the request's state in the record panel
  (`GET /user/workspace/activities/publisher-categories`).
- **No preselected option** anywhere: the add form and the record panel start every select empty, and an empty
  field is not stored.
- **Imports** (H142 slice 2): an imported book whose line names a listed publisher (two words or more) gets its
  `Editura` filled, so its category follows without typing.

## Data

`src/main/resources/report-data/cncs-publishers.csv` (list, domain, category, name) and
`uefiscdi-arts-humanities-international-publishers.csv` (nr, name, url), parsed from the official texts:

- 2026: cncs-nrc.ro/wp-content/uploads/2016/12/RVED-2026-categorii.edituri.concatenate.ORDIN_.site_.pdf
- 2020: uefiscdi.gov.ro/resource-868609-categorii.edituri.site.cncs.2020.-2021-.pdf
- 2013: the CNCS 2013 classification of publishers, domain "Artele spectacolului" (B and C)
- international, arts and humanities: old.uefiscdi.ro/userfiles/file/CENAPOSS/Edituri prestigiu international_Arte
  Stiinte Umaniste.pdf
- international, social sciences: Anexa 7c of the PN-IV PD/TE 2026 packages (H136,
  `uefiscdi-anexa7c-publishers-2026.csv`)
- SENSE: the `senseRankings` collection (`data/sense/SENSE-rankings.xlsx`, admin initialization page); the
  index `international-publisher-lists.csv` names every international list and the levels that count

## What the regulations say about which list, and the readings chosen

Adrian (review round): "It's not like it's my choice. Don't the regulations provide wording on which to choose? If not
clear, then your choice is good and must be stated in the indicator/scorer description that the researcher sees."

- **Comisia 35 (Muzică), OM 3.019/2025:** «publicat» = "publicarea în edituri clasificate de CNSC în categoria A sau B
  sau edituri echivalente din străinătate" — no list year, no domain. Not clear, so the platform's reading stands and
  is written into the descriptions of DID 1.1 and N_carti: the best category in the Music domain of the 2020 and 2026
  lists, else the best in any domain of the 2013, 2020 and 2026 lists. For comparison only: Comisia 29 (Filologie)
  calls the CNCS lists indicative and leaves the classification to the committee, "în corelație cu evoluția
  domeniului și data publicării contribuției" — a time rule Comisia 35 does not have.
- **Comisia 25 (Sociologie):** the A2 list is the annex's own; A1 is "Lista A1, în vigoare", not reproduced (the
  international lists stand in for it, said in the descriptions); and "Cărțile publicate anterior datei intrării în
  vigoare a prezentei liste și care se aflau pe lista de edituri din Anexa 2" count too. That Anexa 2 is not in
  OM 3.019/2025, whose Anexa 2 is the professor standards: the sentence comes from the earlier standard (OMENCS
  6.129/2016), whose list the platform does not hold. Reading: such a book is a request a head approves (option
  "Editură de pe lista anterioară (Anexa 2), carte apărută înaintea listei actuale", A2), said in the descriptions.
  Loading the 2016 list would let the engine decide it instead.
- **Comisia 28:** one list, the 2026 annex's, no time rule. A1 has no list ("edituri de prestigiu internațional"): the
  international lists stand in for it, said in the descriptions.
- **Sociology I.8 translations** count at an A2 publisher (or six WorldCat libraries, or the earlier list), not at an
  A1 house, as the annex says "lista A2" — said in the description.

## Prod

`rke2-overmind/feaa-2026-scripts/h143_publisher_categories.js` (guard `H143_IMAGE_IS_DEPLOYED`, run with
`--restart`): 7 activity types (the picked category removed, the request fields added, `Editura` on Comisia 28 I17,
Sociology's earlier-list option), 14 indicators (formulas on `Categorie_editura`, their formula hashes, the commission
flags) and 21 descriptions (the four reports' book indicators: how the publisher is classified, which lists, what a head
is asked). Generated from the seed before H143 and the committed descriptions; rehearsed on a scratch database with
prod-like ids: the guarded run writes nothing, the first run updates everything, the second changes nothing, the
result matches the local app by name (descriptions included), nothing else is touched. Prod read before the push: all
7 types and 21 indicators exist once, still no record of the affected types, SENSE loaded (14 A, 55 B, 181 C, 388 D,
160 E — as locally).

Corpus books change too, upward only: across the whole prod corpus (374,251 book records, mostly cited works) 77
publishers newly count as international — University of Toronto Press, Berghahn, Brookings, Lawrence Erlbaum, Frank
Cass, Olms, Metzler, … — so Psychology, Educational Sciences and Sociology reports can rise on their next run. Two
known stretches of token matching, both rare: "The Feminist Press at the City University of New York" reads as New
York University Press, "Presses universitaires de Limoges, France" as Presses Universitaires de France.

## Verified

Review round: full suite and the CI guardrails green; the international lists checked against every list held (no
listed foreign house reads as Romanian, except Economica by design; no Romanian list name passes as foreign).
First round: full suite 3,797 tests, none failing; guardrails pass. Locally: a book at "Editura Polirom, Iași" shows A2 under
both Comisia 28 domains; one at "Editura MediaMusica, Cluj-Napoca" A (CNCS 2026, Music) right after it is created;
a request goes PENDING on save and shows so; a head approves another researcher's request on the page, which
records it and offers to take it back; the researcher's own request is not listed to them. Test records removed.
