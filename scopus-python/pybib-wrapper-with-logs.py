# main.py
from __future__ import annotations

from datetime import datetime, date
from typing import Optional, Dict, Any, List, Annotated

import os
import logging
from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel, Field, StringConstraints

# pybliometrics config: set env before running:
#   export PYBLIOMETRICS_API_KEY=xxxxxxxxxxxxxxxx
#   export PYBLIOMETRICS_INST_TOKEN=yyyyyyyyyyyyyy   # optional
from pybliometrics.scopus import ScopusSearch, AbstractRetrieval
from pybliometrics.scopus import init as scopus_init
from pybliometrics.exception import Scopus400Error, Scopus401Error, Scopus403Error, Scopus429Error

# Initialize from env or ~/.pybliometrics/config.ini (newer pybliometrics ignores args)

scopus_init()



# -------------------------
# Logging
# -------------------------
logger = logging.getLogger("scopus_api")
logger.setLevel(logging.INFO)

_handler = logging.StreamHandler()  # stdout (docker/systemd friendly)
_formatter = logging.Formatter("%(asctime)s | %(levelname)s | %(name)s | %(message)s")
_handler.setFormatter(_formatter)
if not logger.handlers:
    logger.addHandler(_handler)


def _as_list(v):
    """Turn strings like 'a;b;c' into ['a','b','c'], lists/tuples/sets into list(...),
    ints into [int], None into []. Keeps empty entries out."""
    if v is None:
        return []
    if isinstance(v, str):
        parts = [p for p in v.split(";") if p != ""]
        return parts
    if isinstance(v, (list, tuple, set)):
        return list(v)
    # scalar (int, float, other) -> single-element list
    return [v]

def _join_sc(seq) -> str:
    """Join with ';' after coercing everything to str and dropping None/empty."""
    return ";".join(str(x) for x in seq if x is not None and str(x) != "")




# -------------------------
# Pydantic request models
# -------------------------
DigitId = Annotated[str, StringConstraints(pattern=r"^[0-9]{5,20}$")]
IsoDate = Annotated[str, StringConstraints(pattern=r"^\d{4}-\d{2}-\d{2}$")]

class PageRequest(BaseModel):
    page_size: int = Field(25, ge=1, le=100)
    cursor: Optional[str] = None  # opaque; "offset:n" for fallback

class AuthorWorksReq(BaseModel):
    request_id: Optional[str] = None
    author_id: DigitId
    from_date: Optional[IsoDate] = None  # None / null → no date filter (full update)
    paging: PageRequest = PageRequest()
    include_enrichment: bool = False  # authors, affiliations, subtypeDescription, funding, etc.
    format: Annotated[str, StringConstraints(pattern=r"^(legacy|normalized)$")] = "legacy"

from typing import Dict, Optional, Annotated
from pydantic import BaseModel, Field, StringConstraints

# reuse IsoDate if you have it; for values we allow None or ISO string
class CitationsByEidReq(BaseModel):
    request_id: Optional[str] = None
    # map of cited EID -> last citation date (or null)
    eid_last_date: Dict[str, Optional[str]]
    page_size_per_eid: int = Field(25, ge=1, le=100)
    include_enrichment: bool = False

class CitedWorkSpec(BaseModel):
    """One cited work for the reverse-title search (H106 S5)."""
    title: str
    # surname variants of the researcher as they may appear in a reference ("Fortis", "Fortiş")
    surnames: List[str] = []
    # lower bound on the citing document's cover date: "YYYY-MM-DD" | "YYYY-MM" | "YYYY" | None
    from_date: Optional[str] = None
    # citing EIDs already known for this work (found by REF(eid) or earlier passes) — never re-reported
    known_citing_eids: List[str] = []


class CitationsByTitleReq(BaseModel):
    request_id: Optional[str] = None
    # cited key -> spec; the key is echoed back verbatim (Scopus EID, "doi:<doi>" or a canonical id)
    items: Dict[str, CitedWorkSpec]
    page_size_per_item: int = Field(25, ge=1, le=100)
    include_enrichment: bool = False
    # read each hit's reference list and keep only hits whose reference names the title AND a surname
    verify_references: bool = True


