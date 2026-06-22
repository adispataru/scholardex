# H75 — V1 rules catalog (the spec V2 must port verbatim)

Stage-0 artifact. Every identity / dedup / normalization rule the current (V1) derivation applies, with thresholds
and `file:line`, so the V2 batch engine ports them **exactly** (and the differential harness can assert parity).
Source of truth is the code; this is the index. Re-derive from code before trusting any line below.

## Output contract — what V2 must produce (the wiped, derived collections)

`MANAGED_DERIVED_COLLECTIONS` in `PipelineRebuildService` (lines ~33–59) is the full wipe set. The **canonical
(stage-3) subset V2 owns**:

| Collection | Model | Natural key (unique index) |
|---|---|---|
| `scholardex.publication_facts` | `ScholardexPublicationFact` | `eid` / `wosId` / `googleScholarId` / `userSourceId` (each unique+sparse); `@Id` = `spub_<hash>` |
| `scholardex.author_facts` | `ScholardexAuthorFact` | `scopusAuthorIds[]` (unique, partialFilter `$type:string`); `@Id` = `sauth_<hash>` |
| `scholardex.affiliation_facts` | `ScholardexAffiliationFact` | `scopusAffiliationIds[]` (unique partialFilter); `@Id` = `saff_<hash>` |
| `scholardex.forum_facts` | `ScholardexForumFact` | `scopusForumIds[]`/`wosForumIds[]`/`openAlexIds[]`/`dblpIds[]` (each unique partialFilter); `@Id` = `sforum_<hash>` |
| `scholardex.authorship_facts` | `ScholardexAuthorshipFact` | `{publicationId, authorId, source}` |
| `scholardex.author_affiliation_facts` | `ScholardexAuthorAffiliationFact` | `{authorId, affiliationId, source}` |
| `scholardex.publication_author_affiliation_facts` | `ScholardexPublicationAuthorAffiliationFact` | `{publicationId, authorId, affiliationId, source}` |
| `scholardex.citation_facts` | `ScholardexCitationFact` | `{citedPublicationId, citingPublicationId}` |
| `scholardex.source_links` | `ScholardexSourceLink` | `{entityType, source, sourceRecordId}` → `canonicalEntityId`, `linkState`, `linkReason` |
| `scholardex.identity_conflicts` | `ScholardexIdentityConflict` | `{entityType, incomingSource, incomingSourceRecordId, reasonCode, status}` |

**Persist (NOT derived, NOT wiped):** `scholardex.book_facts`, `doaj.journal_facts`, `erih.journal_facts`,
`wos.journal_identity` is wiped+rebuilt from WoS ingest (out of V2 scope — an input). V2 reads source facts
(`scopus.*`, `openalex.*`, WoS identity, DOAJ/ERIH/DBLP) and writes the 10 collections above.

## Build order (V2 in-memory, dependency-first — absorbs H74)

`forums → affiliations → publications → authors (+ inversion) → edges → citations`. The author **reconcile** folds
INTO the author build (one union-find pass), not a separate post-step. Live V1 order for reference (post-S2.2):
stage-2 facts → forums → OpenAlex canon → Scopus affiliation → author → publication canon → WoS-link →
OpenAlex-citations → DBLP → Scopus-citations → projections → index-ensure → **post-rebuild reconcile**.

## Normalization primitives (reuse `CanonicalizationSupport` verbatim — do NOT reimplement)

- `shortHash(s)` = first **24** hex chars of SHA-256. All canonical ids = `<prefix>_ + shortHash(material)`.
- `normalizeToken(s)` = trim + lowercase(ROOT), "" if null.
- `normalizeName(s)` = lowercase → NFKD → strip `\p{M}+` → `[^\p{Alnum}\s]`→space → collapse spaces → trim; null if empty. Result `[a-z0-9 ]+`.
- `normalizeDoi(s)` = strip `^https?://(dx\.)?doi\.org/` + `^doi:` (CI) → trim → lowercase; null if empty.
- `normalizeTitle(s)` = like normalizeName (lowercase/NFKD/strip/space/trim).
- `normalizeIssn(s)` = strip non-alnum, upper, require 8 chars + valid check digit, format `XXXX-XXXX`.
- Surname: Scopus `"Last, First"` → text before comma, else last whitespace token; OpenAlex `"First Last"` → last token. `normalizeSurname` = NFKD → strip marks → lowercase → `[^a-z0-9]`-strip.

