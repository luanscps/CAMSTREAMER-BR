'use strict';

var ISO_LIST=[50,81,112,143,174,205,236,267,298,329,360,391,422,453,484,515,546,577,608,639,670,701,732,763,794,825,856,887,918,949,980,1011,1042,1073,1104,1135,1166,1197,1228,1259,1290,1321,1352,1383,1414,1445,1476,1507,1538,1569,1600,1631,1662,1693,1724,1755,1786,1817,1848,1879,1910,1941,1972,2003,2034,2065,2096,2127,2158,2189,2220,2251,2282,2313,2344,2375,2406,2437,2468,2499,2530,2561,2592,2623,2654,2685,2716,2747,2778,2809,2840,2871,2902,2933,2964,2995,3026,3057,3088,3119,3150,3200];
var SHUTTER_STOPS=['1/24','1/30','1/50','1/60','1/100','1/250','1/500','1/1000','1/2000','1/4000','1/10000'];
var FRAME_STOPS=['1/15','1/24','1/30','1/60'];
var FPS_CANDIDATES=[15,24,30,60,120,240];

var _caps=null;
var _currentCamId='0';
var _isManual=false;
var _rggbEnabled=false;
var _toastTimer;
var _pollFail=0;
var _pollCtrl=null;
var _isStreaming=false;

var _brT,_zT,_fT,_iT,_eT,_shT,_frT;
var _rgT_R,_rgT_Gr,_rgT_Gb,_rgT_B;

function showToast(msg,isErr){
  var t=document.getElementById('toast');
  t.textContent=msg;t.className=isErr?'err':'ok';t.classList.add('show');
  clearTimeout(_toastTimer);
  _toastTimer=setTimeout(function(){t.classList.remove('show');},1800);
}
function feedback(btn,ok){
  if(!btn)return;
  var cls=ok?'fb-ok':'fb-err';
  btn.classList.remove('fb-ok','fb-err');void btn.offsetWidth;
  btn.classList.add(cls);setTimeout(function(){btn.classList.remove(cls);},500);
}
function markActive(attr,val){
  var els=document.querySelectorAll('['+attr+']');
  for(var i=0;i<els.length;i++){
    els[i].classList.toggle('active',els[i].getAttribute(attr)===String(val));
  }
}
function showCard(id,show){
  var el=document.getElementById(id);if(!el)return;
  show?el.classList.remove('hidden'):el.classList.add('hidden');
}
function setText(id,val){
  var el=document.getElementById(id);
  if(el)el.textContent=(val!==undefined&&val!==null&&val!=='')?val:'-';
}
function setHtml(id,val){
  var el=document.getElementById(id);
  if(el)el.innerHTML=val||'';
}
function boolText(v){ return v ? 'Sim' : 'Não'; }
function fmtFloat(v,d){
  if(v===undefined||v===null||isNaN(v))return '-';
  return Number(v).toFixed(d===undefined?2:d);
}
function fmtList(arr,suffix){
  if(!arr||!arr.length)return '-';
  return arr.map(function(v){return typeof v==='number' ? fmtFloat(v,1)+(suffix||'') : String(v);}).join(', ');
}
function fmtRange(range,suffix){
  if(!range||range.length<2)return '-';
  return range[0]+' → '+range[1]+(suffix||'');
}
function buildChips(id,items){
  if(!items||!items.length){ setHtml(id,'<span class="chip">-</span>'); return; }
  setHtml(id,items.map(function(v){ return '<span class="chip">'+v+'</span>'; }).join(''));
}
function formatShutter(ns){
  if(!ns||ns<=0)return '-';
  var s=ns/1e9;
  if(s>=1)return s.toFixed(2)+'s';
  return '1/'+Math.round(1/s)+'s';
}
function resolutionLabel(res){
  if(!res)return '-';
  var p=parseResolution(res);
  if(!p)return res;
  if(p.w>=7680||p.h>=4320)return '8K';
  if(p.w>=3840||p.h>=2160)return '4K';
  if(p.w>=2560||p.h>=1440)return '2K';
  if(p.w>=1920||p.h>=1080)return '1080p';
  if(p.w>=1280||p.h>=720)return '720p';
  return res;
}
function parseResolution(res){
  var parts=String(res||'').split('x');
  if(parts.length!==2)return null;
  var w=parseInt(parts[0],10), h=parseInt(parts[1],10);
  if(!w||!h)return null;
  return {raw:res,w:w,h:h,pixels:w*h};
}
function normalizeRange(range){
  if(!range)return null;
  if(Array.isArray(range)&&range.length>=2)return {min:parseInt(range[0],10),max:parseInt(range[1],10)};
  if(typeof range==='object'){
    if(range.min!==undefined&&range.max!==undefined)return {min:parseInt(range.min,10),max:parseInt(range.max,10)};
    if(range.lower!==undefined&&range.upper!==undefined)return {min:parseInt(range.lower,10),max:parseInt(range.upper,10)};
  }
  return null;
}
function getCap(camId){
  if(!_caps)return null;
  for(var i=0;i<_caps.length;i++){
    if(String(_caps[i].camera_id)===String(camId))return _caps[i];
  }
  return null;
}

