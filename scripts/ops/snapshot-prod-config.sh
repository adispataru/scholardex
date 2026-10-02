#!/usr/bin/env bash
#
# Snapshot the CONFIG layer of the PRODUCTION database into seed/precious-config (read-only against prod).
#
# WHY: the committed seed and a developer's local database drift from what admins change in prod (report
# selections per division, report edits, org units). This brings the git-tracked config snapshot to prod's
# state; `git diff seed/` then shows exactly what prod holds that the seed did not, and
# scripts/ops/load-config-seed-local.sh reloads a local database from it.
#
# Reads only the config collections (definitions, catalog, org structure). The personal-data collections
# (users, decisions, activity entries, runs, affiliations) are NOT exported — SNAPSHOT_CONFIG_ONLY=1 — so no
# production personal data lands in the development directory (H122).
#
#   Usage:  ./scripts/ops/snapshot-prod-config.sh        # from the repo root

set -euo pipefail

KUBECONFIG_PATH="${KUBECONFIG:-$HOME/Documents/Development/rke2-overmind/prod.kubeconfig}"
NS=scholardex
PORT=27019

[ -f scripts/h54-1-snapshot-precious.js ] || { echo "run from the repository root" >&2; exit 1; }
export KUBECONFIG="$KUBECONFIG_PATH"
command -v mongosh >/dev/null || { echo "mongosh not found" >&2; exit 1; }

PW=$(kubectl -n "$NS" get secret scholardex-db -o jsonpath='{.data.MONGO_PASSWORD}' | base64 -d)
kubectl -n "$NS" port-forward svc/scholardex-mongo "$PORT:27017" >/dev/null 2>&1 &
PF=$!
trap 'kill $PF 2>/dev/null || true' EXIT
sleep 4

URI="mongodb://scholardex:$PW@localhost:$PORT/scholardex?authSource=admin"
SNAPSHOT_CONFIG_ONLY=1 mongosh "$URI" --quiet scripts/h54-1-snapshot-precious.js

echo
echo "=== what prod holds that the committed seed did not ==="
git --no-pager diff --stat -- seed/precious-config
