#!/usr/bin/env bash
#
# H129 — give the declared activity types the fields the CNFIS sheets ask for, in the PRODUCTION database:
#   "Brevet"                         + "Cod brevet", "Oficiu", "N_autori_universitate"        (Anexa 5)
#   "Participare eveniment artistic" + "Tip" (the kind of the work), "N_participanti_universitate" (Anexa 5.1)
#
# Idempotent: a field that is already there is not added twice. Instances declared before keep working —
# the new fields read as empty until the person fills them in (a performance without "Tip" is left out of
# Anexa 5.1 and says so).
#
#   Usage:  ./scripts/ops/add-cnfis-activity-fields.sh

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

show() {
  mongosh "$URI" --quiet --eval '
const A = db.getCollection("activities");
["Brevet", "Participare eveniment artistic"].forEach(name => {
  const a = A.findOne({ name: name });
  print(a ? "   " + name + ": " + a.fields.map(f => f.name).join(", ") : "   " + name + ": NOT FOUND");
});
'
}

echo "=== before ==="
show

printf '\nadd the CNFIS fields to the two activity types, in production? [y/N] '
read -r confirm < /dev/tty
[ "$confirm" = "y" ] || { echo "aborted, nothing changed"; exit 1; }

mongosh "$URI" --quiet --eval '
const A = db.getCollection("activities");
const wanted = {
  "Brevet": [
    { name: "Cod brevet", number: false },
    { name: "Oficiu", number: false },
    { name: "N_autori_universitate", number: true }
  ],
  "Participare eveniment artistic": [
    { name: "Tip", allowedValues: ["Proiect individual", "Proiect de grup (2-4)", "Proiect colectiv (5+)",
                                   "Nominalizare individuală", "Premiu individual"], number: false },
    { name: "N_participanti_universitate", number: true }
  ]
};
Object.keys(wanted).forEach(name => {
  const a = A.findOne({ name: name });
  if (!a) { print("   " + name + ": not found — skipped"); return; }
  const present = new Set(a.fields.map(f => f.name));
  const missing = wanted[name].filter(f => !present.has(f.name));
  if (missing.length === 0) { print("   " + name + ": all fields already present"); return; }
  A.updateOne({ _id: a._id }, { $push: { fields: { $each: missing } } });
  print("   " + name + ": added " + missing.map(f => f.name).join(", "));
});
'

echo
echo "=== after ==="
show
