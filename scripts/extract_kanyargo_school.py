"""Reproducible import of the operator's 2026-09-01 PDF (visually reviewed).
Run with bundled Python/pdfplumber. No nearest-time/route matching is used.
Columns are actual runs; terminal splits below follow bold terminal arrows in PDF.
"""
from pathlib import Path
import json,re,hashlib
import pdfplumber
from bs4 import BeautifulSoup
import argparse
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source-dir', type=Path, default=Path(__file__).resolve().parent/'raw', help='Directory containing the downloaded official PDFs and HTML')
ROOT=parser.parse_args().source_dir.resolve()
html=(ROOT/'kanyargo-map.html').read_text()
features=re.findall(r'parseFloat\(([\d.]+)\), parseFloat\(([\d.]+)\).*?name: \'([^\']+)\'',html,re.S)
points={}
for lon,lat,name in features: points.setdefault(name,[]).append((float(lat),float(lon)))
soup=BeautifulSoup(html,'html.parser')
names={x['id'].split('-')[-1]:x.parent.parent.select('th')[1].get_text(' ',strip=True) for x in soup.select('[id^=timeThSpan-]')}
# The official selector and map list duplicate platform names in the same order.
base_names={'2':'Iskola utca','27':'Torbágyi út 10.','28':'Téglagyár','36':'Töki út','37':'Torbágyi út 10','38':'Téglagyár','42':'Biatorbágy, Vendel tér'}
stops=[]
for id,name in names.items():
 base=base_names.get(id,name)
 choices=points[base]
 index=1 if id=='38' else 0
 lat,lon=choices[index]
 stops.append(dict(id='kanyargo:'+id,name=name,lat=lat,lon=lon,routeIds=[],providerId='kanyargo'))
# HTML uses the same Vendel-tér ID for both directions; retain provider IDs instead of guessing.
p=pdfplumber.open(ROOT/'kanyargo-2026-school.pdf').pages[0]
head=[w for w in p.extract_words() if w['text'].startswith('1') and 67<w['top']<69]
centers=[(w['x0']+w['x1'])/2 for w in head]
bounds=[142]+[(a+b)/2 for a,b in zip(centers,centers[1:])]+[961]
rows=[(85.61,'19'),(98.33,'2'),(111.05,'3'),(123.77,'4'),(136.49,'5'),(149.93,'7'),(163.25,'45'),(175.97,'44'),(188.69,'43'),(202.37,'46'),(216.05,'8'),(228.77,'9'),(241.49,'10'),(254.21,'11'),(266.96,'12'),(279.68,'13'),(292.4,'14'),(305.12,'15'),(317.84,'16'),(330.56,'19'),(343.28,'47'),(356.0,'2'),(368.72,'36'),(381.44,'23'),(394.16,'24'),(406.88,'25'),(419.6,'26'),(432.32,'27'),(444.43,'28'),(457.78,'42'),(470.5,'29'),(483.2,'30'),(494.8,'42'),(508.66,'38'),(533.5,'37'),(546.22,'1'),(571.62,'26'),(585.7,'48'),(598.42,'42'),(611.14,'50'),(623.89,'51'),(636.61,'49'),(649.33,'50'),(662.05,'30'),(674.77,'42'),(686.9,'1')]
# Explicit terminal/layover boundaries, visually verified against PDF arrows.
# Public/school sections always form separate directions; service eligibility is never hidden.
split_after={0:[370],1:[413],7:[473],8:[533],9:[617],22:[1009,1037]}
trips=[]
for col,h in enumerate(head):
 vals=[]
 for top,id in rows:
  txt=p.crop((bounds[col],top-1,bounds[col+1],top+9)).extract_text() or ''
  times=re.findall(r'(\d{1,2}):(\d{2})',txt)
  if len(times)>1: raise ValueError((col,id,txt))
  if times:
   hour,minute=map(int,times[0]); vals.append((id,hour*60+minute,top>560))
 groups=[]; group=[]
 for row in vals:
  if group and (row[2]!=group[-1][2] or group[-1][1] in split_after.get(col,[])):
   groups.append(group);group=[]
  group.append(row)
 if group: groups.append(group)
 for part,group in enumerate(groups):
  assert all(a[1]<=b[1] for a,b in zip(group,group[1:])),(col,group)
  assert len(group)>1,(col,group)
  trips.append(dict(id=f'kanyargo:school:{col+1}:{part+1}',routeNumber=h['text'],restrictedService=group[0][2],calendar='school_term',stopTimes=[dict(stopId='kanyargo:'+s,minuteOfDay=t) for s,t,_ in group]))
# Crop verification assertions for train arrivals and restricted school return.
assert any(t['stopTimes'][0]['minuteOfDay']==390 and t['stopTimes'][-1]['minuteOfDay']==397 for t in trips)
assert any(t['routeNumber']=='1T' and [s['minuteOfDay'] for s in t['stopTimes']]==[989,990,991] for t in trips)
out=dict(stops=stops,trips=trips,sourceSha256=hashlib.sha256((ROOT/'kanyargo-2026-school.pdf').read_bytes()).hexdigest())
(ROOT/'kanyargo-school-import.json').write_text(json.dumps(out,ensure_ascii=False,indent=2))
print('stops',len(stops),'trips',len(trips),'routes',sorted(set(t['routeNumber'] for t in trips)))
for t in trips: print(t['id'],t['routeNumber'],t['restrictedService'],len(t['stopTimes']),t['stopTimes'][0],t['stopTimes'][-1])
