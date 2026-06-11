// H54.7 derived-collection snapshot for at-scale rebuild verification.
//
//   mongosh "mongodb://localhost:27017/<db>" scripts/h54-derived-collection-snapshot.js
//
// Prints per-collection document counts for the derived (rebuildable) collections this app owns.
// Capture this BEFORE a wipe+rebuild and AFTER, and diff: counts must match for a deterministic
// rebuild. (Content-level determinism is proven in CI by PipelineRebuildDeterminismIntegrationTest;
// at full scale, count parity per collection is the practical operator check.)
//
// Read-only. Only lists OWNED derived collections — never touches foreign collections
// (planuri.*, skills, exam system, etc.). See docs/data-ownership-inventory.md.

const DERIVED_COLLECTIONS = [
  "scopus.import_events",
  "scopus.publication_facts",
  "scopus.author_facts",
  "scopus.affiliation_facts",
  "scopus.citation_facts",
  "scopus.forum_facts",
  "scopus.funding_facts",
  "wos.import_events",
  "wos.category_facts",
  "wos.metric_facts",
  "wos.journal_identity",
  "wos.fact_conflicts",
  "wos.identity_conflicts",
  "user_defined.publication_facts",
  "user_defined.forum_facts",
  "scholardex.publication_facts",
  "scholardex.author_facts",
  "scholardex.affiliation_facts",
  "scholardex.citation_facts",
  "scholardex.forum_facts",
  "scholardex.authorship_facts",
  "scholardex.author_affiliation_facts",
  "scholardex.publication_author_affiliation_facts",
  "scholardex.source_links",
  "scholardex.identity_conflicts",
  "scholardex.publication_link_conflicts",
];

print("==== H54.7 derived-collection snapshot ====");
print("Database: " + db.getName() + "  at " + new Date().toISOString());
print("------------------------------------------");
const existing = new Set(db.getCollectionNames());
let total = 0;
for (const c of DERIVED_COLLECTIONS) {
  const count = existing.has(c) ? db.getCollection(c).countDocuments({}) : 0;
  total += count;
  print(("           " + count).slice(-12) + "  " + c + (existing.has(c) ? "" : "  (absent)"));
}
print("------------------------------------------");
print(("           " + total).slice(-12) + "  TOTAL");
