"use strict";

var ISO_LIST=[50,81,112,143,174,205,236,267,298,329,360,391,422,453,484,515,546,577,608,639,670,701,732,763,794,825,856,887,918,949,980,1011,1042,1073,1104,1135,1166,1197,1228,1259,1290,1321,1352,1383,1414,1445,1476,1507,1538,1569,1600,1631,1662,1693,1724,1755,1786,1817,1848,1879,1910,1941,1972,2003,2034,2065,2096,2127,2158,2189,2220,2251,2282,2313,2344,2375,2406,2437,2468,2499,2530,2561,2592,2623,2654,2685,2716,2747,2778,2809,2840,2871,2902,2933,2964,2995,3026,3057,3088,3119,3150,3200];
var SHUTTER_STOPS=['1/24','1/30','1/50','1/60','1/100','1/250','1/500','1/1000','1/2000','1/4000','1/10000'];
var FRAME_STOPS=['1/15','1/24','1/30','1/60'];
var RES_LABELS={'7680x4320':'8K','3840x2160':'4K','4032x2268':'4K (4:3)','4608x2592':'4K UW','2560x1440':'2K','1920x1080':'1080p','1920x1440':'1080p 4:3','1280x720':'720p','960x540':'540p','854x480':'480p','640x360':'360p'};
var RES_MIN_WIDTH=640;
var _caps=null,_currentCamId='0',_isManual=false,_rggbEnabled=false,_toastTimer,_pollFail=0,_pollCtrl=null;
var _brT,_zT,_fT,_iT,_eT,_shT,_frT,_rgT_R,_rgT_Gr,_rgT_Gb,_rgT_B;

// Estado da captura RAW
var _rawPollTimer=null,_rawPollCount=0,_rawPollMax=60; // 60 * 500ms = 30s timeout

function showToast(msg,isErr){var t=document.getElementById('toast');t.textContent=msg;t.className=isErr?'err':'ok';t.classList.add('show');clearTimeout(_toastTimer);_toastTimer=setTimeout(function(){t.classList.remove('show');},1800)}
function feedback(btn,ok){if(!btn)return;var cls=ok?'fb-ok':'fb-err';btn.classList.remove('fb-ok','fb-err');void btn.offsetWidth;btn.classList.add(cls);setTimeout(function(){btn.classList.remove(cls);},500)}
function markActive(attr,val){var els=document.querySelectorAll('['+attr+']');for(var i=0;i<els.length;i++){els[i].classList.toggle('active',els[i].getAttribute(attr)===String(val))}}
function showCard(id,show){var el=document.getElementById(id);if(!el)return;show?el.classList.remove('hidden'):el.classList.add('hidden')}
function setText(id,val){var el=document.getElementById(id);if(el)el.textContent=(val!==undefined&&val!==null&&val!=='')?val:'-'}
function setClass(id,cls){var el=document.getElementById(id);if(el){el.className='mc-val';if(cls)el.classList.add(cls)}}
function setToggleChecked(id,checked){var el=document.getElementById(id);if(el)el.checked=!!checked}
function setBadge(id,enabled){var el=document.getElementById(id);if(el)el.style.display=enabled?'flex':'none'}
function formatShutter(ns){if(!ns||ns<=0)return '-';var s=ns/1e9;if(s>=1)return s.toFixed(2)+'s';return '1/'+Math.round(1/s)+'s'}
function formatNs(ns){if(!ns||ns<=0)return '-';if(ns>1e9)return (ns/1e9).toFixed(3)+' s';if(ns>1e6)return (ns/1e6).toFixed(3)+' ms';return ns+' ns'}
function resLabel(res){if(RES_LABELS[res])return RES_LABELS[res]+'\n'+res;var p=res.split('x');if(p.length===2){var h=parseInt(p[1]);if(h>=2160)return '4K\n'+res;if(h>=1440)return '2K\n'+res;if(h>=1080)return '1080p\n'+res;if(h>=720)return '720p\n'+res;if(h>=540)return '540p\n'+res;if(h>=480)return '480p\n'+res}return res}
function filterStreamRes(resolutions){return (resolutions||[]).filter(function(r){var p=r.split('x');return p.length===2&&parseInt(p[0])>=RES_MIN_WIDTH})}

