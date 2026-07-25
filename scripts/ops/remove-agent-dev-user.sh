#!/usr/bin/env bash
#
# Remove the `agent@dev.local` account from the PRODUCTION user collection.
#
# WHY: it is the agent-dev profile's principal — a development artifact — and it sits in the
# production database holding PLATFORM_ADMIN + SUPERVISOR + RESEARCHER. It is not exploitable today
# (the agent-dev profile is never active in prod, and that profile bypasses security wholesale
# anyway, so the account is not the weak link), but an admin identity that nobody owns has no reason
# to exist in prod. Verified 2026-07-25 to have ZERO references: no report runs, no authorship
# decisions, no merge decisions.
#
# The document is copied into `scholardex.app_migrations` before deletion, so this is reversible.
#
#   Usage:  ./scripts/ops/remove-agent-dev-user.sh
#
# Removing it does NOT break local agent-dev: AgentDevSecurityConfig falls back to a synthetic
# profile-less principal when the DB user is absent.

set -euo pipefail

KUBECONFIG_PATH="${KUBECONFIG:-$HOME/Documents/Development/rke2-overmind/prod.kubeconfig}"
NS=scholardex
PORT=27019
EMAIL="agent@dev.local"

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
const U = db.getCollection("scholardex.users");
const u = U.findOne({ _id: "'"$EMAIL"'" });
print(u ? "target present: roles=" + JSON.stringify(u.roles) : "target ABSENT — nothing to do");
print("PLATFORM_ADMIN accounts:");
U.find({ roles: "PLATFORM_ADMIN" }).forEach(x => print("   " + x._id));
'

printf '\ndelete %s from production? [y/N] ' "$EMAIL"
read -r confirm < /dev/tty
[ "$confirm" = "y" ] || { echo "aborted, nothing changed"; exit 1; }

mongosh "$URI" --quiet --eval '
const email = "'"$EMAIL"'";
const U = db.getCollection("scholardex.users");
const M = db.getCollection("scholardex.app_migrations");
const doc = U.findOne({ _id: email });
if (!doc) { print("already absent"); quit(0); }
// Keep a restorable copy in the audit trail.
M.replaceOne({ _id: "remove-agent-dev-user-v1" }, {
  _id: "remove-agent-dev-user-v1",
  appliedAt: new Date().toISOString(),
  reason: "agent-dev profile principal held PLATFORM_ADMIN in production; dev artifact, zero references",
  removedDocument: doc
}, { upsert: true });
const r = U.deleteOne({ _id: email });
print("deleted: " + r.deletedCount + "  (copy kept at app_migrations/remove-agent-dev-user-v1)");
'

echo
echo "=== after ==="
mongosh "$URI" --quiet --eval '
const U = db.getCollection("scholardex.users");
print("total users: " + U.countDocuments({}));
print("PLATFORM_ADMIN accounts:");
U.find({ roles: "PLATFORM_ADMIN" }).forEach(x => print("   " + x._id));
print("");
print("break-glass still passwordless: " +
  (U.countDocuments({ _id: "rdi-breakglass", password: { $in: [null, ""] } }) === 1));
'