function sendControl(data,btn,msg){
  return fetch('/api/control',{
    method:'POST',
    headers:{'Content-Type':'application/json'},
    body:JSON.stringify(data)
  })
    .then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();})
    .then(function(resp){feedback(btn,true);if(msg)showToast(msg,false);return resp;})
    .catch(function(e){feedback(btn,false);showToast('ERR: '+e.message,true);throw e;});
}

function applyRtmpUrl(btn){
  var url=document.getElementById('rtmp-input').value.trim();
  if(!url)return;
  sendControl({rtmpUrl:url},btn,'RTMP atualizado');
}
function streamAction(action,btn){
  var msgs={start:'Stream iniciado',restart:'Stream reiniciado',stop:'Stream parado'};
  return sendControl({streamAction:action},btn,msgs[action]||action);
}

function updateManualUI(isManual){
  if(_isManual===isManual)return;
  _isManual=isManual;
  var badge=document.getElementById('badge-manual');
  if(badge)badge.style.display=isManual?'flex':'none';
  ['card-iso','card-shutter','card-frame'].forEach(function(id){
    var el=document.getElementById(id);if(!el)return;
    isManual?el.classList.add('manual-active'):el.classList.remove('manual-active');
  });
  showCard('card-shutter',isManual);
  showCard('card-frame',isManual);
  var evSlider=document.getElementById('ev');
  var evHint=document.getElementById('ev-hint');
  var evPresets=document.getElementById('ev-presets');
  if(evSlider)evSlider.disabled=isManual;
  if(evHint)isManual?evHint.classList.add('show'):evHint.classList.remove('show');
  if(evPresets){
    var btns=evPresets.querySelectorAll('button');
    for(var i=0;i<btns.length;i++)btns[i].disabled=isManual;
  }
}
function updateOISCapability(hasOIS){
  var chk=document.getElementById('toggle-ois');
  var hint=document.getElementById('ois-hint');
  if(chk)chk.disabled=!hasOIS;
  if(hint)hint.textContent=hasOIS?'':'(sem suporte nesta câmera)';
}

function buildCameraButtons(cameras,currentId){
  var container=document.getElementById('btngroup-camera');
  if(!container)return;
  var html='';
  for(var i=0;i<cameras.length;i++){
    var c=cameras[i];
    if(c.is_depth)continue;
    var lbl=c.name||('Cam '+c.camera_id);
    var active=(String(c.camera_id)===String(currentId));
    html+='<button data-cam="'+c.camera_id+'"'+(active?' class="active"':'')+
      ' onclick="switchCamera(\''+c.camera_id+'\',this)">'+lbl+'</button>';
  }
  container.innerHTML=html||'<span style="color:var(--muted)">Nenhuma câmera disponível</span>';
}
function switchCamera(camId,btn){
  _currentCamId=camId;
  sendControl({camera:camId},btn,'Câmera '+camId);
  markActive('data-cam',camId);
  applyCameraCapabilities(camId);
}

