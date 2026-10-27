#!/usr/bin/env python3
"""Write the two offline styles. All resources are local, including glyphs."""
import json
from pathlib import Path

OUT = Path(__file__).resolve().parents[2] / 'app/src/main/assets/map'

def style(dark):
    palette = dict(background='#172126', built='#253036', green='#203b32', water='#173f55',
        waterline='#2e6178', casing='#152025', street='#657178', major='#b6a580', text='#f0f2ed', halo='#172126', rail='#a69ba8') if dark else dict(
        background='#f2f0e9', built='#e8e4dc', green='#d4e1c8', water='#badce8',
        waterline='#8ebfcf', casing='#d4cbbd', street='#ffffff', major='#f1d49c', text='#30383b', halo='#ffffff', rail='#8e8794')
    layers = [{'id':'background','type':'background','paint':{'background-color':palette['background']}}]
    def layer(id, type, source, paint, **kwargs):
        layers.append(dict(id=id,type=type,source='offline-hungary',**{'source-layer':source},paint=paint,**kwargs))
    for kind in ['built','green']:
        layer('land-'+kind,'fill','land',{'fill-color':palette[kind]},filter=['==','kind',kind])
    layer('water','fill','water',{'fill-color':palette['water']})
    layer('waterways','line','waterways',{'line-color':palette['waterline'],'line-width':['interpolate',['linear'],['zoom'],8,0.7,15,3]})
    major=['in','kind','motorway','motorway_link','trunk','trunk_link','primary','primary_link','secondary','secondary_link','tertiary','tertiary_link']
    # Street outlines and centers use the same zoom scale; overzoom retains roads
    # at street level without making them huge at a bus-stop camera zoom of 19.
    width=['interpolate',['linear'],['zoom'],10,0.7,13,2,16,6,19,12]
    layer('road-casing','line','roads',{'line-color':palette['casing'],'line-width':['interpolate',['linear'],['zoom'],10,1.3,13,3,16,8,19,14]},layout={'line-cap':'round','line-join':'round'})
    layer('roads','line','roads',{'line-color':palette['street'],'line-width':width},layout={'line-cap':'round','line-join':'round'})
    layer('major-roads','line','roads',{'line-color':palette['major'],'line-width':width},filter=major,layout={'line-cap':'round','line-join':'round'})
    layer('railways','line','railways',{'line-color':palette['rail'],'line-width':['interpolate',['linear'],['zoom'],9,0.6,16,2],'line-dasharray':[3,2]})
    labelpaint={'text-color':palette['text'],'text-halo-color':palette['halo'],'text-halo-width':1.5}
    layer('road-labels','symbol','roads',labelpaint,minzoom=14,layout={
        'symbol-placement':'line','symbol-spacing':300,'text-field':'{name}','text-font':['Noto Sans Regular'],
        'text-size':12,'text-max-angle':35,'text-padding':4})
    layer('places','symbol','places',labelpaint,layout={'text-field':'{name}',
        'text-font':['Noto Sans Regular'],'text-size':['match',['get','kind'],'city',18,'town',16,'village',14,12],
        'text-padding':8,'text-max-width':9})
    return {'version':8,'name':'Hungary offline context'+(' dark' if dark else ''),
        'glyphs':'asset://map/offline/glyphs/{fontstack}/{range}.pbf',
        'sources':{'offline-hungary':{'type':'vector','url':'pmtiles://file://OFFLINE_BASEMAP',
            'attribution':'© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a> · <a href="https://opendatacommons.org/licenses/odbl/1-0/">ODbL 1.0</a> · Geofabrik · 2026-10-25'}},'layers':layers}

for dark in [False,True]:
    (OUT / ('offline-dark.json' if dark else 'offline.json')).write_text(json.dumps(style(dark),ensure_ascii=False,indent=2)+'\n')
