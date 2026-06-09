// H54.1 precious-layer snapshot.
//
// Run from the repo root with mongosh:
//   mongosh "mongodb://localhost:27017/test" scripts/h54-1-snapshot-precious.js
//
// Splits the precious (human/operationally authored, non-reconstructable) layer into:
//   - CONFIG (no PII): written deterministically (sorted by _id, pretty JSON) to
//     seed/precious-config/<collection>.json  -> git-tracked, meaningful diffs.
//   - PERSONAL (PII): written to data/backups/precious-pii-<timestamp>/<collection>.jsonl
//     -> git-ignored (data/* is ignored), one EJSON doc per line.
//
// Read-only against Mongo. Only this app's OWNED collections are touched
// (see docs/data-ownership-inventory.md). Re-runnable.

const fs = require("fs");

// Config: definitions / catalog / org structure. No person-identifying records.
const CONFIG_GIT = [
  "indicators",
  "individualReports",
  "groupReports",
  "scholardex.groups",
  "institutions",
  "domains",
  "activities",
  "scholardex.artisticEvent",
  "scholardex.departments",
  "scholardex.org_divisions",
  "scholardex.division_report_selections",
  "scholardex.department_report_hides",
];

// Personal: keyed to / naming a specific person. Never committed.
const PII_LOCAL = [
  "scholardex.users",
  "scholardex.publication_authorship_decisions",
  "activityInstances",
  "userIndividualReportRuns",
  "groupIndividualReportRuns",
  "scholardex.workspacePreferences",
  "scholardex.memberships",
  "scholardex.department_affiliations",
];

function ts() {
  // YYYYMMDD-HHMMSS
  const d = new Date().toISOString().replace(/\.\d+Z$/, "").replace(/[:T-]/g, (m) => (m === "T" ? "-" : ""));
  return d; // e.g. 20260609-115355
}

function ensureDir(p) {
  fs.mkdirSync(p, { recursive: true });
}

function idKey(doc) {
  // stable sort key: stringify _id (handles ObjectId, string, etc.)
  try { return EJSON.stringify(doc._id); } catch (e) { return String(doc._id); }
}

// ---- CONFIG: deterministic, git-tracked ----
const configDir = "seed/precious-config";
ensureDir(configDir);
let configTotal = 0;
const configSummary = [];
for (const coll of CONFIG_GIT) {
  const exists = db.getCollectionInfos({ name: coll }).length > 0;
  const path = configDir + "/" + coll + ".json";
  if (!exists) {
    fs.writeFileSync(path, "[]\n");
    configSummary.push(coll + ": (absent) -> []");
    continue;
  }
  const docs = db.getCollection(coll).find({}).toArray();
  docs.sort((a, b) => (idKey(a) < idKey(b) ? -1 : idKey(a) > idKey(b) ? 1 : 0));
  const body = "[\n" + docs.map(d => "  " + EJSON.stringify(d)).join(",\n") + "\n]\n";
  fs.writeFileSync(path, docs.length ? body : "[]\n");
  configTotal += docs.length;
  configSummary.push(coll + ": " + docs.length);
}

// ---- PII: timestamped, git-ignored ----
const piiDir = "data/backups/precious-pii-" + ts();
ensureDir(piiDir);
let piiTotal = 0;
const piiSummary = [];
for (const coll of PII_LOCAL) {
  const exists = db.getCollectionInfos({ name: coll }).length > 0;
  const path = piiDir + "/" + coll + ".jsonl";
  if (!exists) {
    fs.writeFileSync(path, "");
    piiSummary.push(coll + ": (absent)");
    continue;
  }
  const cursor = db.getCollection(coll).find({});
  const stream = fs.createWriteStream(path, { flags: "w" });
  let n = 0;
  while (cursor.hasNext()) { stream.write(EJSON.stringify(cursor.next()) + "\n"); n++; }
  stream.end();
  piiTotal += n;
  piiSummary.push(coll + ": " + n);
}

print("\n========== H54.1 precious-layer snapshot ==========");
print("Database: " + db.getName());
print("\n-- CONFIG (git-tracked) -> " + configDir + " --");
configSummary.forEach(s => print("  " + s));
print("  config docs total: " + configTotal);
print("\n-- PERSONAL/PII (git-ignored) -> " + piiDir + " --");
piiSummary.forEach(s => print("  " + s));
print("  pii docs total: " + piiTotal);
print("\nDone. Review `git status seed/` for the committable config snapshot.");
print("===================================================");