function buildResolutionButtons(cap,currentRes,currentFps){
  var container=document.getElementById('btngroup-resolution');
  if(!container)return;

  var map=cap.resolution_fps_map||{};
  var resolutions=(cap.available_resolutions||[]).slice();
  if(!resolutions.length){
    container.innerHTML='<span style="color:var(--muted)">Sem resoluções</span>';
    return;
  }

  var html='';
  for(var i=0;i<resolutions.length;i++){
    var r=resolutions[i];
    var fpsList=(map[r]||[]).slice().sort(function(a,b){return a-b;});
    var best=currentFps&&fpsList.indexOf(currentFps)!==-1?currentFps:(fpsList.length?fpsList[fpsList.length-1]:null);
    var active=r===currentRes;
    var label=resolutionLabel(r)+(best?' · '+best+'fps':'');
    html+='<button data-res="'+r+'"'+(active?' class="active"':'')+
      ' onclick="setResolutionProfile(\''+r+'\','+(best||'null')+',this)">'+label+'</button>';
  }
  container.innerHTML=html;
}
function setResolutionProfile(res,fps,btn){
  var payload={resolution:res};
  if(fps)payload.fps=fps;
  var wasStreaming=_isStreaming;
  return sendControl(payload,btn,resolutionLabel(res)+(fps?' · '+fps+'fps':''))
    .then(function(){
      markActive('data-res',res);
      if(wasStreaming)return streamAction('restart',btn);
    });
}

function updateZoom(v){
  clearTimeout(_zT);
  _zT=setTimeout(function(){sendControl({zoom:parseFloat(v)},null,'Zoom');},250);
  var slider=parseFloat(v);
  document.getElementById('zoom-val').textContent=slider===0?'1x':(Math.round(slider*100)/100)+'x';
}
function setZoomPreset(v){
  document.getElementById('zoom').value=v;
  updateZoom(v);
}

function buildFocusModeButtons(modes,currentMode){
  var container=document.getElementById('btngroup-focusmode');
  if(!container)return;
  var labels={auto:'AF Auto',macro:'Macro','continuous-video':'AF Contínuo','continuous-picture':'AF Foto',edof:'EDOF',off:'Manual'};
  var html='';
  for(var i=0;i<modes.length;i++){
    var m=modes[i];
    var active=(m===currentMode);
    html+='<button data-af="'+m+'"'+(active?' class="active"':'')+
      ' onclick="setFocusMode(\''+m+'\',this)">'+(labels[m]||m)+'</button>';
  }
  container.innerHTML=html;
}
function setFocusMode(mode,btn){sendControl({focusMode:mode},btn,mode);markActive('data-af',mode);}
function triggerAF(btn){sendControl({triggerAF:true},btn,'AF acionado');}

function buildWBButtons(modes,currentMode){
  var container=document.getElementById('btngroup-wb');
  if(!container)return;
  var labels={auto:'Auto',cloudy:'Nublado',daylight:'Sol',fluorescent:'Fluorescente',incandescent:'Incandescente',shade:'Sombra',twilight:'Crepúsculo',warm_fluorescent:'Fluoresc. Quente',off:'Manual'};
  var html='';
  for(var i=0;i<modes.length;i++){
    var m=modes[i];
    if(m==='off')continue;
    var active=(m===currentMode);
    html+='<button data-wb="'+m+'"'+(active?' class="active"':'')+
      ' onclick="setWBMode(\''+m+'\',this)">'+(labels[m]||m)+'</button>';
  }
  container.innerHTML=html;
}
function setWBMode(mode,btn){sendControl({wb:mode},btn,mode);markActive('data-wb',mode);}
function toggleAWBLock(el){sendControl({awbLock:el.checked},null,el.checked?'AWB travado':'AWB livre');}

