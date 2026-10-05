#!/usr/bin/env python3
"""Competitor scan for XSD Mapper against the public JetBrains Marketplace API.

Stdlib only. Run from anywhere:

    python3 tools/marketplace-scan/scan.py

Writes out/competitors.csv and out/competitors.md next to this script.

Endpoints (JSON API used by plugins.jetbrains.com itself, no auth). The
documented Marketplace APIs (docs/marketplace/plugins-list.html and
plugin-details.html) only expose XML feeds keyed by IDE build or plugin id,
with no keyword search, so the JSON endpoints below were confirmed against
live responses instead:

    GET /api/searchPlugins?search=<kw>&max=<n>&offset=<n>
        -> {plugins: [{id, xmlId, name, link, downloads, pricingModel,
                       rating, vendor: {name}, tags, preview}], total}
    GET /api/plugins/<id>
        -> {id, name, description, downloads, pricingModel,
            vendor: {publicName, name}, tags, ...}
    GET /api/plugins/<id>/updates?page=1&size=1
        -> [{version, cdate (epoch ms, string), compatibleVersions:
             {IDEA: "...", PYCHARM: "...", ...}}]
"""

import csv
import html
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

BASE = "https://plugins.jetbrains.com"
SEARCH = "/api/searchPlugins"
DETAILS = "/api/plugins/{id}"
UPDATES = "/api/plugins/{id}/updates"

KEYWORDS = [
    "xslt", "xml mapping", "xsd", "xml schema", "schema mapping",
    "xml transform", "xquery", "wsdl", "data mapper", "ubl", "iso 20022",
]

PAGE_SIZE = 100
DELAY_S = 0.5  # pause between every HTTP call
OUT_DIR = Path(__file__).resolve().parent / "out"
USER_AGENT = "xsd-mapper-marketplace-scan/1.0 (research script)"

# Heuristic feature flags, matched against name + preview + description.
FLAGS = {
    "visual_mapping": [
        r"visual(ly)?\s+(data\s+)?map", r"graphical(ly)?\s+map", r"drag(\s|-)?and(\s|-)?drop",
        r"mapping (editor|canvas|designer|tool)", r"data mapper", r"xml mapper", r"schema mapper",
        r"map(ping)? (between|from) .{0,40}(schema|xsd|xml)",
    ],
    "xslt_generation": [
        r"generat\w*\s+(an?\s+)?xslt", r"xslt\s+generat", r"(produce|create)s?\s+(an?\s+)?xslt",
    ],
    "sample_generation": [
        r"(generat\w*|creat\w*)\s+(an?\s+)?(sample|example|instance|test)?\s*xml\s+(from|based on|for)\s+(an?\s+)?(xsd|schema)",
        r"xsd\s+to\s+xml", r"sample xml", r"xml instance",
    ],
    "validation": [
        r"validat\w*", r"schema check",
    ],
}

used_endpoints = {}


def get_json(path, params=None):
    url = BASE + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    template = BASE + re.sub(r"/\d+(/|$)", r"/{id}\1", path)
    used_endpoints[template] = used_endpoints.get(template, 0) + 1
    req = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": USER_AGENT})
    for attempt in range(4):
        time.sleep(DELAY_S * (2 ** attempt))
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.load(resp)
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 502, 503, 504) and attempt < 3:
                continue
            if e.code == 404:
                return None
            raise
        except urllib.error.URLError:
            if attempt < 3:
                continue
            raise


def search(keyword):
    found, offset, total = [], 0, None
    while True:
        data = get_json(SEARCH, {"search": keyword, "max": PAGE_SIZE, "offset": offset})
        plugins = (data or {}).get("plugins", [])
        total = (data or {}).get("total", 0)
        new = [p for p in plugins if p["id"] not in {f["id"] for f in found}]
        found.extend(new)
        offset += PAGE_SIZE
        # Stop when the page is short, we've reached total, or offset is ignored (no new ids).
        if len(plugins) < PAGE_SIZE or offset >= total or not new:
            break
    return found, total


def fmt_date(ms):
    if not ms:
        return ""
    return datetime.fromtimestamp(int(ms) / 1000, tz=timezone.utc).strftime("%Y-%m-%d")


def strip_html(s):
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", s or ""))).strip()


def flags_for(text):
    t = text.lower()
    return [name for name, pats in FLAGS.items() if any(re.search(p, t) for p in pats)]


