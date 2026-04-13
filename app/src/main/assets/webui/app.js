'use strict';

// ── Constantes ───────────────────────────────────────────────────────────────────────────────
var ISO_LIST=[50,81,112,143,174,205,236,267,298,329,360,391,422,453,484,
  515,546,577,608,639,670,701,732,763,794,825,856,887,918,949,980,1011,1042,
  1073,1104,1135,1166,1197,1228,1259,1290,1321,1352,1383,1414,1445,1476,1507,
  1538,1569,1600,1631,1662,1693,1724,1755,1786,1817,1848,1879,1910,1941,1972,
  2003,2034,2065,2096,2127,2158,2189,2220,2251,2282,2313,2344,2375,2406,2437,
  2468,2499,2530,2561,2592,2623,2654,2685,2716,2747,2778,2809,2840,2871,2902,
  2933,2964,2995,3026,3057,3088,3119,3150,3200];
var SHUTTER_STOPS=['1/24','1/30','1/50','1/60','1/100','1/250','1/500','1/1000','1/2000','1/4000','1/10000'];
var FRAME_STOPS=['1/15','1/24','1/30','1/60'];

// Label bonito para resoluções comuns de vídeo
var RES_LABELS={
  '7680x4320':'8K','3840x2160':'4K','4032x2268':'4K (4:3)','4608x2592':'4K UW',
  '2560x1440':'2K','1920x1080':'1080p','1920x1440':'1080p 4:3',
  '1280x720':'720p','960x540':'540p','854x480':'480p',
  '640x360':'360p','3840x2160':'4K'
};
// Resoluções válidas para streaming (descarta miniaturas <540p por padrão)
var RES_MIN_WIDTH=640;

// ── Estado ──────────────────────────────────────────────────────────────────────────────
var _caps=null;
var _currentCamId='0';
var _isManual=false;
var _rggbEnabled=false;
var _toastTimer;
var _pollFail=0;
var _pollCtrl=null;

// debounce timers
var _brT,_zT,_fT,_iT,_eT,_shT,_frT;
var _rgT_R,_rgT_Gr,_rgT_Gb,_rgT_B;

// ── Utilitários ───────────────────────────────────────────────────────────────────────────
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
function debounce(fn,delay,timerRef){
  return function(){
    clearTimeout(timerRef);
    timerRef=setTimeout(fn,delay);
  };
}

// Label legível para resolução
function resLabel(res){
  if(RES_LABELS[res])return RES_LABELS[res]+'\n'+res;
  // Tenta gerar label por altura
  var parts=res.split('x');
  if(parts.length===2){
    var h=parseInt(parts[1]);
    if(h>=2160)return '4K\n'+res;
    if(h>=1440)return '2K\n'+res;
    if(h>=1080)return '1080p\n'+res;
    if(h>=720)return '720p\n'+res;
    if(h>=540)return '540p\n'+res;
    if(h>=480)return '480p\n'+res;
    return res;
  }
  return res;
}

// Filtra resoluções úteis para streaming (descarta miniaturas)
function filterStreamRes(resolutions){
  return (resolutions||[]).filter(function(r){
    var parts=r.split('x');
    return parts.length===2&&parseInt(parts[0])>=RES_MIN_WIDTH;
  });
}

// ── API ───────────────────────────────────────────────────────────────────────────────
function sendControl(data,btn,msg){
  fetch('/api/control',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)})
    .then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();})
    .then(function(){feedback(btn,true);showToast(msg||'OK',false);})
    .catch(function(e){feedback(btn,false);showToast('ERR:'+e.message,true);});
}
function getCap(camId){
  if(!_caps)return null;
  for(var i=0;i<_caps.length;i++){if(String(_caps[i].camera_id)===String(camId))return _caps[i];}
  return null;
}

// ── Controles de Stream ────────────────────────────────────────────────────────────────
function applyRtmpUrl(btn){
  var url=document.getElementById('rtmp-input').value.trim();
  if(!url)return;sendControl({rtmpUrl:url},btn,'RTMP atualizado');
}
function streamAction(action,btn){
  var msgs={start:'Stream iniciado',restart:'Stream reiniciado',stop:'Stream parado'};
  sendControl({streamAction:action},btn,msgs[action]||action);
}