function sendControl(data,btn,msg){
  fetch('/api/control',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)})
    .then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();})
    .then(function(){feedback(btn,true);showToast(msg||'OK',false);setTimeout(pollStatus,120);})
    .catch(function(e){feedback(btn,false);showToast('ERR:'+e.message,true);});
}
function getCap(camId){if(!_caps)return null;for(var i=0;i<_caps.length;i++){if(String(_caps[i].camera_id)===String(camId))return _caps[i]}return null}
function applyRtmpUrl(btn){var url=document.getElementById('rtmp-input').value.trim();if(!url)return;sendControl({rtmpUrl:url},btn,'RTMP atualizado')}
function streamAction(action,btn){var msgs={start:'Stream iniciado',restart:'Stream reiniciado',stop:'Stream parado'};sendControl({streamAction:action},btn,msgs[action]||action)}
function updateManualUI(isManual){if(_isManual===isManual)return;_isManual=isManual;var badge=document.getElementById('badge-manual');if(badge)badge.style.display=isManual?'flex':'none';['card-iso','card-shutter','card-frame'].forEach(function(id){var el=document.getElementById(id);if(!el)return;isManual?el.classList.add('manual-active'):el.classList.remove('manual-active')});showCard('card-shutter',isManual);showCard('card-frame',isManual);var evSlider=document.getElementById('ev');var evHint=document.getElementById('ev-hint');var evPresets=document.getElementById('ev-presets');if(evSlider)evSlider.disabled=isManual;if(evHint)isManual?evHint.classList.add('show'):evHint.classList.remove('show');if(evPresets){var btns=evPresets.querySelectorAll('button');for(var i=0;i<btns.length;i++)btns[i].disabled=isManual}}
function updateOISCapability(hasOIS){var chk=document.getElementById('toggle-ois');var hint=document.getElementById('ois-hint');if(chk)chk.disabled=!hasOIS;if(hint)hint.textContent=hasOIS?'':'(sem suporte nesta câmera)'}

