#!/usr/bin/env bash
#
# H129 — give the declared activity type "Brevet" the three fields the CNFIS sheet asks for, in the
# PRODUCTION database: "Cod brevet", "Oficiu", "N_autori_universitate". Anexa 5 reports a patent with its
# code, the issuing office and the number of authors from the university; the type had only the number of
# authors, the kind (Triadic / European / International / National) and the evidence.
#
# Idempotent: a field that is already there is not added twice. Instances declared before keep working —
# the new fields simply read as empty until the person fills them in.
#
#   Usage:  ./scripts/ops/add-brevet-fields.sh

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

echo "=== before ==="
mongosh "$URI" --quiet --eval '
const A = db.getCollection("activities");
const a = A.findOne({ name: "Brevet" });
print(a ? "Brevet fields: " + a.fields.map(f => f.name).join(", ") : "activity type Brevet NOT FOUND");
'

printf '\nadd the fields "Cod brevet", "Oficiu", "N_autori_universitate" to Brevet, in production? [y/N] '
read -r confirm < /dev/tty
[ "$confirm" = "y" ] || { echo "aborted, nothing changed"; exit 1; }

mongosh "$URI" --quiet --eval '
const A = db.getCollection("activities");
const a = A.findOne({ name: "Brevet" });
if (!a) { print("activity type Brevet not found — nothing changed"); quit(1); }
const wanted = [
  { name: "Cod brevet", number: false },
  { name: "Oficiu", number: false },
  { name: "N_autori_universitate", number: true }
];
const present = new Set(a.fields.map(f => f.name));
const missing = wanted.filter(f => !present.has(f.name));
if (missing.length === 0) { print("all three fields already present — nothing changed"); quit(0); }
A.updateOne({ _id: a._id }, { $push: { fields: { $each: missing } } });
print("added: " + missing.map(f => f.name).join(", "));
'

echo
echo "=== after ==="
mongosh "$URI" --quiet --eval '
const a = db.getCollection("activities").findOne({ name: "Brevet" });
print("Brevet fields: " + a.fields.map(f => f.name).join(", "));
'
