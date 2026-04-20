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

var RES_LABELS={
  '7680x4320':'8K','3840x2160':'4K','4032x2268':'4K','4608x2592':'4K',
  '2560x1440':'2K','1920x1080':'1080p','1920x1440':'1080p',
  '1280x720':'720p','960x540':'540p','854x480':'480p',
  '640x360':'360p'
};
var RES_MIN_WIDTH=640;
var FPS_CANDIDATES=[15,24,30,60,120,240];
var RESOLUTION_TIER_ORDER_ASC=['720p','1080p','2K','4K','8K'];
var WEAK_RESOLUTION_TIERS=['360p','480p','540p'];

// ── Estado ──────────────────────────────────────────────────────────────────────────────
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

function parseResolution(res){
  var parts=String(res||'').split('x');
  if(parts.length!==2)return null;
  var w=parseInt(parts[0],10);
  var h=parseInt(parts[1],10);
  if(!w||!h)return null;
  return { raw:res, w:w, h:h, pixels:w*h };
}
function getResolutionTierLabel(res){
  var p=parseResolution(res);
  if(!p)return res;
  var h=p.h;
  var w=p.w;
  if(w>=7680||h>=4320)return '8K';
  if(w>=3840||h>=2160)return '4K';
  if(w>=2560||h>=1440)return '2K';
  if(w>=1920||h>=1080)return '1080p';
  if(w>=1280||h>=720)return '720p';
  if(h>=540)return '540p';
  if(h>=480)return '480p';
  return res;
}
function resLabel(res){
  var tier=getResolutionTierLabel(res);
  if(tier!==res)return tier;
  if(RES_LABELS[res])return RES_LABELS[res];
  return res;
}
function filterStreamRes(resolutions){
  return (resolutions||[]).filter(function(r){
    var parts=String(r).split('x');
    return parts.length===2&&parseInt(parts[0],10)>=RES_MIN_WIDTH;
  });
}
function normalizeRange(range){
  if(!range)return null;
  if(Array.isArray(range)&&range.length>=2)return { min:parseInt(range[0],10), max:parseInt(range[1],10) };
  if(typeof range==='object'){
    if(range.min!==undefined&&range.max!==undefined)return { min:parseInt(range.min,10), max:parseInt(range.max,10) };
    if(range.lower!==undefined&&range.upper!==undefined)return { min:parseInt(range.lower,10), max:parseInt(range.upper,10) };
  }
  return null;
}
function getSupportedFpsList(fpsRanges){
  var out=[];
  for(var i=0;i<FPS_CANDIDATES.length;i++){
    var fps=FPS_CANDIDATES[i];
    var supported=false;
    if(fpsRanges){
      for(var j=0;j<fpsRanges.length;j++){
        var r=normalizeRange(fpsRanges[j]);
        if(r&&fps>=r.min&&fps<=r.max){supported=true;break;}
      }
    }
    if(supported)out.push(fps);
  }
  return out;
}
function getFpsByResolutionMap(cap){
  var map={};
  if(!cap)return map;

  var source=cap.resolution_fps_map||cap.resolution_fps_ranges||cap.fps_by_resolution||null;
  if(source){
    for(var key in source){
      if(!Object.prototype.hasOwnProperty.call(source,key))continue;
      var fpsList=[];
      var entry=source[key];
      if(Array.isArray(entry)){
        for(var i=0;i<entry.length;i++){
          var normalized=normalizeRange(entry[i]);
          if(normalized){
            for(var c=0;c<FPS_CANDIDATES.length;c++){
              var fps=FPS_CANDIDATES[c];
              if(fps>=normalized.min&&fps<=normalized.max&&fpsList.indexOf(fps)===-1)fpsList.push(fps);
            }
          } else {
            var direct=parseInt(entry[i],10);
            if(direct&&fpsList.indexOf(direct)===-1)fpsList.push(direct);
          }
        }
      }
      fpsList.sort(function(a,b){return a-b;});
      map[key]=fpsList;
    }
    return map;
  }

  var fallback=getSupportedFpsList(cap.fps_ranges||null);
  var resolutions=cap.available_resolutions||[];
  for(var r=0;r<resolutions.length;r++)map[resolutions[r]]=fallback.slice();
  return map;
}
function simplifyResolutions(cap,currentRes){
  var resolutions=filterStreamRes((cap&&cap.available_resolutions)||[]);
  var fpsMap=getFpsByResolutionMap(cap);
  var groups={};

  for(var i=0;i<resolutions.length;i++){
    var raw=resolutions[i];
    var p=parseResolution(raw);
    if(!p)continue;
    var tier=getResolutionTierLabel(raw);
    if(!groups[tier])groups[tier]=[];
    groups[tier].push({
      raw:raw,
      w:p.w,
      h:p.h,
      pixels:p.pixels,
      tier:tier,
      fpsList:(fpsMap[raw]||[]).slice()
    });
  }

  var hasStrongTier=false;
  for(var s=0;s<RESOLUTION_TIER_ORDER_ASC.length;s++){
    if(groups[RESOLUTION_TIER_ORDER_ASC[s]]&&groups[RESOLUTION_TIER_ORDER_ASC[s]].length){
      hasStrongTier=true;
      break;
    }
  }

  var order=RESOLUTION_TIER_ORDER_ASC.slice();
  if(!hasStrongTier){
    order=WEAK_RESOLUTION_TIERS.concat(order);
  }

  var out=[];
  for(var j=0;j<order.length;j++){
    var tierName=order[j];
    if(hasStrongTier&&WEAK_RESOLUTION_TIERS.indexOf(tierName)!==-1)continue;
    var items=groups[tierName];
    if(!items||!items.length)continue;

    var chosen=items[0];
    for(var k=0;k<items.length;k++){
      var item=items[k];
      if(item.raw===currentRes){ chosen=item; break; }
      var chosenMax=chosen.fpsList.length?chosen.fpsList[chosen.fpsList.length-1]:0;
      var itemMax=item.fpsList.length?item.fpsList[item.fpsList.length-1]:0;
      if(itemMax>chosenMax||(itemMax===chosenMax&&item.pixels>chosen.pixels))chosen=item;
    }
    out.push(chosen);
  }

  if(currentRes){
    var exists=false;
    for(var x=0;x<out.length;x++){
      if(out[x].raw===currentRes){exists=true;break;}
    }
    if(!exists){
      var cp=parseResolution(currentRes);
      if(cp){
        out.push({
          raw:currentRes,
          w:cp.w,
          h:cp.h,
          pixels:cp.pixels,
          tier:getResolutionTierLabel(currentRes),
          fpsList:(fpsMap[currentRes]||[]).slice()
        });
      }
    }
  }

  return out;
}
function getBestFpsForResolution(item,currentFps,currentRes){
  if(!item||!item.fpsList||!item.fpsList.length)return null;
  if(currentFps&&currentRes&&item.raw===currentRes&&item.fpsList.indexOf(currentFps)!==-1)return currentFps;
  return item.fpsList[item.fpsList.length-1];
}