function buildCameraButtons(cameras,currentId){var c=document.getElementById('btngroup-camera');if(!c)return;var html='';for(var i=0;i<cameras.length;i++){var cam=cameras[i];if(cam.is_depth)continue;var lbl=cam.name||('Cam '+cam.camera_id);var active=(String(cam.camera_id)===String(currentId));html+='<button data-cam="'+cam.camera_id+'"'+(active?' class="active"':'')+' onclick="switchCamera(\''+cam.camera_id+'\',this)">'+lbl+'</button>'}c.innerHTML=html||'<span style="color:var(--muted)">Nenhuma câmera de vídeo disponível</span>'}
function switchCamera(camId,btn){_currentCamId=camId;sendControl({camera:camId},btn,'Câmera '+camId);markActive('data-cam',camId);applyCameraCapabilities(camId)}
function buildResolutionButtons(resolutions,currentRes){var c=document.getElementById('btngroup-resolution');if(!c)return;var filtered=filterStreamRes(resolutions);if(!filtered.length){c.innerHTML='<span style="color:var(--muted)">Nenhuma resolução disponível</span>';return}var html='';for(var i=0;i<filtered.length;i++){var r=filtered[i];var active=(r===currentRes);html+='<button data-res="'+r+'" style="white-space:pre;line-height:1.2"'+(active?' class="active"':'')+' onclick="setResolution(\''+r+'\',this)">'+resLabel(r)+'</button>'}c.innerHTML=html}
function buildFpsButtons(fpsRanges,currentFps){var c=document.getElementById('btngroup-fps');if(!c)return;var candidates=[15,24,30,60,120,240];var html='';for(var i=0;i<candidates.length;i++){var fps=candidates[i],supported=false;if(fpsRanges){for(var j=0;j<fpsRanges.length;j++){if(fps>=fpsRanges[j][0]&&fps<=fpsRanges[j][1]){supported=true;break}}}if(!supported)continue;var active=(fps===currentFps);html+='<button data-fps="'+fps+'"'+(active?' class="active"':'')+' onclick="setFps('+fps+',this)">'+fps+'fps</button>'}c.innerHTML=html||'<span style="color:var(--muted)">-</span>'}
function setResolution(res,btn){sendControl({resolution:res},btn,res);markActive('data-res',res)}
function setFps(fps,btn){sendControl({fps:fps},btn,fps+'fps');markActive('data-fps',fps)}
function updateZoom(v){clearTimeout(_zT);_zT=setTimeout(function(){sendControl({zoom:parseFloat(v)},null,'Zoom')},250);document.getElementById('zoom-val').textContent=parseFloat(v)===0?'1x':(Math.round(parseFloat(v)*10)/10)+'x'}
function setZoomPreset(v){document.getElementById('zoom').value=v;updateZoom(v)}
function buildOpticalZoomButtons(lenses,currentLens){var card=document.getElementById('card-optical-zoom');var c=document.getElementById('btngroup-optical-zoom');if(!card||!c||!lenses||lenses.length<2){showCard('card-optical-zoom',false);return}showCard('card-optical-zoom',true);var html='';for(var i=0;i<lenses.length;i++){var l=lenses[i];var active=(l.focal_length===currentLens);html+='<button data-optical="'+l.focal_length+'"'+(active?' class="active"':'')+' onclick="setOpticalZoom('+l.focal_length+',this)">'+l.label+'</button>'}c.innerHTML=html}
function setOpticalZoom(fl,btn){sendControl({opticalZoom:fl},btn,'Lente '+fl+'mm');markActive('data-optical',fl);document.getElementById('optical-zoom-val').textContent=fl+'mm'}
function updateFocus(v){clearTimeout(_fT);var fv=parseFloat(v);_fT=setTimeout(function(){sendControl({focus:fv},null,'Foco')},200);document.getElementById('focus-val').textContent=fv===0?'Auto':fv.toFixed(1)+'D'}
function buildFocusModeButtons(modes,currentMode){var c=document.getElementById('btngroup-focusmode');if(!c)return;var labels={auto:'AF Auto',macro:'Macro',continuous_video:'AF Contínuo','continuous-video':'AF Contínuo','continuous-picture':'AF Foto',fixed:'Fixo',edof:'EDOF',off:'Manual'};var html='';for(var i=0;i<modes.length;i++){var m=modes[i];var active=(m===currentMode);html+='<button data-af="'+m+'"'+(active?' class="active"':'')+' onclick="setFocusMode(\''+m+'\',this)">'+(labels[m]||m)+'</button>'}c.innerHTML=html}
function setFocusMode(mode,btn){sendControl({focusMode:mode},btn,mode);markActive('data-af',mode)}
function triggerAF(btn){sendControl({triggerAF:true},btn,'AF acionado')}
function buildWBButtons(modes,currentMode){var c=document.getElementById('btngroup-wb');if(!c)return;var labels={auto:'Auto',cloudy_daylight:'Nublado',cloudy:'Nublado',daylight:'Sol',fluorescent:'Fluorescente',incandescent:'Incandescente',shade:'Sombra',twilight:'Crepúsculo',warm_fluorescent:'Fluoresc. Quente',off:'Manual'};var html='';for(var i=0;i<modes.length;i++){var m=modes[i];if(m==='off')continue;var active=(m===currentMode);html+='<button data-wb="'+m+'"'+(active?' class="active"':'')+' onclick="setWBMode(\''+m+'\',this)">'+(labels[m]||m)+'</button>'}c.innerHTML=html}
function setWBMode(mode,btn){sendControl({wb:mode},btn,mode);markActive('data-wb',mode)}
function toggleAWBLock(el){sendControl({awbLock:el.checked},null,el.checked?'AWB travado':'AWB livre')}
function updateISO(v){clearTimeout(_iT);var iso=ISO_LIST[Math.min(parseInt(v),ISO_LIST.length-1)];_iT=setTimeout(function(){sendControl({iso:iso},null,'ISO')},150);document.getElementById('iso-val').textContent=iso}
function toggleManual(el){sendControl({manualSensor:el.checked},null,el.checked?'Manual ON':'Manual OFF')}
function updateEV(v){clearTimeout(_eT);_eT=setTimeout(function(){sendControl({ev:parseInt(v)},null,'EV')},150);document.getElementById('ev-val').textContent=(parseInt(v)>0?'+':'')+v}
function setEVPreset(v){document.getElementById('ev').value=v;updateEV(v)}
function toggleAELock(el){sendControl({aeLock:el.checked},null,el.checked?'AE travado':'AE livre')}
function updateShutter(v){clearTimeout(_shT);var s=SHUTTER_STOPS[Math.min(parseInt(v),SHUTTER_STOPS.length-1)];_shT=setTimeout(function(){sendControl({shutter:s},null,'Shutter')},150);document.getElementById('shutter-val').textContent=s+'s'}
function setShutterPreset(s){var i=SHUTTER_STOPS.indexOf(s);if(i>=0){document.getElementById('shutter').value=i;updateShutter(i)}}
function updateFrameTime(v){clearTimeout(_frT);var s=FRAME_STOPS[Math.min(parseInt(v),FRAME_STOPS.length-1)];_frT=setTimeout(function(){sendControl({frameTime:s},null,'FrameTime')},150);document.getElementById('frame-val').textContent=s+'s'}
function setFramePreset(v){document.getElementById('frame').value=v;updateFrameTime(v)}
function updateBitrate(v){clearTimeout(_brT);_brT=setTimeout(function(){sendControl({bitrate:parseInt(v)},null,'Bitrate')},200);document.getElementById('br-value').textContent=v}
function setBitratePreset(v){document.getElementById('bitrate').value=v;updateBitrate(v)}
function toggleOIS(el){sendControl({ois:el.checked},null,el.checked?'OIS ON':'OIS OFF')}
function toggleEIS(el){sendControl({eis:el.checked},null,el.checked?'EIS ON':'EIS OFF')}
function updateRggb(ch,v){var timers={R:'_rgT_R',Gr:'_rgT_Gr',Gb:'_rgT_Gb',B:'_rgT_B'};var t=timers[ch];clearTimeout(window[t]);var val=parseFloat(v)/100;var numEl=document.getElementById('rggb-'+ch.toLowerCase()+'-num');if(numEl)numEl.textContent=val.toFixed(2);window[t]=setTimeout(function(){var payload={};payload['rggb'+ch]=val;sendControl(payload,null,'RGGB '+ch)},200);_rggbEnabled=true;setBadge('badge-rggb',true);var status=document.getElementById('rggb-status');if(status)status.textContent='Ativo';document.getElementById('card-rggb').classList.add('rggb-active')}
function applyRggbPreset(r,gr,gb,b){document.getElementById('rggb-r').value=Math.round(r*100);document.getElementById('rggb-gr').value=Math.round(gr*100);document.getElementById('rggb-gb').value=Math.round(gb*100);document.getElementById('rggb-b').value=Math.round(b*100);document.getElementById('rggb-r-num').textContent=r.toFixed(2);document.getElementById('rggb-gr-num').textContent=gr.toFixed(2);document.getElementById('rggb-gb-num').textContent=gb.toFixed(2);document.getElementById('rggb-b-num').textContent=b.toFixed(2);sendControl({rggbR:r,rggbGr:gr,rggbGb:gb,rggbB:b},null,'Preset RGGB')}
function resetRggb(btn){applyRggbPreset(1,1,1,1);sendControl({resetRggb:true},btn,'RGGB resetado');_rggbEnabled=false;setBadge('badge-rggb',false);var status=document.getElementById('rggb-status');if(status)status.textContent='Off';document.getElementById('card-rggb').classList.remove('rggb-active')}
function setEdge(val,btn){sendControl({edge:val},btn,'Edge: '+val);markActive('data-edge',val)}
function setNR(val,btn){sendControl({nr:val},btn,'NR: '+val);markActive('data-nr',val)}
function setHotPx(val,btn){sendControl({hotPixel:val},btn,'HotPx: '+val);markActive('data-hotpx',val)}
function applyQualityMax(btn){setEdge('high_quality',null);setNR('high_quality',null);setHotPx('high_quality',null);feedback(btn,true);showToast('Qualidade Máxima',false)}
function applyLatencyMin(btn){setEdge('fast',null);setNR('minimal',null);setHotPx('fast',null);feedback(btn,true);showToast('Latência Mínima',false)}
function toggleYuv(el){sendControl({yuvCapture:el.checked},null,el.checked?'YUV ON':'YUV OFF')}
function toggleDepth(el){sendControl({depthFusion:el.checked},null,el.checked?'Depth ON':'Depth OFF')}