## Identity rules (id material + merge predicates + thresholds)

### Publications (`ScholardexPublicationCanonicalizationService`)
- **Canonical id material precedence:** `doi|<doi>` (if DOI present AND not blocklisted) → `eid|` → `wos|` → `user|` → `title|<t>|date|<d>|creator|<c>|forum|<f>`. `@Id = spub_+shortHash`.
- **H66B Decision-0 shared-DOI blocklist:** group source facts by normalized DOI; if a DOI has ≥2 records AND they form **>1 title-cluster**, blocklist that DOI (those records do NOT merge on DOI). Title-cluster = single-link clustering with **token-Jaccard ≥ 0.5**; **year is deliberately NOT compared**. Conflict `PUBLICATION_SHARED_DOI`.
- **OpenAlex field-precedence (S2.2, just shipped):** if an existing canonical pub is `source==OPENALEX`, Scopus **enriches only** (eid + Scopus-only fields; citation count monotonic-max) and does NOT overwrite OpenAlex title/venue/creator/coverDate/owning-source. Otherwise Scopus applies the full field set.
- Forum/affiliation on the pub resolve via source-link (already-`sforum_`/`saff_` pass through).

### Affiliations (`ScholardexAffiliationCanonicalizationService` + `ScopusAffiliationRorMatcher`)
- **Canonical id material:** `scopus|<afid>` (verified afid) else `name|<n>|city|<c>|country|<co>`. ROR backbone id = `saff_+shortHash("ror|"+normalizeToken(ror))` — **same namespace**, so an afid matching a ROR plugs into the backbone record.
- **Verified vs ad-hoc afid:** verified = `startsWith("60")` (8-digit profile); ad-hoc = `1xxxxxxxx` (9-digit). `scopus.affiliation.verified-only=true` (default) → **drop ad-hoc afids** entirely.
- **3-tier ROR match** (Scopus afid → OpenAlex backbone): (1) exact normalized-name vs backbone display-name + `display_name_alternatives` aliases (first-writer-wins index); (2) simplification — strip `\([^)]*\)` parentheticals + a leading faculty/dept/institute clause regex, retry; (3) country-gated **token-Jaccard ≥ 0.8** within same `country` only, city-match tiebreak. Stopwords removed: `of,the,and,for,de,la,din,si`. Refuses fuzzy when country blank.
- **Backbone enrich-only:** existing `source==OPENALEX` record keeps OpenAlex name/city/country; Scopus name added as alias + afid added to `scopusAffiliationIds`.