// ── API ───────────────────────────────────────────────────────────────────────────────
function sendControl(data,btn,msg){
  return fetch('/api/control',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)})
    .then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();})
    .then(function(resp){feedback(btn,true);showToast(msg||'OK',false);return resp;})
    .catch(function(e){feedback(btn,false);showToast('ERR:'+e.message,true);throw e;});
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
  return sendControl({streamAction:action},btn,msgs[action]||action);
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
    if(c.is_depth)continue;
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
function buildResolutionButtons(cap,currentRes,currentFps){
  var container=document.getElementById('btngroup-resolution');
  if(!container)return;

  var simplified=simplifyResolutions(cap,currentRes);
  if(!simplified.length){
    container.innerHTML='<span style="color:var(--muted)">Nenhuma resolução disponível</span>';
    return;
  }

  var html='';
  for(var i=0;i<simplified.length;i++){
    var item=simplified[i];
    var active=(item.raw===currentRes);
    var fps=getBestFpsForResolution(item,currentFps,currentRes);
    var text=resLabel(item.raw)+(fps?' · '+fps+'fps':'');
    html+='<button data-res="'+item.raw+'" title="'+item.raw+(fps?' @ '+fps+'fps':'')+'"'+(active?' class="active"':'')+
          ' onclick="setResolutionProfile(\''+item.raw+'\','+(fps||'null')+',this)">'+text+'</button>';
  }
  container.innerHTML=html;
}
function setResolutionProfile(res,fps,btn){
  var payload={resolution:res};
  var msg=resLabel(res);
  if(fps){
    payload.fps=fps;
    msg+=' · '+fps+'fps';
  }

  var wasStreaming=_isStreaming;

  return sendControl(payload,btn,msg)
    .then(function(){
      markActive('data-res',res);
      if(wasStreaming){
        return streamAction('restart',btn);
      }
    })
    .then(function(){
      if(wasStreaming)showToast(msg+' · stream reiniciada',false);
    });
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
    if(m==='off')continue;
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
  buildResolutionButtons(cap,cap.current_resolution,cap.current_fps);
  buildFocusModeButtons(cap.supported_af_modes||cap.af_modes||[],cap.current_af_mode);
  buildWBButtons(cap.supported_awb_modes||cap.awb_modes||[],cap.current_wb);
  updateOISCapability(cap.has_ois||false);
  buildOpticalZoomButtons(cap.lenses||null,cap.current_focal_length);
  showCard('card-postproc',cap.supports_manual_post_processing||cap.has_postproc||false);
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
  _isStreaming=!!s.streaming;

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
  setText('lbl-res',s.resolution ? resLabel(s.resolution) : s.resolution);
  setText('lbl-br',s.bitrate_kbps);

  var latEl=document.getElementById('lbl-lat');
  if(latEl&&s.latency_ms!==undefined){
    latEl.textContent=s.latency_ms+'ms';
    latEl.className=s.latency_ms<100?'lat-ok':s.latency_ms<300?'lat-warn':'lat-bad';
  }

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

  if(s.monitor){
    var m=s.monitor;
    setText('mon-iso',m.iso&&m.iso>0?m.iso:null);
    setText('mon-shutter',m.shutter_ns&&m.shutter_ns>0?formatShutter(m.shutter_ns):null);
    var afState=m.af_state;
    setText('mon-af',afState&&afState!=='unknown'?afState:null);
    setClass('mon-af',afState==='passive_focused'||afState==='FOCUSED'?'green':
                       afState==='passive_scan'||afState==='SEARCHING'?'yellow':null);
    var aeState=m.ae_state;
    setText('mon-ae',aeState&&aeState!=='unknown'?aeState:null);
    setClass('mon-ae',aeState==='converged'||aeState==='CONVERGED'?'green':
                       aeState==='searching'||aeState==='SEARCHING'?'yellow':null);
    setText('mon-rggb-r',m.rggb_r!=null?m.rggb_r.toFixed(3):null);
    setText('mon-rggb-b',m.rggb_b!=null?m.rggb_b.toFixed(3):null);
    setText('mon-rggb-gr',m.rggb_gr!=null?m.rggb_gr.toFixed(3):null);
    setText('mon-rggb-gb',m.rggb_gb!=null?m.rggb_gb.toFixed(3):null);
  }

  updateManualUI(s.manual_sensor||false);

  if(s.cameras&&_caps===null){
    _caps=s.cameras;
    buildCameraButtons(s.cameras,s.camera_id);
    applyCameraCapabilities(s.camera_id);
    _currentCamId=String(s.camera_id);
  }

  if(s.resolution)markActive('data-res',s.resolution);
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
        _isStreaming=false;
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