function updateFocus(v){
  clearTimeout(_fT);
  var fv=parseFloat(v);
  _fT=setTimeout(function(){sendControl({focus:fv},null,'Foco');},200);
  document.getElementById('focus-val').textContent=fv===0?'Auto':fv.toFixed(1)+'D';
}
function updateISO(v){
  clearTimeout(_iT);
  var iso=ISO_LIST[Math.min(parseInt(v,10),ISO_LIST.length-1)];
  _iT=setTimeout(function(){sendControl({iso:iso},null,'ISO');},150);
  document.getElementById('iso-val').textContent=iso;
}
function toggleManual(el){sendControl({manualSensor:el.checked},null,el.checked?'Manual ON':'Manual OFF');}
function updateEV(v){
  clearTimeout(_eT);
  _eT=setTimeout(function(){sendControl({ev:parseInt(v,10)},null,'EV');},150);
  document.getElementById('ev-val').textContent=(parseInt(v,10)>0?'+':'')+v;
}
function setEVPreset(v){document.getElementById('ev').value=v;updateEV(v);}
function toggleAELock(el){sendControl({aeLock:el.checked},null,el.checked?'AE travado':'AE livre');}

function updateShutter(v){
  clearTimeout(_shT);
  var s=SHUTTER_STOPS[Math.min(parseInt(v,10),SHUTTER_STOPS.length-1)];
  _shT=setTimeout(function(){sendControl({shutter:s},null,'Shutter');},150);
  document.getElementById('shutter-val').textContent=s+'s';
}
function setShutterPreset(s){
  var i=SHUTTER_STOPS.indexOf(s);
  if(i>=0){document.getElementById('shutter').value=i;updateShutter(i);}
}
function updateFrameTime(v){
  clearTimeout(_frT);
  var s=FRAME_STOPS[Math.min(parseInt(v,10),FRAME_STOPS.length-1)];
  _frT=setTimeout(function(){sendControl({frameTime:s},null,'FrameTime');},150);
  document.getElementById('frame-val').textContent=s+'s';
}
function setFramePreset(v){document.getElementById('frame').value=v;updateFrameTime(v);}

function updateBitrate(v){
  clearTimeout(_brT);
  _brT=setTimeout(function(){sendControl({bitrate:parseInt(v,10)},null,'Bitrate');},200);
  document.getElementById('br-value').textContent=v;
}
function setBitratePreset(v){document.getElementById('bitrate').value=v;updateBitrate(v);}
function toggleOIS(el){sendControl({ois:el.checked},null,el.checked?'OIS ON':'OIS OFF');}
function toggleEIS(el){sendControl({eis:el.checked},null,el.checked?'EIS ON':'EIS OFF');}