// ── UI Manual ──────────────────────────────────────────────────────────────────────────
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
  if(evPresets){var btns=evPresets.querySelectorAll('button');for(var i=0;i<btns.length;i++)btns[i].disabled=isManual;}
}
function updateOISCapability(hasOIS){
  var chk=document.getElementById('toggle-ois');
  var hint=document.getElementById('ois-hint');
  if(chk)chk.disabled=!hasOIS;
  if(hint)hint.textContent=hasOIS?'':'(sem suporte nesta câmera)';
}

// ── Câmeras ───────────────────────────────────────────────────────────────────────────
function buildCameraButtons(cameras,currentId){
  var container=document.getElementById('btngroup-camera');
  if(!container)return;
  var html='';
  for(var i=0;i<cameras.length;i++){
    var c=cameras[i];
    // FIX: usa c.name (API retorna "name") e filtra câmera depth/ToF
    if(c.is_depth)continue;
    // Nome real da câmera ("Wide", "Frontal", "Ultra Wide", etc)
    var lbl=c.name||('Cam '+c.camera_id);
    var active=(String(c.camera_id)===String(currentId));
    html+='<button data-cam="'+c.camera_id+'"'+(active?' class="active"':'')+
          ' onclick="switchCamera(\''+c.camera_id+'\',this)">'+lbl+'</button>';
  }
  container.innerHTML=html||'<span style="color:var(--muted)">Nenhuma câmera de vídeo disponível</span>';
}
function switchCamera(camId,btn){
  _currentCamId=camId;
  sendControl({camera:camId},btn,'Câmera '+camId);
  markActive('data-cam',camId);
  applyCameraCapabilities(camId);
}

// ── Resoluções + FPS ──────────────────────────────────────────────────────────────────────
function buildResolutionButtons(resolutions,currentRes){
  var container=document.getElementById('btngroup-resolution');
  if(!container)return;
  // FIX: filtra resoluções muito pequenas para streaming
  var filtered=filterStreamRes(resolutions);
  if(!filtered.length){
    container.innerHTML='<span style="color:var(--muted)">Nenhuma resolução disponível</span>';
    return;
  }
  var html='';
  for(var i=0;i<filtered.length;i++){
    var r=filtered[i];
    var active=(r===currentRes);
    // Label legível: "1080p\n1920x1080" — exibe em duas linhas via CSS white-space:pre
    var lbl=resLabel(r);
    html+='<button data-res="'+r+'" style="white-space:pre;line-height:1.2"'+(active?' class="active"':'')+
          ' onclick="setResolution(\''+r+'\',this)">'+lbl+'</button>';
  }
  container.innerHTML=html;
}
function buildFpsButtons(fpsRanges,currentFps){
  var container=document.getElementById('btngroup-fps');
  if(!container)return;
  // Candidatos realistas para streaming; 60/120/240 só se hardware suportar
  var candidates=[15,24,30,60,120,240];
  var html='';
  for(var i=0;i<candidates.length;i++){
    var fps=candidates[i];
    var supported=false;
    if(fpsRanges){
      for(var j=0;j<fpsRanges.length;j++){
        // fps_range é [min,max] — fps precisa ser >= min E <= max
        if(fps>=fpsRanges[j][0]&&fps<=fpsRanges[j][1]){supported=true;break;}
      }
    }
    if(!supported)continue; // Oculta completamente se o hardware não suporta
    var active=(fps===currentFps);
    html+='<button data-fps="'+fps+'"'+(active?' class="active"':'')+
          ' onclick="setFps('+fps+',this)">'+fps+'fps</button>';
  }
  container.innerHTML=html||'<span style="color:var(--muted)">-</span>';
}
function setResolution(res,btn){
  sendControl({resolution:res},btn,res);
  markActive('data-res',res);
}
function setFps(fps,btn){
  sendControl({fps:fps},btn,fps+'fps');
  markActive('data-fps',fps);
}

// ── Zoom ────────────────────────────────────────────────────────────────────────────────
function updateZoom(v){
  clearTimeout(_zT);
  _zT=setTimeout(function(){sendControl({zoom:parseFloat(v)},null,'Zoom');},250);
  document.getElementById('zoom-val').textContent=parseFloat(v)===0?'1x':(Math.round(parseFloat(v)*10)/10)+'x';
}
function setZoomPreset(v){document.getElementById('zoom').value=v;updateZoom(v);}