// ─────────────────────────────────────────────────────
// RAW STILL CAPTURE
// Bug #7 fix: usa fetch() + Blob URL em vez de <a download>
// para garantir download cross-origin no Chrome/Edge/Firefox.
// ─────────────────────────────────────────────────────

function _setRawStatus(msg){var el=document.getElementById('raw-capture-status');if(el)el.textContent=msg||'-';}
function _setRawDownloadRow(show,filename,sizeKb){
  var row=document.getElementById('raw-download-row');
  if(!row)return;
  row.style.display=show?'block':'none';
  if(show){
    var nm=document.getElementById('raw-file-name');if(nm)nm.textContent=filename||'';
    var sz=document.getElementById('raw-file-size');if(sz)sz.textContent=sizeKb||'?';
  }
}

function captureRaw(btn){
  if(btn)btn.disabled=true;
  _setRawStatus('⏳ Enviando disparo...');
  _setRawDownloadRow(false);
  clearTimeout(_rawPollTimer);
  _rawPollCount=0;

  fetch('/api/raw/capture',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})
    .then(function(r){
      if(r.status!==202&&!r.ok)throw new Error('HTTP '+r.status);
      _setRawStatus('⏳ Capturando RAW... aguarde');
      showToast('Captura RAW iniciada',false);
      _pollRawReady();
    })
    .catch(function(e){
      _setRawStatus('❌ Erro: '+e.message);
      showToast('Falha ao capturar RAW',true);
      if(btn)btn.disabled=false;
    });
}