# -------------------------
# Helpers
# -------------------------
def _parse_date(sdate: Optional[str]) -> Optional[date]:
    if not sdate:
        return None
    for fmt in ("%Y-%m-%d", "%Y-%m", "%Y"):
        try:
            d = datetime.strptime(sdate, fmt).date()
            if fmt == "%Y": d = d.replace(month=1, day=1)
            if fmt == "%Y-%m": d = d.replace(day=1)
            return d
        except Exception:
            pass
    return None

def _display_month(cover_date: Optional[str]) -> str:
    dt = _parse_date(cover_date)
    if not dt:
        return ""
    return dt.strftime("%B %Y")  # e.g., "March 2024"

def _int01(val) -> int:
    # openaccess can be 0/1/bool/None
    try:
        return 1 if int(val) == 1 else 0
    except Exception:
        return 1 if bool(val) else 0

# -------------------------
# Core fetch
# -------------------------
def fetch_author_rows(
    author_id: str,
    from_date: Optional[str],
    page_size: int,
    cursor: Optional[str]
) -> tuple[list[Any], Optional[str], int, str, Optional[date]]:
    """Fetch a page of ScopusSearch rows; return (rows, next_cursor, total, upstream_query, from_date_obj).

    from_date=None means no date filter (full update — fetch all publications).
    """
    if not (author_id.isdigit() and 5 <= len(author_id) <= 20):
        raise ValueError("invalid_author_id")
    if not (1 <= page_size <= 100):
        raise ValueError("invalid_paging")

    fd: Optional[date] = None
    if from_date:
        try:
            fd = datetime.strptime(from_date, "%Y-%m-%d").date()
        except ValueError:
            raise ValueError("invalid_from_date")

    if fd is not None:
        year_floor = fd.year - 1
        upstream_query = f"AU-ID({author_id}) AND PUBYEAR > {year_floor}"
    else:
        upstream_query = f"AU-ID({author_id})"

    # Try cursor pagination; fall back to offset if this pybliometrics lacks cursor
    try:
        logger.info("Calling ScopusSearch | query=%s | count=%s | cursor=%s", upstream_query, page_size, cursor)
        s = ScopusSearch(query=upstream_query, view="STANDARD", refresh=False, count=page_size, cursor=cursor)
        rows = s.results or []
        total = getattr(s, "get_results_size", lambda: None)() or getattr(s, "results_size", None) or 0
        next_cursor = getattr(s, "next_cursor", None) or getattr(s, "_next", None)
    except TypeError:
        # offset fallback (cursor="offset:n")
        start = 0
        if cursor and cursor.startswith("offset:"):
            try:
                start = int(cursor.split(":", 1)[1])
            except Exception:
                start = 0
        logger.info("Calling ScopusSearch (offset fallback) | query=%s | count=%s | start=%s", upstream_query, page_size, start)
        s = ScopusSearch(query=upstream_query, view="STANDARD", refresh=False, count=page_size, start=start)
        rows = s.results or []
        total = getattr(s, "get_results_size", lambda: None)() or getattr(s, "results_size", None) or 0
        next_cursor = f"offset:{start + page_size}" if len(rows) == page_size else None

    # Filter by exact date >= from_date (skip when doing full update with no date filter)
    filtered = []
    for r in rows:
        if fd is None:
            filtered.append(r)
        else:
            cd = getattr(r, "coverDate", None)
            if _parse_date(cd) is None or _parse_date(cd) >= fd:
                filtered.append(r)

    def sort_key(r):
        cd = getattr(r, "coverDate", None)
        d = _parse_date(cd) or date(1,1,1)
        return (d, getattr(r, "eid", "") )

    filtered.sort(key=sort_key, reverse=True)
    return filtered, next_cursor, int(total), upstream_query, fd

