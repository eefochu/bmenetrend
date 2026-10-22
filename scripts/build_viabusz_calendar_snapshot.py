#!/usr/bin/env python3
"""Reproduce the reviewed ViaBusz school / school-break snapshot.

Needs Python 3 and Poppler's pdftotext. Downloads the official PDFs unless
--source-dir contains calendar-via-school.pdf and calendar-via-break.pdf.
Unknown source bytes, table structure or stop sequences fail closed.
"""

import argparse
import collections
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    "school_term": (
        "calendar-via-school.pdf",
        "https://viabusz.biatorbagy.hu/documents/viabusz-menetrend-tanitas-261001.pdf",
        "546466bd8d5a0afe0396015dc43252979995a7bce98346a9c9b063e63e65deb0",
        [18, 14, 9, 8],
    ),
    "school_break": (
        "calendar-via-break.pdf",
        "https://viabusz.biatorbagy.hu/documents/viabusz-menetrend-szunet-260901.pdf",
        "e18278f78baf63477af6737debf9352a5e3feecd869c354d5325299a0224a846",
        [13, 10, 7],
    ),
}


def normalized_name(name):
    name = name.casefold().replace("u.", "utca").replace("t.", "tere")
    name = re.sub(r"\s*- [12]$|\s*\(ellenirány\)|\s*\(?visszaút\)?", "", name)
    # These are source spelling differences, not guessed geographic merges.
    name = name.replace("pecató,", "pecató").replace("oportó utca", "oportó")
    return " ".join(name.split())


def extract_trips(text, calendar, routes, stops, counts):
    pages = [p for p in text.split("\f") if p.strip()]
    assert len(pages) == len(counts), (calendar, len(pages))
    result = []
    for page_number, (page, count) in enumerate(zip(pages, counts), 1):
        assert "munkanapokon" in page
        assert ("tanítási időszakban" if calendar == "school_term" else "tanítási szünetben") in page
        lines = page.splitlines()
        station_rows = [i for i, line in enumerate(lines) if line.startswith("Vasútállomás") and re.search(r"\d{1,2}:\d{2}", line)]
        assert len(station_rows) == 2
        first, last = station_rows
        labels = re.findall(r"\b(?:1P|[1-4])\*?\b", lines[first - 1])
        assert len(labels) == count, (calendar, page_number, labels)
        rows = []
        for line in lines[first:last + 1]:
            if not line.strip():
                continue
            token = re.search(r"\d{1,2}:\d{2}|\|", line)
            assert token is not None, line
            name = line[:token.start()].strip()
            cells = re.findall(r"\d{1,2}:\d{2}|\|", line[token.start():])
            assert len(cells) == count, (calendar, page_number, name, cells)
            rows.append((name, cells))
        for column, number in enumerate(labels):
            route = routes[number.rstrip("*")]
            direction = route["directions"][0]
            served = [(name, cells[column]) for name, cells in rows if cells[column] != "|"]
            assert len(served) == len(direction["stopIds"])
            times = []
            for stop_id, (pdf_name, value) in zip(direction["stopIds"], served):
                expected = normalized_name(stops[stop_id]["name"])
                actual = normalized_name(pdf_name)
                if number.rstrip("*") == "2" and stop_id == "66":
                    expected = "szentháromság tér"  # direction implied by route 2
                assert actual == expected, (calendar, number, stop_id, actual, expected)
                hour, minute = map(int, value.split(":"))
                times.append({"stopId": stop_id, "minuteOfDay": hour * 60 + minute})
            assert all(a["minuteOfDay"] <= b["minuteOfDay"] for a, b in zip(times, times[1:]))
            result.append({
                "id": f"viabusz:{calendar}:{route['id']}:{times[0]['minuteOfDay']}",
                "routeId": route["id"], "directionId": direction["id"],
                "calendar": calendar, "stopTimes": times,
            })
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "app/src/main/assets/viabusz-calendar-network.json")
    args = parser.parse_args()
    network = json.loads((ROOT / "app/src/main/assets/network.json").read_text(encoding="utf-8"))
    routes = {route["number"]: route for route in network["routes"]}
    stops = {stop["id"]: stop for stop in network["stops"]}
    trips = []
    with tempfile.TemporaryDirectory(prefix="viabusz-calendar-") as workdir:
        for calendar, (filename, url, fingerprint, counts) in SOURCES.items():
            pdf = args.source_dir / filename if args.source_dir else Path(workdir) / filename
            if not args.source_dir:
                pdf.write_bytes(urllib.request.urlopen(url, timeout=30).read())
            assert hashlib.sha256(pdf.read_bytes()).hexdigest() == fingerprint, f"Source changed: {url}"
            text = subprocess.check_output(["pdftotext", "-layout", str(pdf), "-"], text=True)
            trips.extend(extract_trips(text, calendar, routes, stops, counts))
    assert len(trips) == 79 and len({trip["id"] for trip in trips}) == 79
    network.update({
        "schemaVersion": 2, "fetchedAt": "2026-10-22T00:00:00Z",
        "source": "https://viabusz.biatorbagy.hu/", "serviceDate": None,
        "providerServiceDates": {"viabusz": None},
        "calendarLabel": "school_term / school_break (working days)", "trips": trips,
    })
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(network, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{len(stops)} stops, {len(routes)} routes, {len(trips)} trips")
    print(dict(collections.Counter((trip["calendar"], trip["routeId"]) for trip in trips)))


if __name__ == "__main__":
    main()
