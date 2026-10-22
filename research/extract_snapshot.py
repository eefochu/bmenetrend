"""Extract the public ViaBusz HTML already downloaded into research/route-*.html."""
from bs4 import BeautifulSoup
from pathlib import Path
from datetime import datetime, timezone
import re, json
root=Path(__file__).resolve().parent.parent
stops={}; routes=[]; trips=[]
for route_id in range(10,15):
 html=(root/f'research/route-{route_id}.html').read_text()
 soup=BeautifulSoup(html,'html.parser')
 option=soup.select_one(f'#routeSelect option[value="{route_id}"]')
 label=option.get_text(' ',strip=True); number,name=label.split(' - ',1)
 rows=soup.select('#timeTableData tr[routeid]'); ordered=[]; names={}
 for row in rows:
  clock=row.select_one('.digitalClock');sid=clock['id'].removeprefix('bsClock');sn=row.select('td')[1].get_text(' ',strip=True)
  zoom=next(x['onclick'] for x in row.select('img[onclick]') if 'zoomMapToPoint' in x['onclick'])
  lon,lat=map(float,re.search(r'zoomMapToPoint\(([^)]*)\)',zoom)[1].split(',')[:2])
  if sid not in stops:stops[sid]={'id':sid,'name':sn,'lat':lat,'lon':lon,'routeIds':[]}
  if str(route_id) not in stops[sid]['routeIds']:stops[sid]['routeIds'].append(str(route_id))
  ordered.append(sid);names[sn]=sid
 direction=str(rows[0]['directionid'])
 routes.append({'id':str(route_id),'number':number,'name':name,'directions':[{'id':direction,'name':name,'stopIds':ordered}]})
 for popup in soup.select('div[id^="busStopPopupBoxMPST"]'):
  if popup.select_one('h3').get_text(strip=True)!=number:continue
  times=[]
  for row in popup.select('tr'):
   cells=row.select('td')
   if len(cells)!=2:continue
   name=cells[0].get_text(' ',strip=True);time=cells[1].get_text(strip=True)
   if name not in names:raise RuntimeError(f'Unknown stop {name}')
   h,m=map(int,time.split(':'));times.append({'stopId':names[name],'minuteOfDay':h*60+m})
  assert [t['stopId'] for t in times]==ordered,(number,popup['id'])
  trips.append({'id':popup['id'].removeprefix('busStopPopupBoxMPST'),'routeId':str(route_id),'directionId':direction,'stopTimes':times})
 # Small, relevant real-world HTML fixtures: all route rows and this route's trip popups.
 fixture='<html><body>'+str(soup.select_one('#routeSelect'))+str(soup.select_one('#BusStopsDiv'))
 fixture+=''.join(str(p) for p in soup.select('div[id^="busStopPopupBoxMPST"]') if p.select_one('h3').get_text(strip=True)==number)
 (root/f'app/src/test/resources/route-{route_id}.html').write_text(fixture+'</body></html>')
network={'schemaVersion':1,'fetchedAt':datetime.now(timezone.utc).isoformat().replace('+00:00','Z'),'source':'Bundled official ViaBusz snapshot (2026-10-22)','serviceDate':'2026-10-22','calendarLabel':'Munkanapokon','stops':list(stops.values()),'routes':routes,'trips':trips}
(root/'app/src/main/assets/network.json').write_text(json.dumps(network,ensure_ascii=False,separators=(',',':')))
print(f'{len(stops)} stops, {len(routes)} routes, {len(trips)} trips')