# -------------------------
# Transform to normalized
# -------------------------
def to_normalized(row, cdm_version: str = "1.0") -> Dict[str, Any]:
    cover_date = getattr(row, "coverDate", None)
    pub_year = int(cover_date[:4]) if cover_date else None
    return {
        "cdm_version": cdm_version,
        "kind": "document",
        "eid": getattr(row, "eid", None),
        "doi": getattr(row, "doi", None),
        "title": getattr(row, "title", None),
        "subtype": getattr(row, "subtype", None),
        "publication_year": pub_year,
        "publication_date": cover_date,
        "journal": {
            "title": getattr(row, "publicationName", None),
            "issn": getattr(row, "issn", None),
            "eissn": getattr(row, "eIssn", None),
            "volume": getattr(row, "volume", None),
            "issue": getattr(row, "issueIdentifier", None),
            "pages": getattr(row, "pageRange", None),
            "publisher": getattr(row, "publisher", None),
            "source_id": getattr(row, "source_id", None),
        },
        "open_access": {"is_oa": getattr(row, "openaccess", None), "license": None},
        "keywords": getattr(row, "authkeywords", []) or [],
        "subject_areas": [],
        "authors": [],
        "affiliations": [],
        "citation_count": getattr(row, "citedby_count", None),
        "abstract": None,
        "references": None,
        "links": {
            "scopus": f"https://www.scopus.com/record/display.uri?eid={getattr(row, 'eid', '')}",
            "fulltext": None,
        },
        "provenance": {
            "source_record_id": getattr(row, "eid", None),
            "fetched_at": datetime.utcnow().isoformat(timespec="seconds") + "Z",
        },
        "raw": None,
    }