function _pollRawReady(){
  _rawPollCount++;
  if(_rawPollCount>_rawPollMax){
    _setRawStatus('⏱ Timeout: captura nao concluida em 30s');
    var btn=document.getElementById('btn-capture-raw');
    if(btn)btn.disabled=false;
    return;
  }

  fetch('/api/raw/result?poll=1')
    .then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();})
    .then(function(data){
      if(data.ready){
        _setRawStatus('✅ Pronto! '+data.filename+' ('+data.size_kb+' KB)');
        _setRawDownloadRow(true,data.filename,data.size_kb);
        setBadge('badge-raw-ready',true);
        var btn=document.getElementById('btn-capture-raw');
        if(btn)btn.disabled=false;
        showToast('DNG pronto: '+data.size_kb+' KB',false);
      } else {
        _rawPollTimer=setTimeout(_pollRawReady,500);
      }
    })
    .catch(function(e){
      _setRawStatus('❌ Polling falhou: '+e.message);
      var btn=document.getElementById('btn-capture-raw');
      if(btn)btn.disabled=false;
    });
}

function downloadRawDng(btn){
  if(btn)btn.disabled=true;
  _setRawStatus('⬇️ Baixando DNG...');

  fetch('/api/raw/result')
    .then(function(r){
      if(!r.ok)throw new Error('HTTP '+r.status);
      var cd=r.headers.get('Content-Disposition')||'';
      var match=cd.match(/filename=["']?([^"'\s]+)["']?/);
      var filename=match?match[1]:'raw_capture.dng';
      return r.blob().then(function(blob){return {blob:blob,filename:filename};});
    })
    .then(function(obj){
      var url=URL.createObjectURL(obj.blob);
      var a=document.createElement('a');
      a.href=url;
      a.download=obj.filename;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      _setRawStatus('✅ Download concluido: '+obj.filename);
      showToast('DNG baixado com sucesso',false);
    })
    .catch(function(e){
      _setRawStatus('❌ Download falhou: '+e.message);
      showToast('Falha no download',true);
    })
    .finally(function(){
      if(btn)btn.disabled=false;
    });
}

// ─────────────────────────────────────────────────────

