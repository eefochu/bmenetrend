#!/usr/bin/env python3
"""Build a deliberately limited OSM context database for the offline transit map.

Requires osmium==4.3.2 and shapely==2.2.0. Input is a Geofabrik OSM PBF;
output is streaming GeoJSON for tippecanoe, never loaded by the Android app.
The generated database is ODbL 1.0 (OpenStreetMap contributors).
"""
import collections
import json
import pathlib
import sys
import osmium
from shapely.geometry import shape

ROAD_ZOOM = {
    'motorway': 5, 'motorway_link': 8, 'trunk': 6, 'trunk_link': 9,
    'primary': 7, 'primary_link': 10, 'secondary': 8, 'secondary_link': 11,
    'tertiary': 10, 'tertiary_link': 11, 'unclassified': 12, 'residential': 12,
    'living_street': 12, 'service': 12, 'pedestrian': 12, 'footway': 14, 'cycleway': 14,
}
PLACE_ZOOM = {'city': 5, 'town': 8, 'village': 10, 'hamlet': 12, 'suburb': 12, 'neighbourhood': 14}


def main():
    source, output = map(pathlib.Path, sys.argv[1:3])
    factory = osmium.geom.GeoJSONFactory()
    counts, ranges = collections.Counter(), set()
    with output.open('w') as stream:
        def emit(layer, geometry, props, zoom):
            name = props.get('name', '')
            ranges.update(ord(c) // 256 * 256 for c in name)
            stream.write(json.dumps({'type': 'Feature', 'properties': props,
                'geometry': geometry, 'tippecanoe': {'layer': layer, 'minzoom': zoom}},
                ensure_ascii=False, separators=(',', ':')) + '\n')
            counts[layer] += 1

        # with_areas resolves multipolygon water/landuse; geometry is simplified by
        # tippecanoe at each zoom, with full z12 geometry retained for overzooming.
        processor = osmium.FileProcessor(str(source)).with_locations('flex_mem').with_areas()
        for obj in processor:
            tags = obj.tags
            name = tags.get('name:hu') or tags.get('name') or ''
            if isinstance(obj, osmium.osm.Node):
                place = tags.get('place')
                if place in PLACE_ZOOM and name:
                    emit('places', {'type': 'Point', 'coordinates': [obj.lon, obj.lat]},
                        {'kind': place, 'name': name}, PLACE_ZOOM[place])
            elif isinstance(obj, osmium.osm.Way):
                highway, railway, waterway = tags.get('highway'), tags.get('railway'), tags.get('waterway')
                if highway not in ROAD_ZOOM and railway not in ('rail', 'light_rail', 'tram') and waterway not in ('river', 'canal'):
                    continue
                try:
                    geometry = json.loads(factory.create_linestring(obj))
                except (osmium.InvalidLocationError, RuntimeError):
                    continue
                if highway in ROAD_ZOOM:
                    props = {'kind': highway}
                    if name: props['name'] = name
                    if tags.get('ref'): props['ref'] = tags['ref']
                    emit('roads', geometry, props, ROAD_ZOOM[highway])
                elif railway:
                    emit('railways', geometry, {'kind': railway}, 9 if railway == 'rail' else 12)
                else:
                    emit('waterways', geometry, {'kind': waterway}, 8 if waterway == 'river' else 11)
            elif isinstance(obj, osmium.osm.Area):
                natural, landuse = tags.get('natural'), tags.get('landuse')
                is_water = natural == 'water' or landuse in ('reservoir', 'basin') or tags.get('waterway') == 'riverbank'
                green = natural in ('wood', 'scrub') or landuse in ('forest', 'grass', 'meadow', 'recreation_ground') or tags.get('leisure') == 'park'
                built = landuse in ('residential', 'industrial', 'commercial', 'retail')
                if not (is_water or green or built): continue
                try:
                    geometry = json.loads(factory.create_multipolygon(obj))
                    polygon = shape(geometry)
                    # Approximate square degrees cutoff: discard tiny ponds/patches.
                    if polygon.area < 0.000001: continue
                except (osmium.InvalidLocationError, RuntimeError, ValueError):
                    continue
                emit('water' if is_water else 'land', geometry,
                    {'kind': 'water' if is_water else 'green' if green else 'built'},
                    6 if is_water and polygon.area > 0.001 else 10 if polygon.area > 0.0001 else 12)
    output.with_suffix('.stats.json').write_text(json.dumps({'features': counts, 'glyph_ranges': sorted(ranges)}, indent=2))
    print(json.dumps(counts))

if __name__ == '__main__': main()