# -------------------------
# Transform to legacy (flat) with optional enrichment
# -------------------------
def to_legacy(
    row,
    include_enrichment: bool = False,
) -> Dict[str, Any]:
    # --- base from search row ---
    eid = getattr(row, "eid", "") or ""
    title = getattr(row, "title", "") or ""
    doi = getattr(row, "doi", "") or ""
    subtype = getattr(row, "subtype", "") or ""
    subtypeDescription = getattr(row, "subtypeDescription", "") or ""   # sometimes on COMPLETE view
    citedby_count = getattr(row, "citedby_count", None)
    openaccess = _int01(getattr(row, "openaccess", 0))
    pageRange = getattr(row, "pageRange", "") or ""
    coverDate = getattr(row, "coverDate", "") or ""
    coverDisplayDate = _display_month(coverDate)
    volume = getattr(row, "volume", "") or ""
    issueIdentifier = getattr(row, "issueIdentifier", "") or ""
    publicationName = getattr(row, "publicationName", "") or ""
    issn = getattr(row, "issn", "") or ""
    eIssn = getattr(row, "eIssn", "") or ""
    source_id = getattr(row, "source_id", "") or ""
    aggregationType = getattr(row, "aggregationType", "") or ("Journal" if (issn or eIssn) else "")
    article_number = getattr(row, "article_number", "") or ""

    # Authors & affiliations from SEARCH ROW (may be list OR semicolon string OR ints)
    author_ids = [str(x) for x in _as_list(getattr(row, "author_ids", ""))]
    author_names = [str(x) for x in _as_list(getattr(row, "author_names", ""))]
    author_afids = [str(x) for x in _as_list(getattr(row, "author_afids", ""))]

    afid_list = [str(x) for x in _as_list(getattr(row, "afid", ""))]
    affilname_list = [str(x) for x in _as_list(getattr(row, "affilname", ""))]
    aff_city_list = [str(x) for x in _as_list(getattr(row, "affiliation_city", ""))]
    aff_country_list = [str(x) for x in _as_list(getattr(row, "affiliation_country", ""))]

    # Derive creator/author_count if we already have authors
    creator = author_names[0] if author_names else getattr(row, "creator", "") or ""
    author_count = len(author_ids) if author_ids else 0

    # Description / isbn / funding defaults (may be filled by enrichment)
    description = getattr(row, "description", "") or ""
    isbn = getattr(row, "isbn", "") or ""
    fund_acr = ""
    fund_no = ""
    fund_sponsor = ""

    # --- optional enrichment to fill blanks ---
    if include_enrichment and eid:
        try:
            ar = AbstractRetrieval(eid, view="FULL")

            if not subtypeDescription:
                subtypeDescription = getattr(ar, "subtypeDescription", "") or subtypeDescription
            if not description:
                description = getattr(ar, "abstract", "") or description
            if not article_number:
                article_number = getattr(ar, "article_number", "") or article_number
            if not isbn:
                isbn = getattr(ar, "isbn", "") or ""

            # Authors (only if search row didn't provide them)
            if not author_ids or not author_names:
                _authors = getattr(ar, "authors", []) or []
                if _authors:
                    author_ids = []
                    author_names = []
                    author_afids = []
                    for a in _authors:
                        auid = getattr(a, "auid", "") or ""
                        name = getattr(a, "indexed_name", "") or getattr(a, "surname", "") or ""
                        # per-author afids: list -> '-' join
                        # --- robust per-author afids extraction ---
                        raw_aff = getattr(a, "affiliation", None)
                        a_afids_list: list[str] = []

                        if raw_aff is None:
                            a_afids_list = []
                        elif isinstance(raw_aff, (list, tuple, set)):
                            for aa in raw_aff:
                                # aa might be an object or a plain id
                                afid_val = (getattr(aa, "id", None) or getattr(aa, "afid", None) or (aa if isinstance(aa, (str, int)) else None))
                                if afid_val is not None:
                                    a_afids_list.append(str(afid_val))
                        elif isinstance(raw_aff, (str, int)):
                           # single scalar id
                           a_afids_list = [str(raw_aff)]
                        else:
                           # last resort: try to read id attribute
                           afid_val = getattr(raw_aff, "id", None) or getattr(raw_aff, "afid", None)
                           if afid_val is not None:
                               a_afids_list = [str(afid_val)]

                        author_ids.append(str(auid))
                        author_names.append(name)
                        # Only join a list of IDs; never a raw string
                        author_afids.append("-".join(a_afids_list) if a_afids_list else "")
                    creator = author_names[0] if author_names else creator
                    author_count = len(author_ids) if author_ids else author_count

            # Document-level affiliations (only if search row didn’t provide)
            if not afid_list:
                for af in (getattr(ar, "affiliation", []) or []):
                    afid_val = getattr(af, "id", None) or getattr(af, "afid", None) or ""
                    afname = getattr(af, "name", "") or ""
                    afcity = getattr(af, "city", "") or getattr(af, "address_part", "") or ""
                    afcountry = getattr(af, "country", "") or ""
                    if afid_val != "":
                        afid_list.append(str(afid_val))
                        affilname_list.append(afname)
                        aff_city_list.append(afcity)
                        aff_country_list.append(afcountry)

            # Funding (best-effort)
            if not (fund_acr or fund_no or fund_sponsor):
                funding = getattr(ar, "funding", None) or getattr(ar, "fundings", None)
                if isinstance(funding, list) and funding:
                    f0 = funding[0]
                    fund_acr = str(getattr(f0, "acronym", "") or "")
                    fund_no = str(getattr(f0, "number", "") or "")
                    fund_sponsor = str(getattr(f0, "sponsor", "") or getattr(f0, "agency", "") or "")
        except Exception:
            # swallow enrichment errors; return what we have
            pass

    # Make sure issn/eIssn are strings before replace
    issn_str = str(issn) if issn is not None else ""
    eissn_str = str(eIssn) if eIssn is not None else ""

    # --- build legacy flat dict (joins now safe) ---
    return {
        "eid": eid,
        "doi": doi,
        "title": title,
        "subtype": subtype,
        "subtypeDescription": subtypeDescription,
        "creator": creator,
        "author_count": int(author_count) if author_count is not None else 0,
        "description": description,
        "citedby_count": int(citedby_count) if citedby_count is not None else 0,
        "openaccess": int(openaccess),
        "article_number": article_number,
        "pageRange": pageRange,
        "coverDate": coverDate,
        "coverDisplayDate": coverDisplayDate,
        "volume": str(volume) if volume is not None else "",
        "issueIdentifier": str(issueIdentifier) if issueIdentifier is not None else "",
        "publicationName": publicationName,
        "issn": issn_str.replace("-", ""),
        "eIssn": eissn_str.replace("-", ""),
        "source_id": str(source_id) if source_id is not None else "",
        "isbn": isbn,
        "aggregationType": aggregationType,
        "author_ids": _join_sc(author_ids),
        "author_names": _join_sc(author_names),
        "author_afids": _join_sc(author_afids),
        "afid": _join_sc(afid_list),
        "affilname": _join_sc(affilname_list),
        "affiliation_city": _join_sc(aff_city_list),
        "affiliation_country": _join_sc(aff_country_list),
        "fund_acr": fund_acr,
        "fund_no": fund_no,
        "fund_sponsor": fund_sponsor,
    }


