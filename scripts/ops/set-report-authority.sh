#!/usr/bin/env bash
#
# H129 — mark the reports that apply UEFISCDI's rules, in the PRODUCTION database.
#
# WHY: a report names the authority whose rules it applies (`authority`: CNATDCU or UEFISCDI), and the
# sidebar has one entry per authority. A report stored before the field existed names none and counts as
# CNATDCU, so the three eligibility reports show under CNATDCU until they are marked. The admin report
# form can do it too ("Applies the rules of"); this does the three at once.
#
# Reports are matched by their exact TITLE. One that is not found is reported and skipped; nothing else
# is touched. The previous value of every changed report is kept in `scholardex.app_migrations`
# (set-report-authority-v1), so this is reversible.
#
#   Usage:  ./scripts/ops/set-report-authority.sh                 # the three UEFISCDI reports
#           ./scripts/ops/set-report-authority.sh CNATDCU "FEAA 2026"    # any authority, any titles
#
# No restart is needed: the page reads the report at every request.

set -euo pipefail

KUBECONFIG_PATH="${KUBECONFIG:-$HOME/Documents/Development/rke2-overmind/prod.kubeconfig}"
NS=scholardex
PORT=27019

AUTHORITY="${1:-UEFISCDI}"
case "$AUTHORITY" in
  CNATDCU|UEFISCDI) ;;
  *) echo "unknown authority '$AUTHORITY' (CNATDCU or UEFISCDI)" >&2; exit 1 ;;
esac
if [ "$#" -gt 1 ]; then
  shift
  TITLES=("$@")
else
  TITLES=("Eligibilitate PD" "Eligibilitate PD 2026" "Eligibilitate Tinere Echipe")
fi
# The titles as a JSON array, built without a shell-quoting trap: one title per line into mongosh's stdin
# would need a second channel, so they are JSON-encoded here and embedded as data.
TITLES_JSON=$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1:], ensure_ascii=False))' "${TITLES[@]}")

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
const R = db.getCollection("individualReports");
R.find({}, { title: 1, authority: 1 }).sort({ title: 1 }).forEach(r =>
  print("   " + (r.authority || "(none → CNATDCU)").padEnd(18) + r.title));
'
}

echo "=== before: every report and its authority ==="
show

echo
echo "=== to change: authority → $AUTHORITY ==="
TITLES_JSON="$TITLES_JSON" mongosh "$URI" --quiet --eval '
const R = db.getCollection("individualReports");
JSON.parse(process.env.TITLES_JSON).forEach(title => {
  const n = R.countDocuments({ title: title });
  print("   " + (n === 1 ? "found     " : n === 0 ? "NOT FOUND " : "AMBIGUOUS (" + n + ") ") + title);
});
'

printf '\nset the authority of the reports found to %s, in production? [y/N] ' "$AUTHORITY"
read -r confirm < /dev/tty
[ "$confirm" = "y" ] || { echo "aborted, nothing changed"; exit 1; }

TITLES_JSON="$TITLES_JSON" AUTHORITY="$AUTHORITY" mongosh "$URI" --quiet --eval '
const R = db.getCollection("individualReports");
const M = db.getCollection("scholardex.app_migrations");
const authority = process.env.AUTHORITY;
const previous = [];
let changed = 0;
JSON.parse(process.env.TITLES_JSON).forEach(title => {
  const matches = R.find({ title: title }, { title: 1, authority: 1 }).toArray();
  if (matches.length !== 1) {
    print("   skipped (" + matches.length + " reports with this title): " + title);
    return;
  }
  const report = matches[0];
  if (report.authority === authority) {
    print("   already " + authority + ": " + title);
    return;
  }
  previous.push({ id: report._id, title: report.title, authority: report.authority === undefined ? null : report.authority });
  R.updateOne({ _id: report._id }, { $set: { authority: authority } });
  changed++;
  print("   set: " + title);
});
if (changed > 0) {
  // Appended, not replaced: a second run must not erase what the first one recorded.
  M.updateOne({ _id: "set-report-authority-v1" }, {
    $setOnInsert: { reason: "H129: reports name the authority whose rules they apply" },
    $push: { runs: { appliedAt: new Date().toISOString(), authority: authority, previous: previous } }
  }, { upsert: true });
}
print("changed: " + changed + (changed > 0 ? "  (previous values kept at app_migrations/set-report-authority-v1)" : ""));
'

echo
echo "=== after ==="
show