// Zoom Óptico
function buildOpticalZoomButtons(lenses,currentLens){
  var card=document.getElementById('card-optical-zoom');
  var container=document.getElementById('btngroup-optical-zoom');
  if(!card||!container||!lenses||lenses.length<2){showCard('card-optical-zoom',false);return;}
  showCard('card-optical-zoom',true);
  var html='';
  for(var i=0;i<lenses.length;i++){
    var l=lenses[i];
    var active=(l.focal_length===currentLens);
    html+='<button data-optical="'+l.focal_length+'"'+(active?' class="active"':'')+
          ' onclick="setOpticalZoom('+l.focal_length+',this)">'+l.label+'</button>';
  }
  container.innerHTML=html;
}
function setOpticalZoom(fl,btn){
  sendControl({opticalZoom:fl},btn,'Lente '+fl+'mm');
  markActive('data-optical',fl);
  document.getElementById('optical-zoom-val').textContent=fl+'mm';
}

// ── Foco ───────────────────────────────────────────────────────────────────────────────────
function updateFocus(v){
  clearTimeout(_fT);
  var fv=parseFloat(v);
  _fT=setTimeout(function(){sendControl({focus:fv},null,'Foco');},200);
  document.getElementById('focus-val').textContent=fv===0?'Auto':fv.toFixed(1)+'D';
}
function buildFocusModeButtons(modes,currentMode){
  var container=document.getElementById('btngroup-focusmode');
  if(!container)return;
  var labels={auto:'AF Auto',macro:'Macro',continuous_video:'AF Contínuo',
               'continuous-video':'AF Contínuo','continuous-picture':'AF Foto',
               fixed:'Fixo',edof:'EDOF',off:'Manual'};
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

// ── Balanço de Branco ─────────────────────────────────────────────────────────────────────
function buildWBButtons(modes,currentMode){
  var container=document.getElementById('btngroup-wb');
  if(!container)return;
  var labels={auto:'Auto',cloudy_daylight:'Nublado',cloudy:'Nublado',daylight:'Sol',
               fluorescent:'Fluorescente',incandescent:'Incandescente',shade:'Sombra',
               twilight:'Crepúsculo',warm_fluorescent:'Fluoresc. Quente',off:'Manual'};
  var html='';
  for(var i=0;i<modes.length;i++){
    var m=modes[i];
    if(m==='off')continue; // off = manual RGGB, controlado no card próprio
    var active=(m===currentMode);
    html+='<button data-wb="'+m+'"'+(active?' class="active"':'')+
          ' onclick="setWBMode(\''+m+'\',this)">'+(labels[m]||m)+'</button>';
  }
  container.innerHTML=html;
}
function setWBMode(mode,btn){sendControl({wb:mode},btn,mode);markActive('data-wb',mode);}
function toggleAWBLock(el){sendControl({awbLock:el.checked},null,el.checked?'AWB travado':'AWB livre');}

// ── ISO ────────────────────────────────────────────────────────────────────────────────────
function updateISO(v){
  clearTimeout(_iT);
  var iso=ISO_LIST[Math.min(parseInt(v),ISO_LIST.length-1)];
  _iT=setTimeout(function(){sendControl({iso:iso},null,'ISO');},150);
  document.getElementById('iso-val').textContent=iso;
}
function toggleManual(el){sendControl({manualSensor:el.checked},null,el.checked?'Manual ON':'Manual OFF');}

// ── EV ────────────────────────────────────────────────────────────────────────────────────────
function updateEV(v){
  clearTimeout(_eT);
  _eT=setTimeout(function(){sendControl({ev:parseInt(v)},null,'EV');},150);
  document.getElementById('ev-val').textContent=(parseInt(v)>0?'+':'')+v;
}
function setEVPreset(v){document.getElementById('ev').value=v;updateEV(v);}
function toggleAELock(el){sendControl({aeLock:el.checked},null,el.checked?'AE travado':'AE livre');}

// ── Shutter / Frame ────────────────────────────────────────────────────────────────────────
function updateShutter(v){
  clearTimeout(_shT);
  var s=SHUTTER_STOPS[Math.min(parseInt(v),SHUTTER_STOPS.length-1)];
  _shT=setTimeout(function(){sendControl({shutter:s},null,'Shutter');},150);
  document.getElementById('shutter-val').textContent=s+'s';
}
function setShutterPreset(s){
  var i=SHUTTER_STOPS.indexOf(s);
  if(i>=0){document.getElementById('shutter').value=i;updateShutter(i);}
}
function updateFrameTime(v){
  clearTimeout(_frT);
  var s=FRAME_STOPS[Math.min(parseInt(v),FRAME_STOPS.length-1)];
  _frT=setTimeout(function(){sendControl({frameTime:s},null,'FrameTime');},150);
  document.getElementById('frame-val').textContent=s+'s';
}
function setFramePreset(v){document.getElementById('frame').value=v;updateFrameTime(v);}

// ── Bitrate ───────────────────────────────────────────────────────────────────────────────
function updateBitrate(v){
  clearTimeout(_brT);
  _brT=setTimeout(function(){sendControl({bitrate:parseInt(v)},null,'Bitrate');},200);
  document.getElementById('br-value').textContent=v;
}
function setBitratePreset(v){document.getElementById('bitrate').value=v;updateBitrate(v);}

// ── OIS / EIS ────────────────────────────────────────────────────────────────────────────────
function toggleOIS(el){sendControl({ois:el.checked},null,el.checked?'OIS ON':'OIS OFF');}
function toggleEIS(el){sendControl({eis:el.checked},null,el.checked?'EIS ON':'EIS OFF');}

// ── RGGB ───────────────────────────────────────────────────────────────────────────────────
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
  document.getElementById('card-rggb').classList.add('rggb-active');
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
  document.getElementById('card-rggb').classList.remove('rggb-active');
}

