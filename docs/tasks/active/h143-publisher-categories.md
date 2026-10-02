# H143 — the category of a declared book's publisher comes from the lists, not from the researcher

Status: **BUILT 2026-10-02 (not pushed).** Asked by Adrian the same day ("I want the real fix"), after the H142
slice 2 review showed that a researcher picked their own book's publisher category, and that the record panel
stored the top category by default. Prod checked read-only first: no record of any affected type exists, so no
score was ever inflated.

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
- **Comisia 25 (Sociologie):** the annex's A2 list, else A1 for a house on the WoS Master Book List — exactly as for
  corpus books.
- **Comisia 28 (Psihologie, Științe ale educației):** the domain's 2026 list (A2, B), else A1 for a house on the WoS
  Master Book List (indicative, as for corpus books).
- **Comisia 35 (Muzică):** "publicat" = a publisher CNCS classifies A or B, or an equivalent foreign one:
  - the CNCS classifications of publishers in the humanities and arts, 2026 (OM 5.100/26.08.2026, 168 rows),
    2020 (155) and 2013 (performing arts, 23): the best category in the **Music domain of 2020 and 2026** when the
    publisher has one there (Universitaria, rated C in Music twice, does not count), else its best category in any
    domain of the three lists (Editura Universității de Vest: A in Filologie 2026);
  - foreign: the UEFISCDI list of publishers of international prestige in the arts and humanities (349, with
    Bärenreiter, Casa Ricordi, Universal Edition; **Lambert Academic Publishing excluded**, as UEFISCDI excludes it),
    else the WoS Master Book List.
- **Names** are matched on their distinctive words (no diacritics, no "Editura"/"SC"/"SRL", no linking words): equal,
  the listed name inside the typed one, or a typed name of two or more words inside the listed one. A few short
  forms are aliases (`report-data/publisher-aliases.csv`: UVT, UNMB, PUC, UNATC, UNArte, Grafoart, Ricordi).
- **Where no list decides**, the record may ask for a category its standard grants one book
  (`Incadrare_solicitata` + `Dovada_incadrarii`): Sociology A1 (international house off the Master Book List) or
  six WorldCat libraries (as A2); Comisia 28 A1 by 25 EU/OECD university libraries in WorldCat, A2 or B by the
  complementary route, A1 for a collection; Music an equivalent foreign publisher. The request is PENDING until a
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

## Readings chosen (open to Adrian)

1. **Which CNCS list:** the best category across 2013, 2020 and 2026, Music domain first. The alternative is the list
   in force when the book appeared.
2. **Another domain's rating counts** for a publisher the Music lists do not rate (Editura UVT). The strict reading
   would ask for a Music rating only.
3. **Sociology I.8 translations** count at an A2 publisher (or six WorldCat libraries), not at an A1 house, as the
   annex says "lista A2".

## Prod

`rke2-overmind/feaa-2026-scripts/h143_publisher_categories.js` (guard `H143_IMAGE_IS_DEPLOYED`, run with
`--restart`): 7 activity types (the picked category removed, the request fields added, `Editura` on Comisia 28 I17)
and 14 indicators (formulas on `Categorie_editura`, their formula hashes, the commission flags). Generated from the
seed; rehearsed on a scratch database with prod-like ids: the guarded run writes nothing, the first run updates
everything, the second changes nothing, the result matches the local app by name, nothing else is touched.
A new `SeedFormulaHashTest` keeps every committed formula's hash in step with it: a mongosh-written formula without
its hash would let cached results of the old formula be served.

## Verified

Full suite 3,797 tests, none failing; guardrails pass. Locally: a book at "Editura Polirom, Iași" shows A2 under
both Comisia 28 domains; one at "Editura MediaMusica, Cluj-Napoca" A (CNCS 2026, Music) right after it is created;
a request goes PENDING on save and shows so; a head approves another researcher's request on the page, which
records it and offers to take it back; the researcher's own request is not listed to them. Test records removed.