function updateRggb(ch,v){
  var timers={R:'_rgT_R',Gr:'_rgT_Gr',Gb:'_rgT_Gb',B:'_rgT_B'};
  var t=timers[ch];
  clearTimeout(window[t]);
  var val=parseFloat(v)/100;
  var numEl=document.getElementById('rggb-'+ch.toLowerCase()+'-num');
  if(numEl)numEl.textContent=val.toFixed(2);
  window[t]=setTimeout(function(){
    var payload={};payload['rggb'+ch]=val;
    sendControl(payload,null,'RGGB '+ch);
  },200);
  _rggbEnabled=true;
  var badge=document.getElementById('badge-rggb');if(badge)badge.style.display='flex';
  var status=document.getElementById('rggb-status');if(status)status.textContent='Ativo';
}
function applyRggbPreset(r,gr,gb,b){
  document.getElementById('rggb-r').value=Math.round(r*100);
  document.getElementById('rggb-gr').value=Math.round(gr*100);
  document.getElementById('rggb-gb').value=Math.round(gb*100);
  document.getElementById('rggb-b').value=Math.round(b*100);
  document.getElementById('rggb-r-num').textContent=r.toFixed(2);
  document.getElementById('rggb-gr-num').textContent=gr.toFixed(2);
  document.getElementById('rggb-gb-num').textContent=gb.toFixed(2);
  document.getElementById('rggb-b-num').textContent=b.toFixed(2);
  sendControl({rggbR:r,rggbGr:gr,rggbGb:gb,rggbB:b},null,'Preset RGGB');
}
function resetRggb(btn){
  applyRggbPreset(1,1,1,1);
  sendControl({resetRggb:true},btn,'RGGB resetado');
  _rggbEnabled=false;
  var badge=document.getElementById('badge-rggb');if(badge)badge.style.display='none';
  var status=document.getElementById('rggb-status');if(status)status.textContent='Off';
}

function buildModeButtons(containerId,values,dataAttr,clickFn,currentValue){
  var container=document.getElementById(containerId);
  if(!container)return;
  if(!values||!values.length){ container.innerHTML='<span style="color:var(--muted)">Sem modos</span>'; return; }
  var html='';
  for(var i=0;i<values.length;i++){
    var v=values[i];
    var active=(v===currentValue);
    html+='<button '+dataAttr+'="'+v+'"'+(active?' class="active"':'')+' onclick="'+clickFn+'(\''+v+'\',this)">'+v+'</button>';
  }
  container.innerHTML=html;
}
function setEdge(val,btn){sendControl({edge:val},btn,'Edge: '+val);markActive('data-edge',val);}
function setNR(val,btn){sendControl({nr:val},btn,'NR: '+val);markActive('data-nr',val);}
function setHotPx(val,btn){sendControl({hotPixel:val},btn,'HotPx: '+val);markActive('data-hotpx',val);}
function applyQualityMax(btn){
  setEdge('high_quality',null);setNR('high_quality',null);setHotPx('high_quality',null);
  feedback(btn,true);showToast('Qualidade Máxima',false);
}
function applyLatencyMin(btn){
  setEdge('fast',null);setNR('minimal',null);setHotPx('fast',null);
  feedback(btn,true);showToast('Latência Mínima',false);
}

