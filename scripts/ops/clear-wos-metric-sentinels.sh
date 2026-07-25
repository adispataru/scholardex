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
#   Usage:  ./scripts/ops/clear-wos-metric-sentinels.sh
#
# AFTER RUNNING: the Postgres projection still holds the old values. Rebuild it —
# the Conflicts page rebuild, or POST /admin/.../rebuild — then refresh any affected report runs.

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
const q = { $or: [ { value: { $gte: 999.0 } }, { value: { $lte: -999.0 } } ] };
// Keep a restorable record of exactly what was changed.
const before = M.find(q, { journalId:1, year:1, metricType:1, value:1, sourceFile:1 }).toArray();
db.getCollection("scholardex.app_migrations").replaceOne(
  { _id: "clear-wos-metric-sentinels-v1" },
  { _id: "clear-wos-metric-sentinels-v1", appliedAt: new Date().toISOString(),
    reason: "WoS +/-999 no-value sentinel ingested as a real metric (parser guarded only the negative side)",
    clearedCount: before.length, cleared: before },
  { upsert: true });
const r = M.updateMany(q, { $set: { value: null } });
print("nulled: " + r.modifiedCount + "   (restorable from app_migrations/clear-wos-metric-sentinels-v1)");
print("remaining sentinel facts: " + M.countDocuments(q));
'

echo
echo "Mongo repaired. The Postgres projection still serves the old values until it is rebuilt:"
echo "  reporting_read.scholardex_forum_metric_view"
echo "Rebuild the projections, then refresh any report run that scored a 1998 journal."
