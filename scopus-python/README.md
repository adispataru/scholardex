# scopus-python service

FastAPI + [pybliometrics](https://pybliometrics.readthedocs.io) wrapper around the Elsevier Scopus
API. The Java `core` app calls it for two operations:

- `POST /v1/author-works` — an author's Scopus works since a date
- `POST /v1/citations/by-eid` — citing papers per EID
- `GET  /v1/health` — liveness (does **not** touch Scopus)

It lives in Python because pybliometrics handles the Scopus Search API's query language, cursor
pagination, `AbstractRetrieval` enrichment, and entitlement/rate-limit quirks — there is no
comparable Java library. Rather than reimplement all that, we run this as an internal container.

## Why you can't fully test it off-campus

Scopus **full access is IP + institution gated**. Without an entitled (campus) IP and a real API
key, the service still **boots and serves `/v1/health`**, but actual Scopus calls return
`403 scopus_access_denied`. Successful calls are therefore an **on-prem** check, not a laptop one.

## Run

```bash
docker build -t scopus-python .
docker run -d --name scopus-python -p 65008:65008 \
  -e SCOPUS_API_KEY=<your-elsevier-key> \
  -e SCOPUS_INST_TOKEN=<optional-institutional-token> \
  scopus-python
curl localhost:65008/v1/health        # -> {"status":"ok", ...}
```

Or via the repo-root `docker-compose.yml` (wires it to `core` on an internal network, no public
port). Credentials come from `.env` (see `.env.example`).

## Config

`entrypoint.sh` writes pybliometrics' config file from `SCOPUS_API_KEY` / `SCOPUS_INST_TOKEN` at
startup, so no secret lives in the image or repo. `PORT` (default `65008`) sets the listen port.

Requires **pybliometrics 4.x** (the `from pybliometrics.scopus import init` config-file API).
