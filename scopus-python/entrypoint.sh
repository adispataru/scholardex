#!/bin/sh
# Container entrypoint for the Scopus wrapper service.
#
# pybliometrics reads its credentials from a config file at import time (scopus_init() runs when
# uvicorn imports the app). We generate that file here from environment secrets so nothing sensitive
# lives in the image or the repo. Off-campus / without a real key the service still BOOTS and serves
# /v1/health — the actual Scopus calls only succeed from an entitled (campus) IP on-prem.
set -eu

CONFIG_DIR="${HOME}/.config"
CACHE_DIR="${HOME}/.cache/pybliometrics"
CFG="${CONFIG_DIR}/pybliometrics.cfg"
mkdir -p "${CONFIG_DIR}" \
         "${CACHE_DIR}/AbstractRetrieval" \
         "${CACHE_DIR}/ScopusSearch" \
         "${CACHE_DIR}/AuthorRetrieval" \
         "${CACHE_DIR}/AffiliationRetrieval"

{
  echo "[Directories]"
  echo "AbstractRetrieval = ${CACHE_DIR}/AbstractRetrieval"
  echo "ScopusSearch = ${CACHE_DIR}/ScopusSearch"
  echo "AuthorRetrieval = ${CACHE_DIR}/AuthorRetrieval"
  echo "AffiliationRetrieval = ${CACHE_DIR}/AffiliationRetrieval"
  echo ""
  echo "[Authentication]"
  echo "APIKey = ${SCOPUS_API_KEY:-DUMMY_KEY_SET_ON_PREM}"
  if [ -n "${SCOPUS_INST_TOKEN:-}" ]; then
    echo "InstToken = ${SCOPUS_INST_TOKEN}"
  fi
  echo ""
  echo "[Requests]"
  echo "Timeout = 20"
  echo "Retries = 5"
} > "${CFG}"

# pybliometrics 3.x honors PYB_CONFIG_FILE; also leave the file at the default ~/.config path.
export PYB_CONFIG_FILE="${CFG}"

exec uvicorn app:app --host 0.0.0.0 --port "${PORT:-65008}" --log-level info
