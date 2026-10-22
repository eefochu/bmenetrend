"""Build provider-namespaced Android asset from the two reviewed PDF imports."""
from pathlib import Path
import json,hashlib
BASE=Path(__file__).parent
school=json.loads((BASE/'kanyargo-school-import.json').read_text())
breaks=json.loads((BASE/'kanyargo-break-snapshot.json').read_text())
trips=school['trips']+[dict(id='kanyargo:'+t['id'],routeNumber=t['routeLabel'],restrictedService=False,calendar='school_break',stopTimes=[dict(stopId='kanyargo:'+st['stopId'],minuteOfDay=st['minuteOfDay']) for st in t['stopTimes']]) for t in breaks['trips']]
stops={s['id']:s for s in school['stops']}
routes=[]
for label in ['1A','1D','1J','1M','1MT','1MV','1T','1V']:
 route_id='kanyargo:'+label
 directions={}
 for trip in [t for t in trips if t.get('routeNumber')==label]:
  ids=[s['stopId'] for s in trip['stopTimes']]
  fingerprint='|'.join(ids)+str(trip['restrictedService'])
  direction_id='kanyargo:'+hashlib.sha256(fingerprint.encode()).hexdigest()[:12]
  names=[stops[s]['name'] for s in ids]
  notable=[stops[s]['name'] for s in ids[1:-1] if s in ['kanyargo:46','kanyargo:36','kanyargo:25','kanyargo:19']]
  name=' → '.join(dict.fromkeys([names[0]]+notable))+' → '+names[-1]
  directions[direction_id]=dict(id=direction_id,name=name,stopIds=ids,restrictedService=trip['restrictedService'])
  trip.update(routeId=route_id,directionId=direction_id)
  for s in set(ids): stops[s]['routeIds'].append(route_id)
  trip.pop('routeNumber');trip.pop('restrictedService')
 routes.append(dict(id=route_id,number=label,name='KanyarGO '+label,directions=list(directions.values()),providerId='kanyargo'))
for s in stops.values():s['routeIds']=list(dict.fromkeys(s['routeIds']))
network=dict(schemaVersion=2,fetchedAt='2026-10-22T13:05:00Z',source='https://kanyargo.paty.hu/',serviceDate=None,calendarLabel='school_calendar',providerServiceDates={'kanyargo':None},stops=list(stops.values()),routes=routes,trips=trips)
path=BASE.parent/'app/src/main/assets/kanyargo-network.json'
path.write_text(json.dumps(network,ensure_ascii=False,indent=2))
(BASE.parent/'app/src/test/resources/kanyargo-network.json').write_text(path.read_text())
for name in ['kanyargo-2026-school.pdf','kanyargo-break.pdf']:
 print(name,hashlib.sha256((BASE/name).read_bytes()).hexdigest())
print('Network',len(stops),'stops',len(routes),'routes',sum(len(r['directions']) for r in routes),'directions',len(trips),'trips')