function applyCameraCapabilities(camId){var cap=getCap(camId);if(!cap)return;buildResolutionButtons(cap.available_resolutions||[],cap.current_resolution);buildFpsButtons(cap.fps_ranges||null,cap.current_fps);buildFocusModeButtons(cap.supported_af_modes||cap.af_modes||[],cap.current_af_mode);buildWBButtons(cap.supported_awb_modes||cap.awb_modes||[],cap.current_wb);updateOISCapability(cap.has_ois||false);buildOpticalZoomButtons(cap.lenses||null,cap.current_focal_length);showCard('card-postproc',cap.supports_manual_post_processing||cap.has_postproc||false);if(cap.iso_range){var minIso=cap.iso_range[0],maxIso=cap.iso_range[1];var labels=document.querySelector('#card-iso .rlabels');if(labels)labels.innerHTML='<span>'+minIso+'</span><span>'+Math.round((minIso+maxIso)/2)+'</span><span>'+maxIso+'</span>'}}

function applyAdvancedVision(s){
  var adv=s.advanced_vision||{};
  var yuv=(adv.yuv_enabled!==undefined)?adv.yuv_enabled:s.yuv_enabled;
  var depth=(adv.depth_enabled!==undefined)?adv.depth_enabled:s.depth_enabled;
  var yuvTs=(adv.last_yuv_ts_ns!==undefined)?adv.last_yuv_ts_ns:s.last_yuv_ts_ns;
  var depthMean=(adv.depth_mean_mm!==undefined)?adv.depth_mean_mm:s.depth_mean_mm;
  var depthMin=(adv.depth_min_mm!==undefined)?adv.depth_min_mm:s.depth_min_mm;
  var depthMax=(adv.depth_max_mm!==undefined)?adv.depth_max_mm:s.depth_max_mm;
  var rawEnabled=(adv.raw_enabled!==undefined)?adv.raw_enabled:s.raw_enabled;
  var rawReady=(adv.raw_ready!==undefined)?adv.raw_ready:false;
  var rawFilename=(adv.raw_filename!==undefined)?adv.raw_filename:'';

  setToggleChecked('toggle-yuv',yuv);
  setToggleChecked('toggle-depth',depth);
  setBadge('badge-yuv',!!yuv);
  setBadge('badge-raw',!!rawEnabled);
  setBadge('badge-raw-ready',!!rawReady);
  setBadge('badge-depth',!!depth);
  setText('adv-yuv-status',yuv?'ATIVO':'OFF');
  setText('adv-raw-status',rawEnabled?'ATIVO':'OFF');
  setText('adv-depth-status',depth?'ATIVO':'OFF');
  setText('adv-yuv-ts',formatNs(yuvTs));
  setText('adv-depth-mean',depthMean&&depthMean>0?depthMean.toFixed(1)+' mm':'-');
  setText('adv-depth-range',(depthMin&&depthMin>0||depthMax&&depthMax>0)?(depthMin+' / '+depthMax+' mm'):'-');

  if(rawReady&&rawFilename){
    var row=document.getElementById('raw-download-row');
    if(row&&row.style.display==='none'){
      _setRawDownloadRow(true,rawFilename,'?');
    }
  }

  var bar=document.getElementById('depth-meter-bar');
  if(bar){
    var pct=0;
    if(depthMean&&depthMean>0){pct=Math.max(0,Math.min(100,(depthMean/5000)*100));}
    bar.style.width=pct+'%';
  }

  var card=document.getElementById('card-advanced-vision');
  if(card){
    if(yuv||rawEnabled||depth) card.classList.add('advanced-active');
    else card.classList.remove('advanced-active');
  }
}

