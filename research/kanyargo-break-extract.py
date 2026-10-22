"""Verified coordinate extraction of the operator's 2026-08-10 school-break PDF.
Run with bundled Python/pdfplumber. Source PDF rendered and visually reviewed.
Names/IDs come from official kanyargo-timetable.html and kanyargo-map.html.
"""
from pathlib import Path
import json, re
import pdfplumber
BASE=Path(__file__).resolve().parent
# Fixed row positions are properties of this versioned PDF, never inferred clock times.
ROWS=[
(76.52,'25','Levendula otthon'),(88.64,'2','Iskola utca'),(101.00,'3','Telki elágazás'),
(113.36,'4','Harangláb'),(125.72,'5','Kossuth utca'),(138.68,'7','Burgondia utca'),
(151.64,'45','Galagonya utca'),(164.00,'44','Rigó utca'),(176.36,'43','Hársfa utca'),
(189.56,'46','Mézeshegy'),(202.91,'8','Bánya utca'),(215.27,'9','Rét köz'),
(227.63,'10','Páskom utca'),(239.99,'11','Füzespatak utca'),(252.35,'12','Völgy utca'),
(264.71,'13','Nyírfa utca'),(277.07,'14','Játszótér'),(289.43,'15','Árpád utca'),
(301.79,'16','Petőfi Sándor utca'),(314.15,'19','Bocskai iskola'),(326.53,'47','Bocskai köz'),
(338.89,'2','Iskola utca'),(351.25,'36','Töki út'),(363.61,'23','Boglárka utca'),
(375.97,'24','Vízműtelep'),(388.33,'25','Levendula otthon'),(400.69,'26','Rendőrség'),
(413.05,'27','Torbágyi út 10. Vasútállomás felé'),(425.44,'28','Téglagyár Vasútállomás felé'),
(437.80,'42','Biatorbágy, Vendel tér'),(450.16,'29','Biatorbágy VÁ (érkezés)'),
(462.52,'30','Biatorbágy VÁ (indulás)'),(474.88,'42','Biatorbágy, Vendel tér'),
(487.24,'38','Téglagyár Páty felé'),(499.60,'37','Torbágyi út 10. Páty felé'),(511.96,'1','Községháza')]
CENTERS=[222,257,291,325,358,390,422,455,488,521,557,593]
LABELS=['1V','1V','1T','1MV','1MV','1A','1MV','1A','1MV','1A','1MV','1V']
# Blue continuation symbols vs grey terminal markers, verified on rendered PDF.
CONTINUES_NEXT_COLUMN={1,2,3,5,7,9,11}
with pdfplumber.open(BASE/'kanyargo-break.pdf') as pdf:
 page=pdf.pages[0]
 cells={}
 for char in page.chars:
  if char['text'] not in '0123456789:' or char['size']<8 or not 205<char['x0']<608 or not 74<char['top']<522:continue
  ri=min(range(len(ROWS)),key=lambda j:abs(ROWS[j][0]-char['top']))
  ci=min(range(len(CENTERS)),key=lambda j:abs(CENTERS[j]-(char['x0']+char['x1'])/2))
  assert abs(ROWS[ri][0]-char['top'])<1,(char,ri)
  cells.setdefault((ri,ci),[]).append(char)
 values={k:''.join(c['text'] for c in sorted(chars,key=lambda c:c['x0'])) for k,chars in cells.items()}
 assert all(re.fullmatch(r'\d{1,2}:\d{2}',v) for v in values.values())
 assert values[(0,4)]=='9:45',values.get((0,4)) # overprinted small grey route descriptor ignored by font-size
 columns=[];trips=[]
 for ci,label in enumerate(LABELS):
  times=[]
  for ri,(_,sid,name) in enumerate(ROWS):
   raw=values.get((ri,ci))
   if raw is None:continue
   h,m=map(int,raw.split(':'))
   times.append({'stopId':sid,'minuteOfDay':h*60+m,'stopName':name,'pdfRow':ri,'clock':raw})
  assert times and all(a['minuteOfDay']<=b['minuteOfDay'] for a,b in zip(times,times[1:]))
  columns.append({'column':ci+1,'routeLabel':label,'stopTimes':times})
  # Arrival (29) and departure (30) are different provider IDs. Split the wait,
  # preserving each as the endpoint/origin instead of manufacturing an arrival departure.
  station_arrival=next((i for i,x in enumerate(times) if x['stopId']=='29'),None)
  if station_arrival is not None:
   assert times[station_arrival+1]['stopId']=='30'
   segments=[('outbound',times[:station_arrival+1]),('inbound',times[station_arrival+1:])]
  else:segments=[('local',times)]
  for suffix,segment in segments:
   trips.append({'id':f'break-c{ci+1:02}-{suffix}','routeLabel':label,'column':ci+1,'segment':suffix,
                 'endpointKind':('STATION_ARRIVAL' if suffix=='outbound' else 'CONTINUES_NEXT_COLUMN' if ci+1 in CONTINUES_NEXT_COLUMN else 'TERMINUS'),
                 'stopTimes':[{'stopId':x['stopId'],'minuteOfDay':x['minuteOfDay']} for x in segment]})
 result={'source':'https://kanyargo.paty.hu/documents/menetrend-KanyarGO-2026-aug-10-tanszuneti.pdf',
  'validFrom':'2026-08-10','calendar':'tanítási szüneti munkanapokon, visszavonásig',
  'routeLabels':list(dict.fromkeys(LABELS)), 'columns':columns,'trips':trips,
  'notes':['Official stop ID 42 aggregates both Vendel tér directions; preserved rather than inferring empty ID53.',
  'Both Iskola utca occurrences use official ID2, preserving occurrence order.',
  'Station arrival29 is an endpoint; departure30 is an origin after the wait.',
  'No Vitamin-domb row or clock times exist in this PDF; no times are assigned to it.',
  'Column12 is labeled1V in the PDF but terminates at Vízműtelep18:02; label retained verbatim.',
  'PDF is a school-break timetable; do not combine with school-term trips on one service date.']}
 (BASE/'kanyargo-break-snapshot.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
 print(len(columns),'official columns;',len(trips),'segments;',len(set(t['stopId'] for x in trips for t in x['stopTimes'])),'stop IDs')
 for t in trips:print(t['id'],t['routeLabel'],len(t['stopTimes']),t['stopTimes'][0],t['stopTimes'][-1])