function updateLensAndSensorPanels(status){
  var li=status.lens_info||{};
  var si=status.sensor_info||{};
  var ci=status.capability_info||{};
  var fi=status.formats_info||{};
  var ctl=status.controls_info||{};

  setText('lens-name',li.name);
  setText('lens-facing',li.facing);
  setText('lens-focus-cal',li.focus_distance_calibration);
  setText('lens-min-focus',li.lens_min_focus_distance!=null ? fmtFloat(li.lens_min_focus_distance,2)+' D' : '-');
  setText('lens-focals',fmtList(li.focal_lengths,'mm'));
  setText('lens-apertures',fmtList(li.apertures,''));
  setText('sensor-px',Array.isArray(si.sensor_pixel_array_size)&&si.sensor_pixel_array_size.length>=2 ? si.sensor_pixel_array_size[0]+' x '+si.sensor_pixel_array_size[1] : '-');
  setText('sensor-mm',Array.isArray(si.sensor_physical_size)&&si.sensor_physical_size.length>=2 ? fmtFloat(si.sensor_physical_size[0],2)+' x '+fmtFloat(si.sensor_physical_size[1],2)+' mm' : '-');
  setText('sensor-orientation',si.sensor_orientation!=null ? si.sensor_orientation+'°' : '-');
  setText('sensor-ts',si.timestamp_source);

  setText('cap-hw',si.hardware_level);
  setText('cap-manual',boolText(ci.supports_manual_sensor));
  setText('cap-postproc',boolText(ci.supports_manual_post_processing));
  setText('cap-raw',boolText(ci.supports_raw));
  setText('cap-burst',boolText(ci.supports_burst_capture));
  setText('cap-depth',boolText(ci.supports_depth_output));
  setText('cap-logical',boolText(ci.supports_logical_multi_camera));
  setText('cap-60fps',boolText(ci.supports_60fps));
  setText('cap-highspeed',boolText(ci.supports_high_speed));
  setText('cap-crop',si.scaler_cropping_type);

  buildChips('chips-formats',fi.output_formats||[]);
  buildChips('chips-highspeed-sizes',fi.high_speed_video_sizes||[]);
  buildChips('chips-highspeed-fps',(fi.high_speed_video_fps_ranges||[]).map(function(r){ return Array.isArray(r)&&r.length>=2 ? r[0]+'-'+r[1]+' fps' : String(r); }));

  var highSpeedBadge=document.getElementById('badge-highspeed');
  if(highSpeedBadge)highSpeedBadge.style.display=ci.supports_high_speed?'flex':'none';

  buildModeButtons('btngroup-edge-modes',ctl.edge_modes||[],'data-edge','setEdge',status.edge);
  buildModeButtons('btngroup-nr-modes',ctl.noise_reduction_modes||[],'data-nr','setNR',status.nr);
  buildModeButtons('btngroup-hotpx-modes',ctl.hot_pixel_modes||[],'data-hotpx','setHotPx',status.hot_pixel);

  if(Array.isArray(ctl.zoom_ratio_range)&&ctl.zoom_ratio_range.length>=2){
    setText('zoom-min-label',fmtFloat(ctl.zoom_ratio_range[0],2)+'x');
    setText('zoom-max-label',fmtFloat(ctl.zoom_ratio_range[1],2)+'x');
  } else if(Array.isArray(ctl.zoom_range)&&ctl.zoom_range.length>=2){
    setText('zoom-min-label',fmtFloat(ctl.zoom_range[0],2)+'x');
    setText('zoom-max-label',fmtFloat(ctl.zoom_range[1],2)+'x');
  }

  if(Array.isArray(ctl.ev_range)&&ctl.ev_range.length>=2){
    var labels=document.getElementById('ev-labels');
    if(labels)labels.innerHTML='<span>'+ctl.ev_range[0]+'</span><span>0</span><span>+'+ctl.ev_range[1]+'</span>';
  }
}

function applyCameraCapabilities(camId){
  var cap=getCap(camId);
  if(!cap)return;

  buildResolutionButtons(cap,cap.current_resolution,cap.current_fps);
  buildFocusModeButtons(cap.supported_af_modes||[],cap.current_af_mode);
  buildWBButtons(cap.supported_awb_modes||[],cap.current_wb);
  updateOISCapability(cap.has_ois||false);

  showCard('card-postproc',cap.supports_manual_post_processing||false);

  if(cap.iso_range){
    var minIso=cap.iso_range[0], maxIso=cap.iso_range[1];
    var labels=document.querySelector('#card-iso .rlabels');
    if(labels)labels.innerHTML='<span>'+minIso+'</span><span>'+Math.round((minIso+maxIso)/2)+'</span><span>'+maxIso+'</span>';
  }
}