from typing import Optional, Dict, Any, List
from datetime import datetime, date

from pybliometrics.scopus import ScopusSearch


def fetch_citations_for_eids(
    eid_last_date: Dict[str, Optional[str]],
    page_size_per_eid: int = 25,
    include_enrichment: bool = False,
) -> Dict[str, Any]:
    """
    Given a dict { cited_eid -> from_date }, fetch citing documents for each EID.

    from_date can be:
      - "YYYY-MM-DD", "YYYY-MM", "YYYY"
      - None / ""  => no lower bound

    Returns:
    {
      "by_eid": {
        "<eid1>": [ { ... legacy flat item + 'cited_eid' ... }, ... ],
        "<eid2>": [ ... ],
        ...
      },
      "summary": {
        "total_citations": <int>,
        "per_eid": { "<eid>": <int>, ... }
      },
      "provenance": { ... }
    }
    """

    def parse_date(sdate: Optional[str]) -> Optional[date]:
        if not sdate or sdate in ("", "null", "None"):
            return None
        for fmt in ("%Y-%m-%d", "%Y-%m", "%Y"):
            try:
                d = datetime.strptime(sdate, fmt).date()
                if fmt == "%Y":
                    d = d.replace(month=1, day=1)
                if fmt == "%Y-%m":
                    d = d.replace(day=1)
                return d
            except Exception:
                pass
        raise ValueError(f"from_date '{sdate}' is not a valid YYYY, YYYY-MM, or YYYY-MM-DD")

    by_eid: Dict[str, List[Dict[str, Any]]] = {}
    per_eid_count: Dict[str, int] = {}
    t0 = datetime.utcnow()

    for eid, from_date in eid_last_date.items():
        if not eid:
            continue

        fd: Optional[date] = None
        year_clause = ""
        if from_date:
            fd = parse_date(from_date)
            # same trick as for author works: PUBYEAR >= fd.year -> PUBYEAR > (fd.year - 1)
            year_floor = fd.year - 1
            year_clause = f" AND PUBYEAR > {year_floor}"

        # docs that cite this EID
        query = f"REF({eid}){year_clause}"
        # if you get a 400 "Error translating query" on your account, try:
        # query = f"REF({eid}){year_clause}"

        try:
            s = ScopusSearch(
                query=query,
                view="STANDARD",  # or "COMPLETE" if you enrich a lot
                refresh=False,
                count=page_size_per_eid,
            )
        except Exception as ex:
            print(f"[WARN] Failed to fetch citations for {eid}: {ex}")
            by_eid[eid] = []
            per_eid_count[eid] = 0
            continue

        rows = s.results or []

        # filter by exact coverDate if fd provided
        filtered_rows = []
        for r in rows:
            if fd is None:
                filtered_rows.append(r)
            else:
                cd = getattr(r, "coverDate", None)
                try:
                    d = parse_date(cd) if cd else None
                except Exception:
                    d = None
                if d is None or d >= fd:
                    filtered_rows.append(r)

        # sort newest first
        def sort_key(r):
            cd = getattr(r, "coverDate", None)
            try:
                d = parse_date(cd) if cd else None
            except Exception:
                d = None
            return (d or date(1, 1, 1), getattr(r, "eid", "") or "")

        filtered_rows.sort(key=sort_key, reverse=True)

        # transform to legacy flat items & attach cited_eid
        items_for_eid: List[Dict[str, Any]] = []
        for r in filtered_rows:
            legacy = to_legacy(r, include_enrichment=include_enrichment)
            legacy["cited_eid"] = eid
            items_for_eid.append(legacy)

        by_eid[eid] = items_for_eid
        per_eid_count[eid] = len(items_for_eid)

    elapsed_ms = int((datetime.utcnow() - t0).total_seconds() * 1000)

    return {
        "by_eid": by_eid,
        "summary": {
            "total_citations": sum(per_eid_count.values()),
            "per_eid": per_eid_count,
        },
        "provenance": {
            "fetched_at": datetime.utcnow().isoformat(timespec="seconds") + "Z",
            "eid_count": len(eid_last_date),
            "page_size_per_eid": page_size_per_eid,
            "elapsed_ms": elapsed_ms,
        },
    }