// ── Processamento de Imagem ───────────────────────────────────────────────────────────────────
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

// ── Aplicar Capabilities da Câmera ────────────────────────────────────────────────────────────────
function applyCameraCapabilities(camId){
  var cap=getCap(camId);
  if(!cap)return;
  // FIX: usa available_resolutions (campo real da API) + resolucao atual do status
  buildResolutionButtons(cap.available_resolutions||[],cap.current_resolution);
  // FPS via ranges do hardware
  buildFpsButtons(cap.fps_ranges||null,cap.current_fps);
  // Foco — usa supported_af_modes (campo real)
  buildFocusModeButtons(cap.supported_af_modes||cap.af_modes||[],cap.current_af_mode);
  // WB — usa supported_awb_modes (campo real)
  buildWBButtons(cap.supported_awb_modes||cap.awb_modes||[],cap.current_wb);
  // OIS
  updateOISCapability(cap.has_ois||false);
  // Zoom óptico (multilente)
  buildOpticalZoomButtons(cap.lenses||null,cap.current_focal_length);
  // Processamento de imagem (manual sensor = supports_manual_post_processing)
  showCard('card-postproc',cap.supports_manual_post_processing||cap.has_postproc||false);
  // ISO range do hardware
  if(cap.iso_range){
    var minIso=cap.iso_range[0];var maxIso=cap.iso_range[1];
    var labels=document.querySelector('#card-iso .rlabels');
    if(labels)labels.innerHTML='<span>'+minIso+'</span><span>'+Math.round((minIso+maxIso)/2)+'</span><span>'+maxIso+'</span>';
  }
}

// ── Monitor Ao Vivo (status) ────────────────────────────────────────────────────────────────
function setText(id,val){var el=document.getElementById(id);if(el)el.textContent=(val!==undefined&&val!==null&&val!=='')?val:'-';}
function setClass(id,cls){var el=document.getElementById(id);if(el){el.className='mc-val';if(cls)el.classList.add(cls);}}