function applyStatus(s){
  _isStreaming=!!s.streaming;

  var dot=document.getElementById('dot-stream');
  var lbl=document.getElementById('lbl-stream');
  if(s.streaming){
    if(dot)dot.classList.remove('off');
    if(lbl)lbl.textContent='AO VIVO';
  }else{
    if(dot)dot.classList.add('off');
    if(lbl)lbl.textContent='Parado';
  }

  setText('lbl-cam',s.camera_id);
  setText('lbl-res',s.resolution ? resolutionLabel(s.resolution) : s.resolution);
  setText('lbl-br',s.bitrate_kbps);
  setText('info-focusmode',s.focus_mode);
  setText('info-focusdist',s.focus_dist!=null?s.focus_dist.toFixed(2)+'D':null);
  setText('info-iso',s.iso);
  setText('info-exp',s.exposure_ns!=null?formatShutter(s.exposure_ns):null);
  setText('info-frame',s.frame_duration_ns!=null?formatShutter(s.frame_duration_ns):null);
  setText('info-focal',s.focal_length!=null?fmtFloat(s.focal_length,1):null);
  setText('info-ap',s.aperture!=null?fmtFloat(s.aperture,1):null);
  setText('info-wb',s.wb);
  setText('info-ois',s.ois!=null?(s.ois?'On':'Off'):null);
  setText('info-eis',s.eis!=null?(s.eis?'On':'Off'):null);
  setText('info-fps',s.fps);
  setText('info-edge',s.edge);
  setText('info-nr',s.nr);
  setText('info-hotpx',s.hot_pixel);
  setText('info-rtmpurl',s.rtmp_url);

  if(s.monitor){
    var m=s.monitor;
    setText('mon-iso',m.iso&&m.iso>0?m.iso:null);
    setText('mon-shutter',m.shutter_ns&&m.shutter_ns>0?formatShutter(m.shutter_ns):null);
    setText('mon-af',m.af_state&&m.af_state!=='unknown'?m.af_state:null);
    setText('mon-ae',m.ae_state&&m.ae_state!=='unknown'?m.ae_state:null);
    setText('mon-rggb-r',m.rggb_r!=null?m.rggb_r.toFixed(3):null);
    setText('mon-rggb-b',m.rggb_b!=null?m.rggb_b.toFixed(3):null);
    setText('mon-rggb-gr',m.rggb_gr!=null?m.rggb_gr.toFixed(3):null);
    setText('mon-rggb-gb',m.rggb_gb!=null?m.rggb_gb.toFixed(3):null);
  }

  updateManualUI(s.manual_sensor||false);

  if(s.cameras && _caps===null){
    _caps=s.cameras;
    buildCameraButtons(s.cameras,s.camera_id);
    applyCameraCapabilities(s.camera_id);
    _currentCamId=String(s.camera_id);
  }

  updateLensAndSensorPanels(s);

  var aeLock=document.getElementById('toggle-ae-lock');
  var awbLock=document.getElementById('toggle-awb-lock');
  var oisToggle=document.getElementById('toggle-ois');
  var eisToggle=document.getElementById('toggle-eis');
  var manualToggle=document.getElementById('toggle-manual');

  if(aeLock)aeLock.checked=!!s.ae_lock;
  if(awbLock)awbLock.checked=!!s.awb_lock;
  if(oisToggle)oisToggle.checked=!!s.ois;
  if(eisToggle)eisToggle.checked=!!s.eis;
  if(manualToggle)manualToggle.checked=!!s.manual_sensor;

  if(s.resolution)markActive('data-res',s.resolution);
}

function pollStatus(){
  if(_pollCtrl)_pollCtrl.abort();
  _pollCtrl=new AbortController();
  var timeoutId=setTimeout(function(){_pollCtrl.abort();},1000);

  fetch('/api/status',{signal:_pollCtrl.signal})
    .then(function(r){clearTimeout(timeoutId);if(!r.ok)throw new Error('HTTP '+r.status);return r.json();})
    .then(function(data){
      _pollFail=0;
      applyStatus(data);
    })
    .catch(function(e){
      clearTimeout(timeoutId);
      if(e.name==='AbortError')return;
      _pollFail++;
      if(_pollFail>3){
        _isStreaming=false;
        var lbl=document.getElementById('lbl-stream'); if(lbl)lbl.textContent='Sem conexão';
        var dot=document.getElementById('dot-stream'); if(dot)dot.classList.add('off');
      }
    });
}

document.addEventListener('DOMContentLoaded',function(){
  pollStatus();
  setInterval(pollStatus,1000);
});