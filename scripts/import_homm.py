#!/usr/bin/env python3
"""Reproducible HOMM 288/289 import from official PDFs and municipal map API.
Requires Python 3.10+ and pdfplumber. Never interpolate stop times or coordinates.
Run: python scripts/import_homm.py --source-dir research/homm
Without --source-dir, verified documents are downloaded to a temporary directory.
"""
import argparse
import hashlib
import json
import math
import re
import tempfile
import unicodedata
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PAGE_URL = "https://homm.hu/helyi-kozlekedes/"
SOURCES = {
    "school": ("https://homm.hu/www/menetrend-budaors/Közlekedési rend tanítási napokon.pdf", "c7fb61b7abbc6fbf1fc65b828870c8fe2d805dc3e6b2f03f4bdb05a505b0ff82"),
    "break": ("https://homm.hu/www/menetrend-budaors/Közlekedési rend tanítási szünetben.pdf", "792cc9db653af9c453ade424075568f55098ca5a2a6fac8028374e66d902d593"),
}
# This order is the four complete tables in each official PDF.
DIRECTIONS = [("288", 4, 39), ("288", 3, 20), ("289", 2, 26), ("289", 1, 18)]
# Reviewed duplicate municipal records: identical names and coordinates within 1 m.
# This is an explicit allowlist, never automatic clustering of nearby platforms.
ALIASES = {84: 9, 88: 76, 94: 6, 95: 7, 96: 8, 97: 10, 98: 11, 99: 12,
           100: 13, 101: 14, 102: 15, 45: 21, 47: 27, 58: 31, 59: 30,
           60: 29, 61: 28, 62: 26, 63: 32, 64: 33, 65: 34, 66: 35,
           67: 36, 68: 37, 69: 38}
CLOCK = re.compile(r"^(\d{1,2})[:.](\d{2})\*?$")


def canonical(record):
    return f"homm:{ALIASES.get(record['id'], record['id'])}"


def normal_name(value):
    value = "".join(c for c in unicodedata.normalize("NFD", value.lower()) if not unicodedata.combining(c))
    value = value.replace("(atsz.h.)", "").replace("vegallomas", "").replace("vegall.", "")
    value = re.sub(r"\bu\.", "utca", value)
    value = re.sub(r"[^a-z0-9 ]", "", value)
    value = " ".join(value.split())
    if "lakotelep" in value:
        return "budaorsi lakotelep"
    if value == "budaors felsohatar utca":
        return "felsohatar utca"
    return value


def minute(value):
    match = CLOCK.fullmatch(value)
    assert match, f"Unexpected timetable cell: {value!r}"
    h, m = map(int, match.groups())
    assert 0 <= h <= 23 and 0 <= m < 60
    return h * 60 + m


def extract(path):
    import pdfplumber
    with pdfplumber.open(path) as document:
        tables = [table for page in document.pages for table in page.extract_tables()]
    assert len(tables) == 4, "Changed PDF layout"
    result = []
    for table in tables:
        rows = [r for r in table[2:] if r[0] and any(CLOCK.fullmatch(c or "") for c in r[1:])
                and not re.fullmatch(r"28[89]\s*-\s*r[ae]", r[0])]
        assert all(len(r) == len(table[0]) for r in rows)
        result.append(rows)
    return result


def download(target):
    for name, (url, _) in SOURCES.items():
        urllib.request.urlretrieve(urllib.parse.quote(url, safe=":/"), target / f"{name}.pdf")
    for route, direction, _ in DIRECTIONS:
        url = f"https://budaors.hu/api/terkep/busz/megallok/{route}/{direction}"
        urllib.request.urlretrieve(url, target / f"stops-{route}-{direction}.json")