# -------------------------
# H106 S5 — reverse-title citation search
# -------------------------
_REF_QUOTES = str.maketrans({'"': ' ', '{': ' ', '}': ' ', '(': ' ', ')': ' '})


def _norm_text(value: Optional[str]) -> str:
    """Lower-case, diacritics stripped, punctuation dropped, spaces collapsed — for reference matching."""
    if not value:
        return ""
    import unicodedata
    decomposed = unicodedata.normalize("NFKD", str(value))
    ascii_only = "".join(ch for ch in decomposed if not unicodedata.combining(ch))
    kept = "".join(ch if ch.isalnum() else " " for ch in ascii_only.lower())
    return " ".join(kept.split())


def _build_title_query(spec: CitedWorkSpec) -> str:
    # Loose phrase ("...") rather than exact ({...}): references drop subtitles and punctuation, and the
    # reference check below restores precision. Braces/quotes inside the title would break the query.
    title = " ".join(spec.title.translate(_REF_QUOTES).split())
    query = f'REFTITLE("{title}")'
    surnames = [" ".join(x.translate(_REF_QUOTES).split()) for x in (spec.surnames or []) if x and x.strip()]
    if surnames:
        query += " AND (" + " OR ".join(f"REFAUTH({x})" for x in surnames) + ")"
    if spec.from_date:
        year = int(str(spec.from_date)[:4])
        query += f" AND PUBYEAR > {year - 1}"
    return query


def _reference_match(references, title: str, surnames: List[str]):
    """The first reference whose title/text contains the cited title and (when surnames are given) a surname."""
    want_title = _norm_text(title)
    want_names = [_norm_text(x) for x in (surnames or []) if x]
    if not want_title:
        return None
    for ref in references or []:
        ref_title = _norm_text(getattr(ref, "title", None))
        ref_text = _norm_text(getattr(ref, "fulltext", None) or getattr(ref, "text", None))
        title_ok = (want_title in ref_title) if ref_title else (want_title in ref_text)
        if not title_ok:
            continue
        if not want_names:
            return ref
        ref_authors = _norm_text(getattr(ref, "authors", None))
        haystack = ref_authors + " " + ref_text
        # A mangled reference truncates the surname ("Forti��" for "Fortiș"), so a surname of five or
        # more letters also matches on its stem minus the last letter. The title is the strong signal.
        for name in want_names:
            if not name:
                continue
            if name in haystack or (len(name) >= 5 and name[:-1] in haystack):
                return ref
    return None


def fetch_citations_by_title(
    items: Dict[str, CitedWorkSpec],
    page_size_per_item: int = 25,
    include_enrichment: bool = False,
    verify_references: bool = True,
) -> Dict[str, Any]:
    """
    For each cited work, search Scopus for documents whose REFERENCE TEXT names the title (REFTITLE),
    narrowed by the researcher's surname (REFAUTH). Finds what REF(eid) cannot: citations of works with
    no Scopus EID, and references Scopus failed to link (mangled author strings, fresh documents).
    Known citing EIDs are dropped; every remaining hit is verified against its own reference list unless
    verify_references is off. Items carry 'cited_key', 'verified' and 'matched_reference'.
    """
    by_key: Dict[str, List[Dict[str, Any]]] = {}
    per_key_count: Dict[str, int] = {}
    unverified_total = 0
    t0 = datetime.utcnow()
    for key, spec in items.items():
        if not key or not spec or not spec.title or len(spec.title.split()) < 3:
            by_key[key] = []
            per_key_count[key] = 0
            continue
        known = {e for e in (spec.known_citing_eids or []) if e}
        query = _build_title_query(spec)
        try:
            s = ScopusSearch(query=query, view="STANDARD", refresh=False, count=page_size_per_item)
        except Exception as ex:
            logger.warning("Reverse-title search failed for %s | query=%s | %s", key, query, ex)
            by_key[key] = []
            per_key_count[key] = 0
            continue
        rows = s.results or []
        out: List[Dict[str, Any]] = []
        for r in rows:
            eid = getattr(r, "eid", "") or ""
            if not eid or eid in known:
                continue
            verified = None
            matched = None
            if verify_references:
                try:
                    ar = AbstractRetrieval(eid, view="REF")
                    ref = _reference_match(getattr(ar, "references", None), spec.title, spec.surnames)
                    verified = ref is not None
                    if ref is not None:
                        matched = getattr(ref, "fulltext", None) or getattr(ref, "text", None) or getattr(ref, "title", None)
                except Exception as ex:
                    logger.warning("Reference check failed for %s citing %s | %s", eid, key, ex)
                    verified = False
            legacy = to_legacy(r, include_enrichment=include_enrichment)
            legacy["cited_key"] = key
            legacy["verified"] = verified
            legacy["matched_reference"] = matched
            legacy["search_query"] = query
            if verified is False:
                unverified_total += 1
            out.append(legacy)
        by_key[key] = out
        per_key_count[key] = len(out)
    elapsed_ms = int((datetime.utcnow() - t0).total_seconds() * 1000)
    return {
        "by_key": by_key,
        "summary": {
            "total_hits": sum(per_key_count.values()),
            "unverified": unverified_total,
            "per_key": per_key_count,
        },
        "provenance": {
            "fetched_at": datetime.utcnow().isoformat(timespec="seconds") + "Z",
            "item_count": len(items),
            "page_size_per_item": page_size_per_item,
            "verify_references": verify_references,
            "elapsed_ms": elapsed_ms,
        },
    }


