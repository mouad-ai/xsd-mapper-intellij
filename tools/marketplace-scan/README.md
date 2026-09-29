# Marketplace competitor scan

Research script, not shipped. Queries the public JetBrains Marketplace API (no auth) for plugins
that overlap with XSD Mapper and writes a competitor map.

```
python3 tools/marketplace-scan/scan.py
```

Needs Python 3.8+ (stdlib only) and network access to `plugins.jetbrains.com`.
Outputs, overwritten on each run:

- `out/competitors.csv`: one row per plugin, deduplicated by Marketplace id
- `out/competitors.md`: summary sorted by downloads, with visual-mapping / XSLT-generation flags

Endpoints and the keyword list are at the top of `scan.py`; the script prints which endpoints it
called. It waits 0.5 s before every request and backs off on 429/5xx.

The documented Marketplace APIs are XML feeds with no keyword search, so the script uses the JSON
API the Marketplace website itself calls (`/api/searchPlugins`, `/api/plugins/{id}`,
`/api/plugins/{id}/updates`). Their shapes were checked against live responses; they are
undocumented and could change.

Flags (`visual_mapping`, `xslt_generation`, `sample_generation`, `validation`) are regex
heuristics over each plugin's name and description. Treat them as a shortlist to check by hand.
