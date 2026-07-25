#!/usr/bin/env bash
#
# Rotate the production Elsevier/Scopus API key.
#
# WHY: prod and the local dev .env currently hold the SAME key (verified 2026-07-25 by hashing both).
# A laptop compromise therefore reaches production, and Elsevier's quota and access logs cannot tell
# prod traffic from a local experiment. Give production its own key and leave .env alone.
#
# The key is read from a terminal prompt with echo off. It is never passed as an argument (argv is
# visible in `ps`), never written to a file, and never printed. Only its length and a SHA-256 prefix
# are shown, so you can confirm the change without the value appearing anywhere.
#
#   Usage:  ./scripts/ops/rotate-scopus-key.sh
#
# Get a new key from https://dev.elsevier.com/apikey/manage — issue a SECOND key rather than
# regenerating the existing one, so dev keeps working and you can revoke the old one once prod is
# confirmed healthy.

set -euo pipefail

KUBECONFIG_PATH="${KUBECONFIG:-$HOME/Documents/Development/rke2-overmind/prod.kubeconfig}"
NS=scholardex
SECRET=scholardex-scopus
KEY=SCOPUS_API_KEY

export KUBECONFIG="$KUBECONFIG_PATH"

command -v kubectl >/dev/null || { echo "kubectl not found" >&2; exit 1; }
kubectl -n "$NS" get secret "$SECRET" >/dev/null || { echo "cannot reach $NS/$SECRET" >&2; exit 1; }

fingerprint() { printf '%s' "$1" | shasum -a 256 | cut -c1-16; }

OLD=$(kubectl -n "$NS" get secret "$SECRET" -o jsonpath="{.data.$KEY}" | base64 -d)
echo "current prod key: len=${#OLD}  sha256=$(fingerprint "$OLD")…"

# -s: no echo. Reading from /dev/tty (not stdin) keeps this working under a pipe.
printf 'new Scopus API key (input hidden): '
read -rs NEW < /dev/tty
echo

[ -n "$NEW" ] || { echo "empty key, aborting" >&2; exit 1; }
if [ "${#NEW}" -ne 32 ]; then
  echo "warning: Elsevier keys are 32 chars; this one is ${#NEW}." >&2
  printf 'continue anyway? [y/N] '; read -r yn < /dev/tty
  [ "$yn" = "y" ] || { echo "aborted"; exit 1; }
fi
if [ "$NEW" = "$OLD" ]; then
  echo "that is the key already in production — nothing to do." >&2
  exit 1
fi

# Refuse to install the key that is sitting in the developer .env: that is the problem being fixed.
ENV_FILE="$(cd "$(dirname "$0")/../.." && pwd)/.env"
if [ -f "$ENV_FILE" ]; then
  DEV=$(grep -E '^SCOPUS_API_KEY=' "$ENV_FILE" | head -1 | cut -d= -f2- || true)
  if [ -n "${DEV:-}" ] && [ "$NEW" = "$DEV" ]; then
    echo "that key is the one in your local .env — prod must have its OWN key. Aborting." >&2
    exit 1
  fi
fi

echo "new key:          len=${#NEW}  sha256=$(fingerprint "$NEW")…"
printf 'patch %s/%s and restart scholardex-core? [y/N] ' "$NS" "$SECRET"
read -r confirm < /dev/tty
[ "$confirm" = "y" ] || { echo "aborted, nothing changed"; exit 1; }

# --from-literal via `create --dry-run | apply` replaces the value without a shell-visible argv entry
# on the apply, and without hand-rolling base64.
kubectl -n "$NS" create secret generic "$SECRET" \
  --from-literal="$KEY=$NEW" --dry-run=client -o yaml | kubectl -n "$NS" apply -f - >/dev/null
unset NEW DEV

echo "secret updated. restarting consumers…"
kubectl -n "$NS" rollout restart deploy/scholardex-core deploy/scholardex-scopus-python
kubectl -n "$NS" rollout status deploy/scholardex-scopus-python --timeout=180s
kubectl -n "$NS" rollout status deploy/scholardex-core --timeout=300s

NOW=$(kubectl -n "$NS" get secret "$SECRET" -o jsonpath="{.data.$KEY}" | base64 -d)
echo
echo "prod key now:     len=${#NOW}  sha256=$(fingerprint "$NOW")…"
echo
echo "Verify the key actually works before revoking the old one — trigger any Scopus-backed"
echo "action in the app, then watch for a 200 (a bad key shows as 401/429 here):"
echo
echo "  kubectl -n $NS logs deploy/scholardex-scopus-python -f | grep -v health"
echo
echo "Once you see a successful ScopusSearch call, revoke the OLD key at"
echo "https://dev.elsevier.com/apikey/manage. Leave the .env key alone — dev keeps its own."