def build(source_dir, output, fetched_at):
    for name, (_, digest) in SOURCES.items():
        assert hashlib.sha256((source_dir / f"{name}.pdf").read_bytes()).hexdigest() == digest, f"Changed {name} PDF: re-review required"
    maps = [sorted(json.loads((source_dir / f"stops-{r}-{d}.json").read_text()), key=lambda s: s['order'])
            for r, d, _ in DIRECTIONS]
    records = {s['id']: s for group in maps for s in group}
    for alias, target in ALIASES.items():
        a, b = records[alias], records[target]
        assert a['megallo'] == b['megallo']
        assert math.hypot((float(a['lat']) - float(b['lat'])) * 111_000,
                          (float(a['lon']) - float(b['lon'])) * 75_000) < 1
    stops = {}
    for group, (number, _, count) in zip(maps, DIRECTIONS):
        assert len(group) == count
        for record in group:
            source = records[ALIASES.get(record['id'], record['id'])]
            sid = canonical(record)
            if sid not in stops:
                stops[sid] = dict(id=sid, name=source['megallo'], lat=float(source['lat']), lon=float(source['lon']), routeIds=[], providerId='homm')
            route = f"homm:{number}"
            if route not in stops[sid]['routeIds']:
                stops[sid]['routeIds'].append(route)
    routes = {number: dict(id=f"homm:{number}", number=number,
              name=f"Budaörsi lakótelep – {'Kamaraerdő, Iglói köz' if number == '288' else 'Ötvös utca'}",
              directions=[], providerId='homm') for number in ['288', '289']}
    directions = {}
    trips = []
    for filename, calendar in [('school', 'school_term'), ('break', 'school_break')]:
        for rows, group, (number, direction, count) in zip(extract(source_dir / f"{filename}.pdf"), maps, DIRECTIONS):
            assert len(rows) == count, (filename, number, direction, len(rows), count)
            assert all(normal_name(row[0]) == normal_name(record['megallo']) for row, record in zip(rows, group)), [
                (row[0], record['megallo']) for row, record in zip(rows, group) if normal_name(row[0]) != normal_name(record['megallo'])]
            for col in range(1, len(rows[0])):
                active = [i for i, row in enumerate(rows) if row[col]]
                assert active and active == list(range(active[0], count)), "Only continuous trips ending at the published terminal are supported"
                sequence = tuple(canonical(group[i]) for i in active)
                key = (number, direction, sequence)
                if key not in directions:
                    variant = f"homm:{number}:{direction}" + (f":from-{active[0]+1}" if active[0] else "")
                    directions[key] = variant
                    routes[number]['directions'].append(dict(id=variant,
                        name=f"{stops[sequence[0]]['name']} → {stops[sequence[-1]]['name']}", stopIds=list(sequence)))
                times = [dict(stopId=canonical(group[i]), minuteOfDay=minute(rows[i][col])) for i in active]
                assert all(a['minuteOfDay'] <= b['minuteOfDay'] for a,b in zip(times,times[1:])), (filename, number, direction, col)
                trips.append(dict(id=f"homm:{number}:{direction}:{calendar}:{col}", routeId=f"homm:{number}",
                                  directionId=directions[key], calendar=calendar, stopTimes=times))
    assert len(trips) == 149
    assert len(stops) == 78
    assert len(directions) == 5
    network = dict(schemaVersion=2, fetchedAt=fetched_at, source=PAGE_URL, serviceDate=None,
                   calendarLabel='school_term / school_break', providerServiceDates={'homm': None},
                   stops=sorted(stops.values(), key=lambda s: int(s['id'].split(':')[1])),
                   routes=list(routes.values()), trips=trips)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(network, ensure_ascii=False, indent=2) + '\n')
    print(f"Imported {len(stops)} stops, {len(routes)} routes, {len(directions)} variants, {len(trips)} trips to {output}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'app/src/main/assets/homm-network.json')
    parser.add_argument('--fetched-at', default='2026-10-22T15:00:00Z')
    args = parser.parse_args()
    if args.source_dir:
        build(args.source_dir, args.output, args.fetched_at)
    else:
        with tempfile.TemporaryDirectory(prefix='bmenetrend-homm-') as temp:
            source_dir = Path(temp)
            download(source_dir)
            build(source_dir, args.output, args.fetched_at)


if __name__ == '__main__':
    main()