### Forums (`ForumMergeEngine` + `ScholardexForumBuilder`)
- **Canonical id material:** `issn|<sorted ISSNs>` if any ISSN, else `nameAgg|<name>|<aggregationType>`. `@Id = sforum_+shortHash`.
- **Create-or-match by ISSN tokens;** H55.6 **primary-ISSN tiebreak** when >1 candidate; H57 **cross-journal token hygiene** (drop a secondary ISSN that is another journal's primary) + H66B safe-merge guard (`FORUM_CROSS_JOURNAL_ISSN` conflict → mint separate).
- **Id-types:** SCOPUS/WOS/ERIH/DOAJ/OPENALEX/DBLP. **Onboarding order:** Scopus-fold → ERIH → DOAJ → WoS-last (identity-of-last-resort). **OpenAlex single-tag** (unique `openAlexIds` → one venue ↔ one forum, idempotent else lowest-id); ERIH/DOAJ fan-out (non-unique FKs).
- **Dedup** (`deduplicateForums`) runs pre-build, after each create-or-match source, and after membership changes.

### Authors — the hard part (union-find over identity signals)
Seed one node per source author. Canonical id material: Scopus `scopus|<auid>`; OpenAlex `orcid|<orcid>` (preferred) else `openalex|<id>`; name-only refs are NOT id-resolvable (return null). `@Id = sauth_+shortHash`.

**Union edges (what merges two nodes into one canonical author):**
- **Same ORCID** (reconcile ORCID pass): cluster by ORCID; merge **only if all pairwise names compatible** (same normalized surname; given names equal or one is the other's leading initial; blanks compatible), else quarantine `AUTHOR_SHARED_ORCID_NAME_MISMATCH`.
- **Positional bridge** (per shared DOI paper, S2.2): equal author count **and** per-position `surnameMatches`; seeds ORCID/openalex-id onto the Scopus author → unifies. Used both as the inversion source-link writer and the ORCID seeder.
- **Fuzzy name pass** (`reconcileByName`): cluster by **order-insensitive sorted-token name key**; per pair — **hard block if they co-appear on any publication** (different people); else shared-coauthor count (excluding cluster members): **≥ `coauthor-strong-threshold` (default 3) → union**; 1–2 → quarantine `AUTHOR_FUZZY_MERGE_REVIEW`; 0 → drop. Clusters > `max-cluster-size` (default 50) → review. **Gated by `core.author-reconcile.fuzzy-apply` (DEFAULT false → quarantine, NOT merge).**
- **Over-split pass** (`reconcileByNameAndAffiliation`): same-name cluster; pair unions if **affiliations intersect AND ≥1 shared co-author**, with **mega-author guard** (papers with > `coauthor-max-paper-authors`, default 20, contribute no co-author signal); auto-merge only if **distinct display names OR ≥2 multi-letter name tokens** (blocks "Wang, J."×N), else quarantine. **Gated by `core.author-reconcile.affiliation-apply` (DEFAULT false).**
- **Tie-break / winner** on merge: most `scopusAuthorIds` → most total id-keys → lexicographically smallest `@Id`. Loser's authorship/author-affiliation/pub-author-affiliation edges + `pub.authorIds[]` re-point to winner (collisions dedup; `corresponding` OR-merged).
- **`preferEstablished`:** when an ORCID/openalex-id key resolves to several authors, the Scopus-established one (non-empty `scopusAuthorIds`) wins.

## Config that changes output (pinned 2026-06-22)

1. **RESOLVED — both apply flags are ON in the live config:** `core.author-reconcile.fuzzy-apply=true`
   (application.properties:60) and `core.author-reconcile.affiliation-apply=true` (application.properties:63). So a
   real rebuild runs **all three** author merges (ORCID + fuzzy + over-split), *applying* them, not just
   quarantining. V2 must reproduce all three in its union-find. The differential harness runs V1 with these same
   flags (they're the property defaults in the test context unless overridden).
2. `coauthor-strong-threshold=3`, `max-cluster-size=50`, `coauthor-max-paper-authors=20`, `verified-only=true`
   (the @Value fallbacks; not overridden in application.properties). V2 reads the same properties.
3. Citation edges come from **two** builders (OpenAlex DOI-keyed + Scopus eid-keyed) into the same `citation_facts` — V2 must merge both by `{cited,citing}`.
4. DBLP conference forums are re-minted from durable **evidence** (no API) — V2 must reproduce that path or read its evidence.

## Baseline profile to beat (from `/tmp/s23_bootrun3.log`, run #3, partial)

wipe ~instant · **WoS ~9 min** (fact-build + projection) · DOAJ/ERIH ~1 min · **OpenAlex bulk import ~4 min** ·
**forum build ~8 min** · **OpenAlex canon ≥25–30 min (did not finish; the long pole)** · Scopus canon +
reconcile + projections never reached before the overnight sleep. V2 derive target: **< 10 min** total for the
canonical layer (WoS ingest + projections are separate, out of V2 scope).
