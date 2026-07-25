#!/usr/bin/env bash
#
# Null out the WoS ±999 "no value" sentinels that were ingested as real metrics.
#
# WHY: AbstractWosImportEventParser rejected only the NEGATIVE sentinel, so journalImpactFactor=999.999
# rode in as a genuine Impact Factor. The 1998 extracts carry it for 56 journals (50 SCIE + 6 SSCI) —
# an identical three-decimal value shared across a whole year is not data. Unlike a non-finite value,
# nothing downstream clamps it, so "Journal Of Sociology" and 55 others scored on an IF of 999.999.
#
# The parser guard is fixed going forward; this repairs the facts already stored. It sets value to NULL
# rather than deleting the row, which is exactly what a re-ingest now produces (the record is still
# emitted, with no value) — so a later re-ingest converges instead of conflicting.
#
# PHASE B exists because nulling the value is NOT sufficient. The quartile is stored SEPARATELY, on
# wos.category_facts, and was computed from the sentinel before it was cleared. WosFactBuilderService
# puts a value-less fact into its `uncomputable` bucket ("missing-metric-value") and never recomputes it,
# so a stale "Q1" survives forever — and Q1 membership is what mv_wos_top_rankings_q1_if counts to derive
# the top-20%-of-Q1 A*/A boundary. Phase B clears the quartile wherever the metric it was derived from no
# longer has a value. Verified against prod: value-less IF facts in every other year correctly carry NO
# quartile (0 of a 400 sample), so this invariant repair touches only the sentinel rows.
#
#   Usage:  ./scripts/ops/clear-wos-metric-sentinels.sh
#
# AFTER RUNNING: Postgres still holds the old values. The script prints the exact rebuild sequence and
# the query to confirm the Q1 cohort actually moved.

set -euo pipefail

KUBECONFIG_PATH="${KUBECONFIG:-$HOME/Documents/Development/rke2-overmind/prod.kubeconfig}"
NS=scholardex
PORT=27019

export KUBECONFIG="$KUBECONFIG_PATH"
command -v mongosh >/dev/null || { echo "mongosh not found" >&2; exit 1; }

PW=$(kubectl -n "$NS" get secret scholardex-db -o jsonpath='{.data.MONGO_PASSWORD}' | base64 -d)
kubectl -n "$NS" port-forward svc/scholardex-mongo "$PORT:27017" >/dev/null 2>&1 &
PF=$!
trap 'kill $PF 2>/dev/null || true' EXIT
sleep 4

URI="mongodb://scholardex:$PW@localhost:$PORT/scholardex?authSource=admin"

echo "=== sentinel facts currently stored ==="
mongosh "$URI" --quiet --eval '
const M = db.getCollection("wos.metric_facts");
const q = { $or: [ { value: { $gte: 999.0 } }, { value: { $lte: -999.0 } } ] };
print("matching facts: " + M.countDocuments(q));
M.aggregate([{ $match: q },
  { $group: { _id: { m:"$metricType", y:"$year", f:"$sourceFile", v:"$value" }, n:{$sum:1} } },
  { $sort: { "_id.y": 1 } }
]).forEach(r => print("   " + r._id.m + "  " + r._id.y + "  value=" + r._id.v
    + "  rows=" + String(r.n).padStart(4) + "   " + r._id.f));
'

printf '\nnull out these values in production? [y/N] '
read -r confirm < /dev/tty
[ "$confirm" = "y" ] || { echo "aborted, nothing changed"; exit 1; }

mongosh "$URI" --quiet --eval '
const M = db.getCollection("wos.metric_facts");
const C = db.getCollection("wos.category_facts");
const A = db.getCollection("scholardex.app_migrations");
const q = { $or: [ { value: { $gte: 999.0 } }, { value: { $lte: -999.0 } } ] };

// ---- Phase A: null the sentinel VALUES ------------------------------------------------------------
const before = M.find(q, { journalId:1, year:1, metricType:1, value:1, sourceFile:1 }).toArray();
if (before.length > 0) {
  // Only ever write the audit when there is something to record. An earlier version used an
  // unconditional upsert, so a second run overwrote the restore data with an empty list.
  A.replaceOne({ _id: "clear-wos-metric-sentinels-v1" },
    { _id: "clear-wos-metric-sentinels-v1", appliedAt: new Date().toISOString(),
      reason: "WoS +/-999 no-value sentinel ingested as a real metric (parser guarded only the negative side)",
      clearedCount: before.length, cleared: before },
    { upsert: true });
  const r = M.updateMany(q, { $set: { value: null } });
  print("phase A — values nulled: " + r.modifiedCount + "   (restorable from app_migrations)");
} else {
  print("phase A — no sentinel values left (already repaired); audit record left untouched");
}

// ---- Phase B: clear quartiles stranded on now-value-less metrics ----------------------------------
// A category fact keeps quarter/quartileRank/rank computed from a value that is now gone. The builder
// will not recompute it (value-less facts are "uncomputable"), so it must be cleared explicitly.
// Batched by (year, metricType) — ~90 groups rather than one query per value-less fact (10k+).
const groups = new Map();
M.find({ value: null }, { journalId:1, year:1, metricType:1 }).forEach(f => {
  const k = f.year + "|" + f.metricType;
  if (!groups.has(k)) groups.set(k, []);
  groups.get(k).push(f.journalId);
});
let stranded = 0, cleared = 0;
groups.forEach((journalIds, k) => {
  const [year, metricType] = k.split("|");
  const sel = { journalId: { $in: journalIds }, year: parseInt(year, 10), metricType: metricType,
                quarter: { $ne: null } };
  const n = C.countDocuments(sel);
  if (n === 0) return;
  stranded += n;
  cleared += C.updateMany(sel, { $unset: { quarter: "", quartileRank: "", rank: "" } }).modifiedCount;
  print("   " + metricType + " " + year + ": cleared " + n);
});
print("phase B — category facts with a quartile but no metric value: " + stranded + ", cleared: " + cleared);
'

echo
echo "Mongo repaired. Postgres still serves the old values until rebuilt. Run, in this order:"
echo "  1. Initialization -> \"4. Build projections\" (WoS)        rebuilds wos_scoring_view"
echo "  2. Initialization -> \"Refresh all materialized views\"     recomputes the Q1 cohort / A*-A boundary"
echo "  3. Initialization -> \"3. Build projections\" (Scopus)      rebuilds scholardex_forum_metric_view"
echo
echo "Then confirm the cohort actually moved:"
echo "  SELECT count(*) FROM reporting_read.wos_scoring_view"
echo "   WHERE metric_type='IF' AND year=1998 AND quarter='Q1';   -- expect 2719, was 2808"
