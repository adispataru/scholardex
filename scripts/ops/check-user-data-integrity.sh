#!/usr/bin/env bash
#
# Snapshot the user-authored data and its references into the canonical layer, so a rebuild can be
# checked before/after.
#
# WHY: a full rebuild does NOT delete user data — every deletion is scoped to `source ^SCOPUS`, and no
# user collection appears in the reset at all. The real risk is quieter: canonical ids are deterministic
# hashes of identity inputs (titleNormalized + coverDate + creator), so a rebuild that shifts any of those
# re-mints the publication under a NEW id and leaves user rows keyed on the old one dangling. Nothing is
# deleted; a researcher's confirmed claim simply stops resolving, and silently stops counting.
#
# Measured on 2026-07-25 the standing rate was ~0.2% (1 of 423 authorship refs), and that one had already
# self-healed. This script exists so the number is observed rather than assumed — especially before a
# rebuild that touches identity inputs, where it would move.
#
# READ-ONLY. Writes nothing to the database.
#
#   ./scripts/ops/check-user-data-integrity.sh                  # snapshot -> stdout + a timestamped file
#   ./scripts/ops/check-user-data-integrity.sh <previous-file>  # snapshot + diff vs previous
#
# Exits non-zero when any *_dangling count has INCREASED against the previous snapshot — the signal that a
# rebuild orphaned user rows.

set -euo pipefail

KUBECONFIG_PATH="${KUBECONFIG:-$HOME/Documents/Development/rke2-overmind/prod.kubeconfig}"
NS=scholardex
PORT=27019
PREVIOUS="${1:-}"

export KUBECONFIG="$KUBECONFIG_PATH"
command -v mongosh >/dev/null || { echo "mongosh not found" >&2; exit 1; }

PW=$(kubectl -n "$NS" get secret scholardex-db -o jsonpath='{.data.MONGO_PASSWORD}' | base64 -d)
kubectl -n "$NS" port-forward svc/scholardex-mongo "$PORT:27017" >/dev/null 2>&1 &
PF=$!
trap 'kill $PF 2>/dev/null || true' EXIT
sleep 4

OUT="user-data-integrity-$(date -u +%Y%m%dT%H%M%SZ).txt"

mongosh "mongodb://scholardex:$PW@localhost:$PORT/scholardex?authSource=admin" --quiet --eval '
const P  = db.getCollection("scholardex.publication_facts");
const A  = db.getCollection("scholardex.author_facts");
const out = [];
const emit = (k, v) => out.push(k + "=" + v);

function existing(coll, ids) {
  const s = new Set();
  coll.find({ _id: { $in: ids } }, { _id: 1 }).forEach(d => s.add(d._id));
  return s;
}
// rows / distinct refs / how many of those refs no longer resolve
function refCheck(prefix, collName, field, target) {
  const c = db.getCollection(collName);
  const set = new Set();
  c.find({}, { [field]: 1 }).forEach(d => { if (d[field]) set.add(d[field]); });
  const ids = [...set];
  const have = existing(target, ids);
  emit(prefix + "_rows", c.countDocuments({}));
  emit(prefix + "_refs", ids.length);
  emit(prefix + "_dangling", ids.filter(i => !have.has(i)).length);
}

emit("publications_total", P.countDocuments({}));

// --- user-authored -------------------------------------------------------------------------------
const U = db.getCollection("scholardex.users");
emit("users_total", U.countDocuments({}));
emit("users_with_profile", U.countDocuments({ researcherProfile: { $ne: null } }));
emit("users_platform_admin", U.countDocuments({ roles: "PLATFORM_ADMIN" }));

refCheck("authorship_decisions", "scholardex.publication_authorship_decisions", "publicationId", P);
emit("authorship_decisions_confirmed",
     db.getCollection("scholardex.publication_authorship_decisions").countDocuments({ status: "CONFIRMED" }));

// A REJECTED merge may legitimately point at a publication that a LATER approved merge retired, so it is
// reported separately and excluded from the actionable dangling count.
const M = db.getCollection("scholardex.publication_merge_decisions");
emit("merge_decisions_rows", M.countDocuments({}));
["PENDING","APPROVED","REJECTED"].forEach(s => emit("merge_decisions_" + s.toLowerCase(), M.countDocuments({ status: s })));
let survDangling = 0, survDanglingRejected = 0;
M.find({}, { status: 1, "survivor.canonicalId": 1 }).forEach(m => {
  if (P.countDocuments({ _id: m.survivor.canonicalId }) === 0) {
    if (m.status === "REJECTED") survDanglingRejected++; else survDangling++;
  }
});
emit("merge_survivors_dangling", survDangling);
emit("merge_survivors_dangling_rejected_benign", survDanglingRejected);

// Researcher-declared author ids must still resolve, or the workspace shows an unlinked profile.
const declared = new Set();
U.find({ researcherProfile: { $ne: null } }, { researcherProfile: 1 }).forEach(u => {
  const p = u.researcherProfile;
  if (p.primaryScholardexAuthorId) declared.add(p.primaryScholardexAuthorId);
  (p.confirmedScholardexAuthorIds || []).forEach(i => declared.add(i));
});
const declaredIds = [...declared];
const haveAuthors = existing(A, declaredIds);
emit("declared_author_ids", declaredIds.length);
emit("declared_author_ids_dangling", declaredIds.filter(i => !haveAuthors.has(i)).length);

["user_defined.project_facts","user_defined.publication_facts","user_defined.forum_facts"]
  .forEach(c => emit(c.replace(/[.]/g, "_"), db.getCollection(c).countDocuments({})));

// --- derived, but expensive to regenerate --------------------------------------------------------
refCheck("dblp_evidence", "scholardex.publication_dblp_evidence", "publicationId", P);
emit("report_runs", db.getCollection("userIndividualReportRuns").countDocuments({}));
emit("indicator_results", db.getCollection("userIndicatorResults").countDocuments({}));
emit("report_definitions", db.getCollection("individualReports").countDocuments({}));
emit("indicator_definitions", db.getCollection("indicators").countDocuments({}));

out.sort().forEach(l => print(l));
' | tee "$OUT"

echo
echo "snapshot written to $OUT"

if [ -n "$PREVIOUS" ]; then
    [ -f "$PREVIOUS" ] || { echo "previous snapshot not found: $PREVIOUS" >&2; exit 1; }
    echo
    echo "=== change vs $PREVIOUS ==="
    regressed=0
    while IFS='=' read -r key now; do
        before=$(grep -E "^${key}=" "$PREVIOUS" | cut -d= -f2- || true)
        [ -n "${before:-}" ] || { printf '  %-46s      -> %-8s (new)\n' "$key" "$now"; continue; }
        [ "$before" = "$now" ] && continue
        printf '  %-46s %-8s -> %-8s' "$key" "$before" "$now"
        case "$key" in
            *_dangling)
                if [ "$now" -gt "$before" ]; then printf '   ORPHANED (+%s)' "$((now - before))"; regressed=1; fi ;;
        esac
        echo
    done < "$OUT"
    echo
    if [ "$regressed" -eq 1 ]; then
        echo "FAIL: user rows were orphaned — a canonical id they point at was re-minted."
        echo "      Nothing was deleted; the rows no longer resolve. Investigate before refreshing reports."
        exit 1
    fi
    echo "OK: no user row was orphaned."
fi
