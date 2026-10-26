#!/usr/bin/env python3
"""Import official GPX roads, joining ordered timetable stops along those roads.
No inferred vehicle assignments are stored. Stdlib only; rerun after reviewing new timetables.
"""
import concurrent.futures
import hashlib
import heapq
import json
import math
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NUMBERS = ['1', '1P', '2', '3', '4']

def xy(p):
    return p[1] * 75100, p[0] * 111195

def distance(a, b):
    ax, ay = xy(a); bx, by = xy(b)
    return math.hypot(ax-bx, ay-by)

def project(p, a, b):
    px, py = xy(p); ax, ay = xy(a); bx, by = xy(b)
    dx, dy = bx-ax, by-ay
    t = max(0, min(1, ((px-ax)*dx+(py-ay)*dy)/(dx*dx+dy*dy))) if dx*dx+dy*dy else 0
    q = (a[0]+t*(b[0]-a[0]), a[1]+t*(b[1]-a[1]))
    return distance(p, q), q

def fetch(number):
    url = f'https://viabusz.biatorbagy.hu/gpx/viabusz-{number}.gpx'
    body = urllib.request.urlopen(url, timeout=20).read()
    root = ET.fromstring(body)
    # Keep track segments separate: never draw artificial lines across a GPX gap.
    tracks = []
    for segment in root.iter():
        if segment.tag.split('}')[-1] not in ('trkseg', 'rte'): continue
        points = [(float(p.attrib['lat']), float(p.attrib['lon'])) for p in segment
                  if p.tag.split('}')[-1] in ('trkpt', 'rtept')]
        if len(points) > 1: tracks.append(points)
    if not tracks: raise ValueError(f'No route geometry: {url}')
    return number, tracks, {'url': url, 'sha256': hashlib.sha256(body).hexdigest()}

def path_segments(tracks, stops):
    edges = [(a, b) for track in tracks for a, b in zip(track, track[1:])]
    nodes = []
    def node(p):
        for i, q in enumerate(nodes):
            if distance(p, q) < 4: return i
        nodes.append(p)
        return len(nodes)-1
    for a,b in edges: node(a); node(b)
    anchors = []
    for stop in stops:
        d, q = min(project(stop, a, b) for a,b in edges)
        if d > 150: raise ValueError(f'Stop too far from GPX: {stop}, {d:.0f}m')
        anchors.append(node(q))
    graph = {i: {} for i in range(len(nodes))}
    # Split at snapped stops and shared GPX junctions, tolerating small survey offsets.
    for a,b in edges:
        points = sorted((distance(a, p), i) for i,p in enumerate(nodes) if project(p,a,b)[0] < 5)
        for (_,i),(_,j) in zip(points,points[1:]):
            cost = distance(nodes[i],nodes[j])
            graph[i][j] = cost; graph[j][i] = cost
    # GPX loops can end a few metres short of their first fix (line 1: ~10m).
    # Join only survey-scale gaps; never bridge missing streets or whole track sections.
    for i,a in enumerate(nodes):
        for j in range(i+1,len(nodes)):
            gap=distance(a,nodes[j])
            if gap < 15:
                graph[i][j]=gap; graph[j][i]=gap
    paths = []
    for start,end in zip(anchors,anchors[1:]):
        queue = [(0,start)]; costs = {start:0}; parent = {}
        while queue:
            cost,current = heapq.heappop(queue)
            if cost != costs[current]: continue
            if current == end: break
            for nxt,weight in graph[current].items():
                new = cost+weight
                if new < costs.get(nxt,float('inf')):
                    costs[nxt]=new; parent[nxt]=current; heapq.heappush(queue,(new,nxt))
        if end not in costs: raise ValueError(f'Disconnected GPX: {start} to {end}')
        path = [end]
        while path[-1] != start: path.append(parent[path[-1]])
        points = [nodes[i] for i in reversed(path)]
        if len(points) < 2: raise ValueError('Collapsed stop segment')
        paths.append([[round(lat,6),round(lon,6)] for lat,lon in points])
    return paths

def main():
    network = json.loads((ROOT/'app/src/main/assets/viabusz-calendar-network.json').read_text())
    stops = {s['id']:(s['lat'],s['lon']) for s in network['stops']}
    with concurrent.futures.ThreadPoolExecutor(5) as pool: sources = {n:(tracks,meta) for n,tracks,meta in pool.map(fetch,NUMBERS)}
    routes=[]
    for route in network['routes']:
        tracks=list(sources[route['number']][0])
        if route['number']=='1P': tracks += sources['1'][0]
        for direction in route['directions']:
            ids=direction['stopIds']
            segments=path_segments(tracks,[stops[s] for s in ids])
            routes.append({'routeId':route['id'],'directionId':direction['id'],'stopIds':ids,'segments':segments})
            print(route['number'],len(segments),'stop-to-stop road paths')
    result={'schemaVersion':1,'sources':[sources[n][1] for n in NUMBERS],'routes':routes}
    (ROOT/'app/src/main/assets/viabusz-route-geometry.json').write_text(json.dumps(result,separators=(',',':'))+'\n')

if __name__=='__main__': main()
