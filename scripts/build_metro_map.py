#!/usr/bin/env python3
"""Build line-only M1–M4 and H5–H9 map context from the official BKK GTFS ZIP."""
import argparse
import csv
import hashlib
import io
import json
from collections import defaultdict
from pathlib import Path
from zipfile import ZipFile

LINES = {"M1", "M2", "M3", "M4", "H5", "H6", "H7", "H8", "H9"}

def build(archive: Path, output: Path):
    with ZipFile(archive) as feed:
        def rows(name):
            return csv.DictReader(io.TextIOWrapper(feed.open(name), encoding="utf-8-sig"))
        routes = {r["route_id"]: r for r in rows("routes.txt")
                  if r["route_short_name"] in LINES and r["route_type"] in {"1", "109"}}
        assert {r["route_short_name"] for r in routes.values()} == LINES
        trips = [t for t in rows("trips.txt") if t["route_id"] in routes]
        shape_ids = {t["shape_id"] for t in trips}
        shapes = defaultdict(list)
        for row in rows("shapes.txt"):
            if row["shape_id"] in shape_ids:
                shapes[row["shape_id"]].append((int(row["shape_pt_sequence"]),
                    [round(float(row["shape_pt_lon"]), 6), round(float(row["shape_pt_lat"]), 6)]))
        features = []
        for route_id, route in sorted(routes.items()):
            for direction in ("0", "1"):
                candidates = {t["shape_id"] for t in trips if t["route_id"] == route_id and t["direction_id"] == direction}
                assert candidates, (route_id, direction)
                shape = max(sorted(candidates), key=lambda key: len(shapes[key]))
                features.append({"type": "Feature", "properties": {"kind": "line", "line": route["route_short_name"]},
                    "geometry": {"type": "LineString", "coordinates": [p for _, p in sorted(shapes[shape])]}})
        result = {"type": "FeatureCollection", "source": "BKK GTFS",
            "sourceUrl": "https://go.bkk.hu/api/static/v1/public-gtfs/budapest_gtfs.zip",
            "licenseUrl": "https://bkk.hu/fejlesztoknek/", "sourceSha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
            "features": features}
        output.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n")
        print(f"Wrote {len(features)} line features to {output}: {output.stat().st_size} bytes")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    build(args.archive, args.output)