function applyStatus(s){
  var dot=document.getElementById('dot-stream');
  var lbl=document.getElementById('lbl-stream');
  if(s.streaming){if(dot)dot.classList.remove('off');if(lbl)lbl.textContent='AO VIVO';}
  else{if(dot)dot.classList.add('off');if(lbl)lbl.textContent='Parado';}
  setText('lbl-cam',s.camera_id);setText('lbl-res',s.resolution);setText('lbl-br',s.bitrate_kbps);
  // Sincroniza slider e label de bitrate com o valor real do encoder
  if(s.bitrate_kbps!=null&&s.bitrate_kbps>0){
    var brSlider=document.getElementById('bitrate');
    var brLabel=document.getElementById('br-value');
    if(brSlider&&!brSlider._userDirty){brSlider.value=s.bitrate_kbps;}
    if(brLabel&&!brSlider._userDirty){brLabel.textContent=s.bitrate_kbps;}
  }
  var latEl=document.getElementById('lbl-lat');
  if(latEl&&s.latency_ms!==undefined){latEl.textContent=s.latency_ms+'ms';latEl.className=s.latency_ms<100?'lat-ok':s.latency_ms<300?'lat-warn':'lat-bad'}
  setText('info-focusmode',s.focus_mode);
  setText('info-focusdist',s.focus_dist!=null?s.focus_dist.toFixed(2)+'D':null);
  setText('info-iso',s.iso);
  setText('info-exp',s.exposure_ns!=null?formatShutter(s.exposure_ns):null);
  setText('info-frame',s.frame_duration_ns!=null?formatShutter(s.frame_duration_ns):null);
  setText('info-focal',s.focal_length!=null?Number(s.focal_length).toFixed(1):null);
  setText('info-ap',s.aperture!=null?Number(s.aperture).toFixed(1):null);
  setText('info-wb',s.wb);setText('info-ois',s.ois!=null?(s.ois?'On':'Off'):null);setText('info-eis',s.eis!=null?(s.eis?'On':'Off'):null);
  setText('info-fps',s.fps);setText('info-edge',s.edge);setText('info-nr',s.nr);setText('info-hotpx',s.hot_pixel);setText('info-rtmpurl',s.rtmp_url);
  if(s.monitor){var m=s.monitor;setText('mon-iso',m.iso&&m.iso>0?m.iso:null);setText('mon-shutter',m.shutter_ns&&m.shutter_ns>0?formatShutter(m.shutter_ns):null);var afState=m.af_state;setText('mon-af',afState&&afState!=='unknown'?afState:null);setClass('mon-af',afState==='passive_focused'||afState==='FOCUSED'?'green':afState==='passive_scan'||afState==='SEARCHING'?'yellow':null);var aeState=m.ae_state;setText('mon-ae',aeState&&aeState!=='unknown'?aeState:null);setClass('mon-ae',aeState==='converged'||aeState==='CONVERGED'?'green':aeState==='searching'||aeState==='SEARCHING'?'yellow':null);setText('mon-rggb-r',m.rggb_r!=null?Number(m.rggb_r).toFixed(3):null);setText('mon-rggb-b',m.rggb_b!=null?Number(m.rggb_b).toFixed(3):null);setText('mon-rggb-gr',m.rggb_gr!=null?Number(m.rggb_gr).toFixed(3):null);setText('mon-rggb-gb',m.rggb_gb!=null?Number(m.rggb_gb).toFixed(3):null)}
  updateManualUI(s.manual_sensor||false);
  if(s.cameras&&_caps===null){_caps=s.cameras;buildCameraButtons(s.cameras,s.camera_id);applyCameraCapabilities(s.camera_id);_currentCamId=String(s.camera_id)}
  if(s.resolution)markActive('data-res',s.resolution);
  if(s.fps)markActive('data-fps',s.fps);
  applyAdvancedVision(s);
}

function pollStatus(){
  if(_pollCtrl)_pollCtrl.abort();
  _pollCtrl=new AbortController();
  var timeoutId=setTimeout(function(){_pollCtrl.abort();},800);
  fetch('/api/status',{signal:_pollCtrl.signal})
    .then(function(r){clearTimeout(timeoutId);if(!r.ok)throw new Error('HTTP '+r.status);return r.json();})
    .then(function(data){_pollFail=0;applyStatus(data);})
    .catch(function(e){clearTimeout(timeoutId);if(e.name==='AbortError')return;_pollFail++;if(_pollFail>3){var lbl=document.getElementById('lbl-stream');if(lbl)lbl.textContent='Sem conexão';var dot=document.getElementById('dot-stream');if(dot)dot.classList.add('off');}})
}

document.addEventListener('DOMContentLoaded',function(){
  // Marca slider como 'sujado pelo usuario' para nao sobrescrever enquanto ele arrasta
  var brSlider=document.getElementById('bitrate');
  if(brSlider){
    brSlider._userDirty=false;
    brSlider.addEventListener('mousedown',function(){brSlider._userDirty=true;});
    brSlider.addEventListener('touchstart',function(){brSlider._userDirty=true;},{passive:true});
    brSlider.addEventListener('change',function(){setTimeout(function(){brSlider._userDirty=false;},2000);});
  }
  pollStatus();
  setInterval(pollStatus,1000);
});
