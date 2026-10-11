#!/usr/bin/env python3
"""Read-only screen witnesses plus actions in this disposable catalog fixture only."""
import argparse, hashlib, json, re, subprocess, time, urllib.request
from pathlib import Path
from fresh_android_xml import read_ui_xml
from common_goods_ui import CommonGoodsUi
from datetime import datetime, timezone
import xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--serial',required=True);p.add_argument('--output',type=Path,required=True)
a=p.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
for helper_name in ('common_goods_ui.py', 'fresh_android_xml.py'):
    helper = Path(__file__).with_name(helper_name)
    if helper.is_file():
        (out / helper_name).write_bytes(helper.read_bytes())
(out / 'tested-host_journeys.py').write_bytes(Path(__file__).read_bytes())
commands=[];package='staff.android.network';base='http://127.0.0.1:8787/'
result={'date':datetime.now(timezone.utc).isoformat(),'serial':a.serial,'passed':False,'scenarios':[]}
def adb(*args,binary=False):
 r=subprocess.run([a.adb,'-s',a.serial,*map(str,args)],capture_output=True,timeout=15)
 commands.append({'args':args,'exit':r.returncode});r.check_returncode()
 return r.stdout if binary else r.stdout.decode().strip()
def control(mode):
 with urllib.request.urlopen(base+'control?mode='+mode,timeout=5) as r: assert r.status==200
def tree():
    content = read_ui_xml(adb, '/sdcard/chapter11-host.xml', {package})
    return ET.fromstring(content), content

ui = CommonGoodsUi(lambda: tree()[1], lambda *parts: adb(*parts, binary=True), out)
def find(root,text):return next((n for n in root.iter('node') if n.get('text')==text),None)
def expect(text):
 end=time.monotonic()+20
 while time.monotonic()<end:
  n=ui.text(text)
  if n is not None:return n
 raise AssertionError('Missing visible text: '+text)
def tap(text):ui.tap(text)
def capture(name):
 frames=ui.observe(name)
 texts=[n.get('text') for _,content in frames for n in ET.fromstring(content).iter('node') if n.get('text')]
 return {'capture':name,'viewports':[label for label,_ in frames],'texts':texts}

try:
 control('reset')
 adb('shell','am','force-stop',package)
 adb('shell','am','start','-W','-n',package+'/.MainActivity','-f','0x10008000')
 expect('Refresh to load products.');capture('01-ready')
 tap('Refresh');expect('Camera · USD 1299.00');before=capture('02-accepted')
 control('invalid');tap('Refresh');expect('Invalid response: Invalid price: camera')
 expect('Camera · USD 1299.00');after=capture('03-invalid-retains')
 result['scenarios'].append({'name':'invalid-refresh-retains-accepted-page','before':before,'after':after})
 tap('Refresh');expect('Camera · USD 1299.00');capture('04-recovered')
 tap('Load more');expect('Tripod · USD 75.00');more=capture('05-next-page')
 tap('Use account b');expect('Camera · USD 899.00');switched=capture('06-account-b')
 assert 'Tripod · USD 75.00' not in switched['texts']
 result['scenarios'].append({'name':'next-page-then-distinct-account-prices','more':more,'switched':switched})
 result['device']={key:adb('shell','getprop',key) for key in ['ro.product.model','ro.build.version.release','ro.build.version.sdk','ro.build.fingerprint']}
 result['passed']=True
except Exception as error:
 result['error']=repr(error)
 raise
finally:
 diagnostics=[]
 if not result['passed']:
  # Preserve the failure viewport before attempting any recovery or scrolling.
  try:
   failure_xml=read_ui_xml(adb,'/sdcard/chapter11-failure.xml',
       {package,'android','com.android.systemui','com.android.permissioncontroller',
        'com.google.android.permissioncontroller'},attempts=2)
   (out/'failure-viewport.xml').write_text(failure_xml)
  except Exception as error:
   diagnostics.append({'artifact':'failure-viewport.xml','error':repr(error)})
  try:
   (out/'failure-viewport.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
  except Exception as error:
   diagnostics.append({'artifact':'failure-viewport.png','error':repr(error)})
 try:
  with urllib.request.urlopen(base+'history',timeout=5) as reply:
   (out/'http-history.txt').write_bytes(reply.read())
 except Exception as error:
  diagnostics.append({'artifact':'http-history.txt','error':repr(error)})
 result['diagnostic_errors']=diagnostics
 result['commands']=commands
 result['artifacts_sha256']={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in out.iterdir() if f.is_file() and f.name!='results.json'}
 (out/'results.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'passed':result['passed'],'scenarios':len(result['scenarios'])}))