# -------------------------
# FastAPI app
# -------------------------
app = FastAPI(title="Author Works (legacy-compatible)")

@app.get("/v1/health")
def health():
    return {"status": "ok", "pybliometrics": "3.x"}

@app.post("/v1/author-works")
def author_works(body: AuthorWorksReq):
    logger.info(
        "POST /v1/author-works | request_id=%s | author_id=%s | from_date=%s | page_size=%s | cursor=%s | format=%s | enrichment=%s",
        body.request_id,
        body.author_id,
        body.from_date,
        body.paging.page_size if body.paging else None,
        body.paging.cursor if body.paging else None,
        body.format,
        body.include_enrichment,
    )
    try:
        rows, next_cursor, total, upstream_query, _fd = fetch_author_rows(
            author_id=body.author_id,
            from_date=body.from_date,
            page_size=body.paging.page_size,
            cursor=body.paging.cursor,
        )

        if body.format == "normalized":
            items = [to_normalized(r) for r in rows]
            return {
                "request_id": body.request_id,
                "author_id": body.author_id,
                "from_date": body.from_date,
                "total": total,
                "next_cursor": next_cursor,
                "items": items,
                "provenance": {
                    "source": "pybliometrics",
                    "fetched_at": datetime.utcnow().isoformat(timespec="seconds") + "Z",
                    "library_version": "pybliometrics",
                    "upstream_query": upstream_query,
                    "page_size": body.paging.page_size,
                    "cursor_used": body.paging.cursor,
                },
            }

        # legacy (default): return array of flat items (your Spring importer can loop these)
        items = [to_legacy(r, include_enrichment=body.include_enrichment) for r in rows]
        return {
            "request_id": body.request_id,
            "author_id": body.author_id,
            "from_date": body.from_date,
            "total": total,
            "next_cursor": next_cursor,
            "items": items  # <-- your code can iterate this array and reuse existing mappers
        }

    except ValueError as ve:
        logger.warning("Bad request in /v1/author-works | %s", str(ve), exc_info=True)
        raise HTTPException(status_code=400, detail=str(ve))

    except Scopus429Error as ex:
        logger.warning("Scopus rate limited in /v1/author-works | %s", str(ex), exc_info=True)
        raise HTTPException(status_code=429, detail=f"scopus_rate_limited: {ex}")

    except (Scopus401Error, Scopus403Error) as ex:
        logger.error("Scopus access/entitlement error in /v1/author-works | %s", str(ex), exc_info=True)
        raise HTTPException(status_code=403, detail=f"scopus_access_denied: {ex}")

    except Scopus400Error as ex:
        # Includes "Exceeds the maximum number allowed for the service level"
        logger.warning("Scopus400Error in /v1/author-works | %s", str(ex), exc_info=True)
        raise HTTPException(status_code=400, detail=f"scopus_bad_request_or_service_level: {ex}")

    except Exception as ex:
        logger.exception("Unhandled error in /v1/author-works")
        raise HTTPException(status_code=502, detail=f"upstream_error: {type(ex).__name__}: {ex}")

