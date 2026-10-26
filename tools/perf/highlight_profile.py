#!/usr/bin/env python3
"""Diagnostic release/R8 highlighting replay; emulator timings are not phone performance claims."""
import argparse, json, pathlib, re, subprocess, time, xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--label',required=True);p.add_argument('--out',required=True);p.add_argument('--iterations',type=int,default=5);p.add_argument('--line',default='762');args=p.parse_args()
out=pathlib.Path(args.out);out.mkdir(parents=True,exist_ok=True)
adb=pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb'
def call(*items):return subprocess.check_output([str(adb),'-s',args.serial,*items],stderr=subprocess.STDOUT)
def snap():
 call('shell','uiautomator','dump','/sdcard/bm-perf-ui.xml')
 return ET.fromstring(call('shell','cat','/sdcard/bm-perf-ui.xml'))
def xy(node):
 x,y,xx,yy=map(int,re.findall(r'\d+',node.get('bounds')));return (x+xx)//2,(y+yy)//2
def tap(node):
 x,y=xy(node);call('shell','input','tap',str(x),str(y))
def get(root,attr,value):return next(n for n in root.iter('node') if n.get(attr)==value)
def open_line():
 r=snap();tap(get(r,'content-desc','Megálló vagy járat keresése'))
 r=snap();tap(get(r,'text','Járművek'))
 r=snap();tap(next(n for n in r.iter('node') if n.get('class')=='android.widget.EditText'))
 call('shell','input','text',args.line);time.sleep(.4);call('shell','input','keyevent','4')
 r=snap();nodes=[n for n in r.iter('node') if n.get('text')==args.line and n.get('class')!='android.widget.EditText']
 if not nodes: raise RuntimeError('No line result: '+str([n.get('text') for n in r.iter('node') if n.get('text')]))
 tap(nodes[0]);r=snap()
 for _ in range(3):
  if not any(n.get('class')=='android.widget.ProgressBar' for n in r.iter('node')):break
  time.sleep(.5);r=snap()
 return r
print('Warm-up: '+args.line,flush=True)
r=open_line();tap(get(r,'content-desc','Vonalnézet bezárása'));snap()
config='''buffers { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources { config { name: "linux.ftrace" ftrace_config {
 ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_waking"
 atrace_categories: "gfx" atrace_categories: "view" atrace_categories: "wm"
 atrace_categories: "am" atrace_categories: "binder_driver" atrace_apps: "hu.app.bmenetrend"
} } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
duration_ms: 240000
'''
(out/'config.pbtxt').write_text(config)
trace='/data/misc/perfetto-traces/bm-'+args.label+'.pftrace'
result=subprocess.check_output([str(adb),'-s',args.serial,'shell','perfetto','--background-wait','--txt','-c','-','-o',trace],input=config.encode(),stderr=subprocess.STDOUT).decode()
print(result.strip(),flush=True)
trace_pid=re.findall(r'\b\d+\b',result.strip())[-1]
call('shell','dumpsys','gfxinfo','hu.app.bmenetrend','reset')
start=time.monotonic()
for i in range(args.iterations):
 r=open_line();time.sleep(.6)
 # Fixed gentle map pans exercise both retained highlights and native-map redraws.
 call('shell','input','swipe','500','600','620','650','400')
 call('shell','input','swipe','620','650','500','600','400')
 tap(get(r,'content-desc','Vonalnézet bezárása'));snap()
 print('Iteration',i+1,'elapsed',round(time.monotonic()-start,1),flush=True)
call('shell','kill','-INT',trace_pid)
(out/'gfxinfo.txt').write_bytes(call('shell','dumpsys','gfxinfo','hu.app.bmenetrend','framestats'))
call('pull',trace,str(out/'highlight.pftrace'))
(out/'device.txt').write_bytes(call('shell','getprop','ro.product.model')+call('shell','getprop','ro.build.version.sdk'))
(out/'run.json').write_text(json.dumps({'label':args.label,'line':args.line,'iterations':args.iterations,'seconds':time.monotonic()-start,'serial':args.serial,'variant':'release','r8':True,'compilation':'cmd package compile -f -m speed','device_kind':'emulator'},indent=2))
print('Saved',out,flush=True)
