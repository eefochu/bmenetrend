#!/usr/bin/env python3
"""Import the official route catalogue for the three towns; no timetable/GPS estimates."""
import json
import re
import urllib.request
from html.parser import HTMLParser
from pathlib import Path

BASE = 'https://www.mavcsoport.hu/mav-szemelyszallitas/belfoldi-utazas/'

class TownRoutes(HTMLParser):
    def __init__(self, towns):
        super().__init__()
        self.towns = towns
        self.town = None
        self.cell = None
        self.rows = []
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'details':
            self.town = attrs.get('data-filter') if attrs.get('data-filter') in self.towns else None
        if self.town and tag == 'tr':
            self.row = {'town': self.town}
        if self.town and tag == 'td':
            self.cell = attrs.get('class')
            self.row[self.cell] = ''
        if self.town and self.cell == 'route-name' and tag == 'a':
            self.row['url'] = 'https://www.mavcsoport.hu' + attrs['href']
    def handle_data(self, data):
        if self.town and self.cell:
            self.row[self.cell] += data
    def handle_endtag(self, tag):
        if tag == 'td':
            self.cell = None
        if tag == 'tr' and self.town and 'url' in self.row:
            self.rows.append(self.row)
        if tag == 'details':
            self.town = None

routes = {}
for letter, towns in [('B', {'biatorbágy', 'budaörs'}), ('P', {'páty'})]:
    parser = TownRoutes(towns)
    parser.feed(urllib.request.urlopen(BASE + 'telepulesek-szerinti-menetrendek/' + letter, timeout=30).read().decode())
    for row in parser.rows:
        labels = []
        for group in row['route-id'].split(','):
            ends = [int(x) for x in re.findall(r'\d+', group)]
            labels.extend(range(ends[0], ends[-1] + 1))
        for label in labels:
            route = routes.setdefault(str(label), {'number': str(label), 'name': row['route-name'].strip(), 'url': row['url'], 'domainCode': int(row['url'].rsplit('/', 1)[1]), 'towns': []})
            if row['town'] not in route['towns']:
                route['towns'].append(row['town'])
assert all(any(t in r['towns'] for r in routes.values()) for t in ['biatorbágy', 'páty', 'budaörs'])
target = Path(__file__).resolve().parents[1] / 'app/src/main/assets/volan-routes.json'
target.write_text(json.dumps({'source': BASE + 'telepulesek-szerinti-menetrendek', 'reviewedOn': '2026-10-22', 'routes': sorted(routes.values(), key=lambda r: int(r['number']))}, ensure_ascii=False, indent=2) + '\n')
print(f'{len(routes)} routes -> {target}')