@app.post("/v1/citations/by-eid")
def citations_by_eid(body: CitationsByEidReq):
    logger.info(
        "POST /v1/citations/by-eid | request_id=%s | eid_count=%s | page_size_per_eid=%s | enrichment=%s",
        body.request_id,
        len(body.eid_last_date) if body.eid_last_date else 0,
        body.page_size_per_eid,
        body.include_enrichment,
    )
    try:
        data = fetch_citations_for_eids(
            eid_last_date=body.eid_last_date,
            page_size_per_eid=body.page_size_per_eid,
            include_enrichment=body.include_enrichment,
        )
        # echo request_id for tracing
        data["request_id"] = body.request_id
        return data
    except ValueError as ve:
        # e.g., invalid date format
        logger.warning("Bad request in /v1/citations/by-eid | %s", str(ve), exc_info=True)
        raise HTTPException(status_code=400, detail=str(ve))

    except Scopus429Error as ex:
        logger.warning("Scopus rate limited in /v1/citations/by-eid | %s", str(ex), exc_info=True)
        raise HTTPException(status_code=429, detail=f"scopus_rate_limited: {ex}")

    except (Scopus401Error, Scopus403Error) as ex:
        logger.error("Scopus access/entitlement error in /v1/citations/by-eid | %s", str(ex), exc_info=True)
        raise HTTPException(status_code=403, detail=f"scopus_access_denied: {ex}")

    except Scopus400Error as ex:
        logger.warning("Scopus400Error in /v1/citations/by-eid | %s", str(ex), exc_info=True)
        raise HTTPException(status_code=400, detail=f"scopus_bad_request_or_service_level: {ex}")

    except Exception as ex:
        logger.exception("Unhandled error in /v1/citations/by-eid")
        raise HTTPException(status_code=502, detail=f"upstream_error: {type(ex).__name__}: {ex}")


@app.post("/v1/citations/by-title")
def citations_by_title(body: CitationsByTitleReq):
    logger.info(
        "POST /v1/citations/by-title | request_id=%s | item_count=%s | page_size_per_item=%s | verify=%s",
        body.request_id,
        len(body.items) if body.items else 0,
        body.page_size_per_item,
        body.verify_references,
    )
    try:
        data = fetch_citations_by_title(
            items=body.items,
            page_size_per_item=body.page_size_per_item,
            include_enrichment=body.include_enrichment,
            verify_references=body.verify_references,
        )
        data["request_id"] = body.request_id
        return data
    except ValueError as ve:
        logger.warning("Bad request in /v1/citations/by-title | %s", str(ve), exc_info=True)
        raise HTTPException(status_code=400, detail=str(ve))
    except Scopus429Error as ex:
        logger.warning("Scopus rate limited in /v1/citations/by-title | %s", str(ex), exc_info=True)
        raise HTTPException(status_code=429, detail=f"scopus_rate_limited: {ex}")
    except (Scopus401Error, Scopus403Error) as ex:
        logger.error("Scopus access/entitlement error in /v1/citations/by-title | %s", str(ex), exc_info=True)
        raise HTTPException(status_code=403, detail=f"scopus_access_denied: {ex}")
    except Scopus400Error as ex:
        logger.warning("Scopus400Error in /v1/citations/by-title | %s", str(ex), exc_info=True)
        raise HTTPException(status_code=400, detail=f"scopus_bad_request_or_service_level: {ex}")
    except Exception as ex:
        logger.exception("Unhandled error in /v1/citations/by-title")
        raise HTTPException(status_code=502, detail=f"upstream_error: {type(ex).__name__}: {ex}")


if __name__ == "__main__":
    import uvicorn
    # Make sure env vars PYBLIOMETRICS_API_KEY (and optionally PYBLIOMETRICS_INST_TOKEN)
    # are set *in this shell* before running.
    uvicorn.run(app, host="0.0.0.0", port=65008, log_level="debug")
