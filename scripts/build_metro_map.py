#!/usr/bin/env python3
"""Build the persistent Budapest metro overview from the official BKK GTFS ZIP."""
import argparse
import csv
import hashlib
import io
import json
from collections import defaultdict
from pathlib import Path
from zipfile import ZipFile


def build(archive: Path, output: Path):
    with ZipFile(archive) as feed:
        def rows(name):
            return csv.DictReader(io.TextIOWrapper(feed.open(name), encoding="utf-8-sig"))

        routes = {r["route_id"]: r for r in rows("routes.txt") if r["route_type"] == "1"}
        assert {r["route_short_name"] for r in routes.values()} == {"M1", "M2", "M3", "M4"}
        trips = {t["trip_id"]: t for t in rows("trips.txt") if t["route_id"] in routes}
        shape_ids = {t["shape_id"] for t in trips.values()}
        shapes = defaultdict(list)
        for row in rows("shapes.txt"):
            if row["shape_id"] in shape_ids:
                shapes[row["shape_id"]].append((int(row["shape_pt_sequence"]),
                    [round(float(row["shape_pt_lon"]), 6), round(float(row["shape_pt_lat"]), 6)]))
        stop_lines = defaultdict(set)
        for row in rows("stop_times.txt"):
            if row["trip_id"] in trips:
                stop_lines[row["stop_id"]].add(routes[trips[row["trip_id"]]["route_id"]]["route_short_name"])
        stops = {r["stop_id"]: r for r in rows("stops.txt")}
        features = []
        for route_id, route in sorted(routes.items()):
            # Full-length shapes in both directions retain the real curves/river crossings.
            for direction in ("0", "1"):
                candidates = {t["shape_id"] for t in trips.values() if t["route_id"] == route_id and t["direction_id"] == direction}
                shape = max(sorted(candidates), key=lambda shape: len(shapes[shape]))
                coordinates = [point for _, point in sorted(shapes[shape])]
                features.append({"type": "Feature", "properties": {"kind": "line", "line": route["route_short_name"]},
                    "geometry": {"type": "LineString", "coordinates": coordinates}})
        groups = defaultdict(list)
        for stop_id in sorted(stop_lines):
            stop = stops[stop_id]
            groups[stop["parent_station"] or stop_id].append(stop_id)
        for station_id, platform_ids in sorted(groups.items()):
            station = stops[station_id]
            features.append({"type": "Feature", "properties": {
                "kind": "station", "name": station["stop_name"],
                "stop-ids": ["emma:BKK:" + stop_id for stop_id in platform_ids],
                "lines": sorted(set.union(*(stop_lines[stop_id] for stop_id in platform_ids)))},
                "geometry": {"type": "Point", "coordinates": [float(station["stop_lon"]), float(station["stop_lat"])]}})
        result = {"type": "FeatureCollection", "source": "BKK GTFS", "sourceUrl": "https://go.bkk.hu/api/static/v1/public-gtfs/budapest_gtfs.zip",
            "licenseUrl": "https://bkk.hu/fejlesztoknek/", "sourceSha256": hashlib.sha256(archive.read_bytes()).hexdigest(), "features": features}
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n")
        print(f"Wrote {len(features)} features ({len(groups)} stations) to {output}: {output.stat().st_size} bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    build(args.archive, args.output)