def main():
    print(f"Scanning {BASE} for {len(KEYWORDS)} keywords (delay {DELAY_S}s between calls)")
    hits = {}  # id -> search record
    matched = {}  # id -> [keywords]
    for kw in KEYWORDS:
        results, total = search(kw)
        print(f"  {kw!r}: {len(results)} plugins (API total {total})")
        for p in results:
            hits.setdefault(p["id"], p)
            matched.setdefault(p["id"], []).append(kw)

    print(f"{len(hits)} unique plugins; fetching details and latest update")
    rows = []
    for i, (pid, s) in enumerate(sorted(hits.items()), 1):
        d = get_json(DETAILS.format(id=pid)) or {}
        ups = get_json(UPDATES.format(id=pid), {"page": 1, "size": 1}) or []
        up = ups[0] if ups else {}
        vendor = d.get("vendor") or {}
        text = " ".join([s.get("name", ""), s.get("preview", ""), strip_html(d.get("description"))])
        ides = sorted((up.get("compatibleVersions") or {}).keys())
        rows.append({
            "name": s.get("name", ""),
            "id": pid,
            "xml_id": s.get("xmlId", ""),
            "vendor": vendor.get("publicName") or (s.get("vendor") or {}).get("name", ""),
            "pricing": (d.get("pricingModel") or s.get("pricingModel") or "").lower(),
            "downloads": d.get("downloads", s.get("downloads", 0)),
            "rating": s.get("rating", ""),
            "last_update": fmt_date(up.get("cdate") or s.get("cdate")),
            "latest_version": up.get("version", ""),
            "compatible_ides": ";".join(ides),
            "flags": ";".join(flags_for(text)),
            "matched_keywords": ";".join(matched[pid]),
            "url": BASE + (s.get("link") or f"/plugin/{pid}"),
        })
        if i % 10 == 0:
            print(f"  {i}/{len(hits)}")

    rows.sort(key=lambda r: -int(r["downloads"] or 0))
    OUT_DIR.mkdir(exist_ok=True)
    write_csv(rows)
    write_md(rows)

    print("\nEndpoints used (calls):")
    for ep, n in used_endpoints.items():
        print(f"  {ep}  x{n}")
    print(f"\nWrote {OUT_DIR / 'competitors.csv'} and {OUT_DIR / 'competitors.md'} ({len(rows)} plugins)")


def write_csv(rows):
    with open(OUT_DIR / "competitors.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()) if rows else ["name"])
        w.writeheader()
        w.writerows(rows)


def md_cell(v):
    return str(v).replace("|", "\\|")


def write_md(rows):
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    flagged = [r for r in rows if "visual_mapping" in r["flags"] or "xslt_generation" in r["flags"]]
    lines = [
        "# JetBrains Marketplace competitor scan",
        "",
        f"Generated {now} by `tools/marketplace-scan/scan.py`. {len(rows)} unique plugins "
        f"across keywords: {', '.join(KEYWORDS)}.",
        "",
        "Flags are keyword heuristics over the listing text, so check the plugin page before relying on one.",
        "",
        "## Flagged: visual mapping or XSLT generation",
        "",
    ]
    if flagged:
        for r in flagged:
            lines.append(f"- **[{md_cell(r['name'])}]({r['url']})** ({r['pricing']}, "
                         f"{int(r['downloads']):,} downloads): {r['flags'].replace(';', ', ')}")
    else:
        lines.append("None found.")
    lines += [
        "",
        "## All plugins by downloads",
        "",
        "| Plugin | Vendor | Pricing | Downloads | Rating | Last update | Flags | Matched |",
        "|---|---|---|---:|---:|---|---|---|",
    ]
    for r in rows:
        lines.append(
            f"| [{md_cell(r['name'])}]({r['url']}) | {md_cell(r['vendor'])} | {r['pricing']} | "
            f"{int(r['downloads']):,} | {r['rating']} | {r['last_update']} | "
            f"{r['flags'].replace(';', ', ')} | {r['matched_keywords'].replace(';', ', ')} |"
        )
    lines.append("")
    (OUT_DIR / "competitors.md").write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    try:
        main()
    except urllib.error.URLError as e:
        sys.exit(f"Request to {BASE} failed: {e}. Check network/proxy access to plugins.jetbrains.com.")
