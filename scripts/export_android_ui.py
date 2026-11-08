"""Export actual retained Android layout tokens/art to web; no Java-to-JS claim."""
from pathlib import Path
import json
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
A='{http://schemas.android.com/apk/res/android}'
S='{http://www.w3.org/2000/svg}'
ET.register_namespace('',S[1:-1])
res=ROOT/'android-original/res'
out=ROOT/'web/dist/assets';out.mkdir(parents=True,exist_ok=True)
for source,name in [('image_auth_start_120','auth-pin'),('image_auth_onboarding_120','auth-welcome'),('icon_back_24','back'),('icon_clock_16','calendar'),('icon_pin_16','pin'),('icon_search_16','search'),('icon_exit_24','exit'),('privacy_policy_page_1','onboarding-1'),('privacy_policy_page_2','onboarding-2'),('privacy_policy_page_3','onboarding-3')]:
 vector=ET.parse(res/'drawable'/(source+'.xml')).getroot()
 svg=ET.Element(S+'svg',{'viewBox':'0 0 '+vector.get(A+'viewportWidth','24')+' '+vector.get(A+'viewportHeight','24')})
 for path in vector.iter('path'):
  attrs={'d':path.get(A+'pathData',''),'fill-rule':'evenodd' if path.get(A+'fillType')=='evenOdd' else 'nonzero'}
  for original,key in [('fillColor','fill'),('strokeColor','stroke'),('strokeWidth','stroke-width'),('fillAlpha','fill-opacity'),('strokeAlpha','stroke-opacity')]:
   value=path.get(A+original)
   if value:
    if key in ('fill','stroke'):
     if value.startswith('#') and len(value)==9:
      alpha=int(value[1:3],16)/255;value='#'+value[3:];attrs[key+'-opacity']=str(alpha)
     elif not value.startswith('#'):value='#2c333b'
    attrs[key]=value
  if name in ('calendar','pin'):
   if attrs.get('fill') not in (None,'none'):attrs['fill']='#4375c9'
   if 'stroke' in attrs:attrs['stroke']='#4375c9'
  elif name in ('search','back','exit'):
   if attrs.get('fill') not in (None,'none'):attrs['fill']='#2a333d'
   if 'stroke' in attrs:attrs['stroke']='#2a333d'
  if 'fill' not in attrs:attrs['fill']='none'
  ET.SubElement(svg,S+'path',attrs)
 ET.ElementTree(svg).write(out/(name+'.svg'),encoding='utf-8',xml_declaration=True)
# References remain machine-readable so future Android layout changes can be compared in build output.
layouts={}
for name in ('events_view','events_item_view','event_view','event_header_view','session_time_item_view'):
 path=res/'layout'/(name+'.xml')
 if path.exists():layouts[name]=[{k.split('}')[-1]:v for k,v in n.attrib.items() if k.startswith(A)} for n in ET.parse(path).iter()]
(ROOT/'web/dist/android-ui.json').write_text(json.dumps({'source':'android-original/res','layouts':layouts},ensure_ascii=False))
# Native-scene actions use the same paths as Android, rather than Unicode approximations.
for source,name in [('icon_back_24','back'),('icon_search_16','search'),('icon_plus_24','add'),('icon_settings_24','settings'),('icon_clock_16','clock'),('icon_calendar_16','calendar'),('icon_pin_16','pin'),('icon_time_24','time'),('icon_complete_16','complete'),('icon_close_24','close'),('icon_send_24','send')]:
 vector=ET.parse(res/'drawable'/(source+'.xml')).getroot()
 svg=ET.Element(S+'svg',{'viewBox':'0 0 '+vector.get(A+'viewportWidth','24')+' '+vector.get(A+'viewportHeight','24')})
 for path in vector.iter('path'):
  attrs={'d':path.get(A+'pathData',''),'fill-rule':'evenodd' if path.get(A+'fillType')=='evenOdd' else 'nonzero'}
  for original,key in [('fillColor','fill'),('strokeColor','stroke'),('strokeWidth','stroke-width'),('fillAlpha','fill-opacity'),('strokeAlpha','stroke-opacity')]:
   value=path.get(A+original)
   if value:
    if key in ('fill','stroke'):
     if value in ('#00000000','@android:color/transparent'):value='none'
     else:value='#356aff' if name in ('send','time') else '#2c333b'
    attrs[key]=value
  if 'fill' not in attrs:attrs['fill']='none'
  ET.SubElement(svg,S+'path',attrs)
 ET.ElementTree(svg).write(out/('native-'+name+'.svg'),encoding='utf-8',xml_declaration=True)
