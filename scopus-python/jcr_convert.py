"""
Convert the raw JCR harvest (data/jcr-web/jcr-journals-YYYY.jsonl, from jcr_dump.py) into the
per-year / per-edition JSON files the app's OFFICIAL_WOS_EXTRACT ingest reads
(data/wos-json-1997-2019/journals-{EDITION}-year-{YYYY}.json).

Field mapping notes:
 - the grid's current-year JIF hides under the legacy field name `jif2019`;
 - one output item per (journal, category) — the grid row carries categoryQuartiles[];
 - NO rank is emitted (the extract's rank is a citation rank, not a metric rank — the app's
   enrichment computes true metric ranks/quartiles from the values);
 - AIS is included for completeness, but AIS facts keep coming from the UEFISCDI files (the fact
   builder prefers GOV_AIS_RIS for AIS), so no conflict.

Run:  python3 scopus-python/jcr_convert.py [--years 2020-2025]
"""
import argparse
import json
import pathlib
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parent
REPO = ROOT.parent
IN_DIR = REPO / "data" / "jcr-web"
OUT_DIR = REPO / "data" / "wos-json-1997-2019"

EDITIONS = ("SCIE", "SSCI", "AHCI", "ESCI")


def clean(value):
    if value is None:
        return None
    s = str(value).strip()
    return None if s in ("", "N/A", "n/a") else s


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--years", default="2020-2025")
    args = parser.parse_args()
    if "-" in args.years:
        lo, hi = args.years.split("-", 1)
        years = list(range(int(lo), int(hi) + 1))
    else:
        years = [int(args.years)]

    for year in years:
        src = IN_DIR / f"jcr-journals-{year}.jsonl"
        if not src.exists():
            print(f"year {year}: {src} missing — run jcr_dump.py first, skipping")
            continue
        items_by_edition = defaultdict(list)
        rows = seen = 0
        for line in src.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            rows += 1
            row = json.loads(line)
            jif = clean(row.get("jif2019"))
            ais = clean(row.get("articleInfluenceScore"))
            for cq in row.get("categoryQuartiles") or []:
                edition = clean(cq.get("edition"))
                category = clean(cq.get("category"))
                if edition not in EDITIONS or not category:
                    continue
                seen += 1
                items_by_edition[edition].append({
                    "journalTitle": clean(row.get("journalName")),
                    "abbrJournal": clean(row.get("abbrJournal")),
                    "issn": clean(row.get("issn")),
                    "eissn": clean(row.get("eissn")),
                    "year": year,
                    "edition": edition,
                    "categoryName": category,
                    "journalImpactFactor": jif,
                    "articleInfluenceScore": ais,
                    # provenance extras (ignored by the parser, kept for audits)
                    "jifQuartileJcr": clean(cq.get("quartile")),
                    "jifRankJcr": clean(cq.get("jifRank")),
                    "jifPercentile": clean(cq.get("jifPercentile")),
                    "aisQuartileJcr": clean(cq.get("aisQuartile")),
                    "aisRankJcr": clean(cq.get("aisRank")),
                })
        for edition, items in sorted(items_by_edition.items()):
            out = OUT_DIR / f"journals-{edition}-year-{year}.json"
            out.write_text(json.dumps(items, ensure_ascii=False, indent=1), encoding="utf-8")
            print(f"year {year}: {edition} -> {len(items)} items -> {out.name}")
        print(f"year {year}: {rows} journals, {seen} (journal,category) placements")


if __name__ == "__main__":
    main()