function applyStatus(s){
  // Status bar
  var dot=document.getElementById('dot-stream');
  var lbl=document.getElementById('lbl-stream');
  if(s.streaming){
    if(dot){dot.classList.remove('off');}
    if(lbl)lbl.textContent='AO VIVO';
  }else{
    if(dot){dot.classList.add('off');}
    if(lbl)lbl.textContent='Parado';
  }
  setText('lbl-cam',s.camera_id);
  setText('lbl-res',s.resolution);
  setText('lbl-br',s.bitrate_kbps);

  // Latência
  var latEl=document.getElementById('lbl-lat');
  if(latEl&&s.latency_ms!==undefined){
    latEl.textContent=s.latency_ms+'ms';
    latEl.className=s.latency_ms<100?'lat-ok':s.latency_ms<300?'lat-warn':'lat-bad';
  }

  // Info pills câmera
  setText('info-focusmode',s.focus_mode);
  setText('info-focusdist',s.focus_dist!=null?s.focus_dist.toFixed(2)+'D':null);
  setText('info-iso',s.iso);
  setText('info-exp',s.exposure_ns!=null?formatShutter(s.exposure_ns):null);
  setText('info-frame',s.frame_duration_ns!=null?formatShutter(s.frame_duration_ns):null);
  setText('info-focal',s.focal_length!=null?s.focal_length.toFixed(1):null);
  setText('info-ap',s.aperture!=null?s.aperture.toFixed(1):null);
  setText('info-wb',s.wb);
  setText('info-ois',s.ois!=null?(s.ois?'On':'Off'):null);
  setText('info-eis',s.eis!=null?(s.eis?'On':'Off'):null);
  setText('info-fps',s.fps);
  setText('info-edge',s.edge);
  setText('info-nr',s.nr);
  setText('info-hotpx',s.hot_pixel);
  setText('info-rtmpurl',s.rtmp_url);

  // Monitor ao vivo
  if(s.monitor){
    var m=s.monitor;
    setText('mon-iso',m.iso&&m.iso>0?m.iso:null);
    setText('mon-shutter',m.shutter_ns&&m.shutter_ns>0?formatShutter(m.shutter_ns):null);
    // AF State
    var afState=m.af_state;
    setText('mon-af',afState&&afState!=='unknown'?afState:null);
    setClass('mon-af',afState==='passive_focused'||afState==='FOCUSED'?'green':
                       afState==='passive_scan'||afState==='SEARCHING'?'yellow':null);
    // AE State
    var aeState=m.ae_state;
    setText('mon-ae',aeState&&aeState!=='unknown'?aeState:null);
    setClass('mon-ae',aeState==='converged'||aeState==='CONVERGED'?'green':
                       aeState==='searching'||aeState==='SEARCHING'?'yellow':null);
    // RGGB sensor
    setText('mon-rggb-r',m.rggb_r!=null?m.rggb_r.toFixed(3):null);
    setText('mon-rggb-b',m.rggb_b!=null?m.rggb_b.toFixed(3):null);
    setText('mon-rggb-gr',m.rggb_gr!=null?m.rggb_gr.toFixed(3):null);
    setText('mon-rggb-gb',m.rggb_gb!=null?m.rggb_gb.toFixed(3):null);
  }

  // Manual mode
  updateManualUI(s.manual_sensor||false);

  // Câmeras — monta botões na primeira carga; ignora câmeras depth
  if(s.cameras&&_caps===null){
    _caps=s.cameras;
    buildCameraButtons(s.cameras,s.camera_id);
    applyCameraCapabilities(s.camera_id);
    _currentCamId=String(s.camera_id);
  }

  // Resolução ativa
  if(s.resolution)markActive('data-res',s.resolution);
  // FPS ativo
  if(s.fps)markActive('data-fps',s.fps);
}

function formatShutter(ns){
  if(!ns||ns<=0)return '-';
  var s=ns/1e9;
  if(s>=1)return s.toFixed(2)+'s';
  return '1/'+Math.round(1/s)+'s';
}

// ── Poll ──────────────────────────────────────────────────────────────────────────────────
function pollStatus(){
  if(_pollCtrl)_pollCtrl.abort();
  _pollCtrl=new AbortController();
  var timeoutId=setTimeout(function(){_pollCtrl.abort();},800);
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
        var lbl=document.getElementById('lbl-stream');
        if(lbl)lbl.textContent='Sem conexão';
        var dot=document.getElementById('dot-stream');
        if(dot)dot.classList.add('off');
      }
    });
}

// ── Init ─────────────────────────────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded',function(){
  pollStatus();
  setInterval(pollStatus,1000);
});
