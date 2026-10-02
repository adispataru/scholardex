#!/usr/bin/env bash
#
# Reload the CONFIG collections of a LOCAL database from seed/precious-config (drop + insert, per collection).
#
# WHY: after scripts/ops/snapshot-prod-config.sh the seed mirrors prod's definitions, org structure and report
# selections; a developer's local database should look the same so that advice about divisions and reports
# is given against the real structure. Personal-data collections are not touched (local users, entries and
# runs stay); runs that point at a report id the seed no longer has simply stop resolving.
#
#   Usage:  ./scripts/ops/load-config-seed-local.sh [mongodb://localhost:27017/scholardex]
#
# Refuses any URI that is not localhost — this drops collections.

set -euo pipefail

URI="${1:-mongodb://localhost:27017/scholardex}"
case "$URI" in
  mongodb://localhost*|mongodb://127.0.0.1*) ;;
  *) echo "refusing to load into a non-local database: $URI" >&2; exit 1 ;;
esac
[ -d seed/precious-config ] || { echo "run from the repository root" >&2; exit 1; }
command -v mongosh >/dev/null || { echo "mongosh not found" >&2; exit 1; }

mongosh "$URI" --quiet --eval '
const fs = require("fs");
const COLLECTIONS = ["indicators", "individualReports", "groupReports", "scholardex.groups", "institutions", "domains",
  "activities", "scholardex.artisticEvent", "scholardex.departments", "scholardex.org_divisions",
  "scholardex.division_report_selections", "scholardex.department_report_hides"];
print("database: " + db.getName());
COLLECTIONS.forEach(function (coll) {
  const path = "seed/precious-config/" + coll + ".json";
  if (!fs.existsSync(path)) { print("  " + coll + ": no seed file, left as it is"); return; }
  const docs = EJSON.parse(fs.readFileSync(path, "utf8"));
  const before = db.getCollection(coll).countDocuments();
  db.getCollection(coll).drop();
  if (docs.length) db.getCollection(coll).insertMany(docs);
  print("  " + coll + ": " + before + " -> " + docs.length);
});
'
echo "done — restart the local app (indicator docs are cached in memory)."
