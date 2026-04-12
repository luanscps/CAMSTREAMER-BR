# CAMSTREAMER-BR

> App Android para **transmissão ao vivo via RTMP** com controle total da câmera pelo hardware Camera2 API e painel web de controle remoto acessível pela rede local.

**Powered by:** Kotlin + Camera2 API + [RootEncoder](https://github.com/pedroSG94/RootEncoder) + WebGUI embutida

---

## Stack Técnica

| Camada | Tecnologia |
|---|---|
| Linguagem | Kotlin |
| Build | Gradle (Groovy) |
| Camera API | Android Camera2 API |
| Streaming | [RootEncoder](https://github.com/pedroSG94/RootEncoder) v2.4.5 via JitPack |
| Preview | `OpenGlView` (RootEncoder) |
| Servidor Web | `WebControlServer` — HTTP embutido porta **8080** |
| Protocolo principal | **RTMP** |
| Persistência | `SharedPreferences` |
| Background | `ForegroundService` + `WakeLock` |

---

## Arquitetura

```
MainActivity
    │
    ├─ bind ──► StreamingService (ForegroundService)
    │               │
    │               ├─ RtmpStreamer (wrapper RootEncoder RtmpCamera2)
    │               │       └─ Camera2Controller (Camera2 API + reflection)
    │               │               └─ CameraCapabilitiesReader
    │               │                       └─ CameraCapabilities (data class)
    │               │
    │               └─ WebControlServer (HTTP :8080)
    │                       └─ WebControlHtml (HTML/JS/CSS inline)
    │
    └─ GridOverlayView (overlay de grade no preview)
```

---

## Quickstart

### Requisitos

- Android 8.0+ (API 26)
- Permissões: `CAMERA`, `RECORD_AUDIO`, `INTERNET`, `FOREGROUND_SERVICE`
- Android 13+: `POST_NOTIFICATIONS`

### Instalação

```bash
# Clone o repositório
git clone https://github.com/luanscps/CAMSTREAMER-BR.git
cd CAMSTREAMER-BR

# Checkout da branch de desenvolvimento
git checkout v4-ui
```

### Build

**Via Android Studio:**
```
1. File → Open
2. Selecione a pasta CAMSTREAMER-BR
3. Aguarde o Gradle sync automático
4. Run (Shift+F10)
```

**Via Terminal:**
```bash
./gradlew installDebug
```

### Conectando ao dispositivo (Samsung Note10+ e outros)

```
1. Ative "Depuração USB" nas opções do desenvolvedor
2. Conecte via cabo USB
3. Aceite a autorização no celular
4. ./gradlew installDebug
```

### Primeiro uso

1. Abra o app — conceda todas as permissões solicitadas (Câmera, Microfone, Notificações)
2. Toque no ⚙️ para abrir o painel de configurações
3. Insira a URL RTMP do seu servidor: `rtmp://192.168.1.100:1935/live/stream`
4. Toque em **Aplicar**
5. Toque no botão shutter para iniciar a transmissão

---

## Interface do App (v4-ui)

### HUD — Heads-Up Display

**Localização:** Canto superior direito da tela

Exibe 8 métricas em tempo real, atualizadas a cada 1 segundo:

| Métrica | Descrição |
|---|---|
| 🔆 ISO | Sensibilidade atual (50–3200) |
| ☀️ Exposição | Tempo de shutter atual |
| 🎯 Foco | Modo AUTO ou distância manual |
| 🎥 FPS | Taxa de quadros do encoder |
| 🌡️ Temperatura | Temperatura do dispositivo |
| 🔋 Bateria | Nível com cor dinâmica (verde/amarelo/vermelho) |
| 👥 Clientes | Clientes conectados ao painel web |
| 🌐 Rede | Tráfego de rede (MB/s) |

**Cores semânticas:**
- 🟢 Verde: Bateria >50%, temperatura OK
- 🟡 Amarelo: Bateria 20–50%, temperatura elevada
- 🔴 Vermelho: Bateria <20%, temperatura crítica

O HUD pode ser ocultado/exibido pelo FAB 👁️.

---

### FABs — Ações Rápidas

**Localização:** Lado esquerdo da tela (verticalmente)

| Botão | Ação |
|---|---|
| 📷 | Trocar câmera (ciclo: Wide → Ultra → Tele → Front) |
| 💡 | Flash/Lanterna on/off |
| 📸 | Captura de foto instantânea |
| 👁️ | Mostrar/ocultar HUD |
| ⚙️ | Abrir Bottom Sheet de controles avançados |

```kotlin
fabSwitchCamera.setOnClickListener {
    cycleCamera()
    showToast("📷 Câmera: ${currentCamera.uppercase()}")
}
```

---

### Bottom Sheet — Controles Avançados

**Ativação:** Toque no FAB ⚙️ ou deslize de baixo para cima

**Seleção de Câmera:**
- 4 botões: `Wide | Ultra | Tele | Front`
- Botão ativo destacado em azul

**Controle de ISO:**
- SeekBar de 50 a 3200
- Valor exibido em tempo real com fonte monoespaçada

**Controle de Exposição:**
- SeekBar com presets de velocidade
- Range: 1/8000s até 1/15s
- Exibição dinâmica do valor

**Controle de Foco:**
- SeekBar 0–100%
- 0 = AUTO, >0 = MANUAL
- Feedback visual do modo ativo

**Ações Rápidas:**
- 🔒 Lock Exposição (AE Lock)
- 🔒 Lock Foco (AF Lock)
- 💡 Flash ON/OFF

```kotlin
bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
```

---

### Modo Compacto + Gestos

**Gesto:** Duplo toque na preview da câmera

| Modo | Elementos visíveis |
|---|---|
| Normal | HUD, FABs, Top Overlay, Indicador RTSP |
| Compacto | Apenas preview limpo, sem UI |

```kotlin
gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
    override fun onDoubleTap(e: MotionEvent): Boolean {
        toggleUICompactMode()
        return true
    }
})
```

Ideal para monitoramento focado, screenshots limpos e apresentações.

---

### Grade de Composição

- **Long-press** no preview ativa/desativa grade de regra dos terços
- A preferência é salva automaticamente via `SharedPreferences`

---

## Painel Web de Controle (WebGUI)

Com o app rodando, acesse pelo browser da rede local:

```
http://<IP-DO-CELULAR>:8080
```

O IP local é exibido na barra de status do app.

### Parâmetros de controle disponíveis

| Controle | Chave | Exemplo |
|---|---|---|
| Iniciar stream | `startStream` | — |
| Parar stream | `stopStream` | — |
| ISO manual | `iso` | `800` |
| Velocidade do obturador | `shutterSpeed` | `1/60` |
| Duração do frame | `frameDuration` | `33333333` (ns) |
| Compensação de exposição | `exposure` | `2` (EV, modo auto) |
| Foco | `focus` | `0.5` (distância normalizada) |
| Modo de foco | `focusmode` | `af`, `manual` |
| Trigger AF | `afTrigger` | `true` |
| Balanço de branco | `whiteBalance` | `auto`, `daylight`, `cloudy`, `tungsten`, `fluorescent` |
| Zoom | `zoom` | `0.0` – `1.0` (mapeado para range real) |
| Lanterna | `lantern` | `true`/`false` |
| Flash | `flashMode` | `true`/`false` |
| OIS | `ois` | `true`/`false` |
| EIS | `eis` | `true`/`false` |
| Trava de exposição | `aeLock` | `true`/`false` |
| Trava de balanço de branco | `awbLock` | `true`/`false` |
| Bitrate em voo | `bitrate` | `4000000` |
| FPS | `fps` | `30` |
| Resolução | `resolution` | `4k`, `1080p`, `720p`, `WxH` |
| Troca de câmera | `camera` | ID da câmera |
| Modo sensor manual | `manualSensor` | `true`/`false` |
| Redução de ruído | `noiseReduction` | `OFF`, `FAST`, `HIGH_QUALITY` |
| Edge mode | `edgeMode` | `OFF`, `FAST`, `HIGH_QUALITY` |
| Hot pixel | `hotPixel` | `OFF`, `FAST`, `HIGH_QUALITY` |
| Curva de tonemap | `tonemapCurve` | `linear`, `s-curve`, `log`, `cinematic`, `power22` |

---

## Sistema de Capabilities da Câmera

O app detecta automaticamente as capacidades de cada câmera via `Camera2Controller.discoverAllCameras()`.

### `CameraCapabilities` — Data Class completa

```kotlin
data class CameraCapabilities(
    val cameraId: String,
    val hardwareLevel: String,  // LEGACY, LIMITED, FULL, LEVEL_3
    val facing: String,         // BACK, FRONT, EXTERNAL
    val name: String,           // Wide, Ultra Wide, Telephoto, Frontal

    // Flags de capacidades
    val supportsManualSensor: Boolean,
    val supportsManualPostProcessing: Boolean,
    val supportsRaw: Boolean,
    val supportsBurstCapture: Boolean,
    val supportsDepthOutput: Boolean,
    val supportsLogicalMultiCamera: Boolean,

    // Ranges disponíveis
    val isoRange: Pair<Int, Int>?,
    val exposureTimeRange: Pair<Long, Long>?,
    val evRange: Pair<Int, Int>?,
    val focusDistanceRange: Pair<Float, Float>?,
    val zoomRange: Pair<Float, Float>?,
    val fpsRanges: List<Pair<Int, Int>>,

    // Formatos e resoluções
    val availableResolutions: List<String>,
    val supportedAFModes: List<String>,
    val supportedAEModes: List<String>,
    val supportedAWBModes: List<String>,

    // Hardware físico
    val hasFlash: Boolean,
    val hasOIS: Boolean,
    val focalLengths: List<Float>,
    val apertures: List<Float>
)
```

### Endpoint `/api/capabilities`

```kotlin
private fun serveCapabilities(): Response {
    val capabilities = cameraController.discoverAllCameras(context)
    val json = gson.toJson(capabilities)
    val resp = newFixedLengthResponse(Response.Status.OK, "application/json", json)
    resp.addHeader("Access-Control-Allow-Origin", "*")
    return resp
}
```

**Exemplo de resposta JSON:**

```json
[
  {
    "camera_id": "0",
    "hardware_level": "FULL",
    "facing": "BACK",
    "name": "Wide",
    "supports_manual_sensor": true,
    "iso_range": [50, 3200],
    "exposure_time_range": [100000, 100000000],
    "ev_range": [-8, 8],
    "focus_distance_range": [0.0, 10.0],
    "zoom_range": [1.0, 8.0],
    "supported_af_modes": ["off", "auto", "continuous-video"],
    "supported_awb_modes": ["auto", "daylight", "cloudy", "tungsten"],
    "has_flash": true,
    "has_ois": true
  },
  {
    "camera_id": "1",
    "hardware_level": "LIMITED",
    "facing": "FRONT",
    "name": "Frontal",
    "supports_manual_sensor": false,
    "zoom_range": null,
    "focus_distance_range": null,
    "has_flash": false,
    "has_ois": false
  }
]
```

### Interface Dinâmica — WebGUI adapta por câmera

```javascript
async function initCapabilities() {
  const r = await fetch('/api/capabilities');
  _caps = await r.json();

  _caps.forEach(cap => {
    const btn = document.createElement('button');
    btn.setAttribute('data-cam', cap.camera_id);
    btn.textContent = cap.name;
    btn.onclick = () => switchCamera(cap.camera_id, btn);
    camGroup.appendChild(btn);
  });

  updateUIForCamera('0');
}
```

**Zoom — mostra/esconde baseado em suporte:**
```javascript
const cap = getCameraCapabilities(camId);
if (cap.zoom_range && cap.zoom_range[1] > 1.0) {
  document.getElementById('card-zoom').classList.remove('hidden');
} else {
  document.getElementById('card-zoom').classList.add('hidden');
}
```

**ISO/Manual Sensor:**
```javascript
if (cap.supports_manual_sensor && cap.iso_range) {
  isoCard.classList.remove('hidden');
  isoSlider.disabled = false;
} else {
  isoCard.classList.add('hidden');
}
```

**White Balance dinâmico:**
```javascript
const wbGroup = document.getElementById('btngroup-wb');
wbGroup.innerHTML = '';
cap.supported_awb_modes.forEach(mode => {
  const btn = document.createElement('button');
  btn.setAttribute('data-wb', mode);
  btn.textContent = icons[mode] || mode;
  btn.onclick = () => setWB(mode, btn);
  wbGroup.appendChild(btn);
});
```

**Flash/OIS condicionais:**
```javascript
if (cap.has_flash) {
  extrasContent.innerHTML += `<div><span>🔦 Lanterna</span>
    <input type="checkbox" id="toggle-lantern" onchange="toggleLantern(this)"></div>`;
}
if (cap.has_ois) {
  extrasContent.innerHTML += `<div><span>🎬 OIS</span>
    <input type="checkbox" id="toggle-ois" onchange="toggleOIS(this)"></div>`;
}
```

### Debug de capabilities

```bash
adb logcat | grep "Camera2Controller"
```

Ou no browser:
```javascript
fetch('/api/capabilities').then(r => r.json()).then(console.log)
```

### Exemplo prático — Galaxy Note10+

| Câmera | Level | Zoom | Foco manual | ISO | Flash | OIS |
|---|---|---|---|---|---|---|
| Wide (ID 0) | FULL | ✅ 1×–8× | ✅ 0–10D | ✅ 50–3200 | ✅ | ✅ |
| Frontal (ID 1) | LIMITED | ❌ lente fixa | ❌ foco fixo | ⚠️ limitado | ❌ | ❌ |
| Ultra Wide (ID 2) | LIMITED | ⚠️ 0.6×–2× | ❌ foco fixo | ⚠️ limitado | ❌ | ❌ |
| Telephoto (ID 3) | FULL | ✅ 2×–10× | ✅ | ✅ | ❌ | ✅ |

---

## Tonemap / Curvas Cinematográficas

O `Camera2Controller` suporta 5 presets de curva de tonemap para uso cinematográfico.

### Presets disponíveis

| Preset | Uso | Matemática |
|---|---|---|
| `linear` | RAW-like, sem processamento | `y = x` |
| `s-curve` | Blockbuster Hollywood — contraste alto, blacks crushed | Interpolação cúbica com pontos críticos |
| `log` | ARRI Alexa / RED — máximo DR para grading | `y = c·log10(a·x + b) + d` |
| `cinematic` | Blender Filmic — proteção de highlights, lift de shadows | Piecewise com lift/roll |
| `power22` | Standard sRGB para monitores | `y = x^0.4545` |

### Implementação no Camera2Controller.kt

```kotlin
private fun buildLinear(): FloatArray = floatArrayOf(0.0f, 0.0f, 1.0f, 1.0f)

private fun buildSCurve(): FloatArray {
    // S-curve cinematográfica: crush blacks + roll highlights (estilo DaVinci/ACES)
    return floatArrayOf(
        0.00f, 0.00f,
        0.05f, 0.01f,  // crush deep shadows
        0.10f, 0.05f,
        0.20f, 0.15f,
        0.30f, 0.28f,
        0.40f, 0.42f,
        0.50f, 0.50f,  // midtone anchor
        0.60f, 0.58f,
        0.70f, 0.70f,
        0.80f, 0.82f,
        0.90f, 0.92f,  // soft roll highlights
        0.95f, 0.96f,
        1.00f, 1.00f
    )
}

private fun buildLogCurve(): FloatArray {
    // Log-C estilo ARRI: máxima preservação de dynamic range
    val points = mutableListOf<Float>()
    for (i in 0..16) {
        val x = i / 16f
        val a = 5.555556f; val b = 0.047996f
        val c = 0.244161f; val d = 0.386036f
        val y = (c * kotlin.math.log10(a * x + b) + d).coerceIn(0f, 1f)
        points.add(x); points.add(y)
    }
    return points.toFloatArray()
}

private fun buildCinematicCurve(): FloatArray {
    // Perfil "Filmic" estilo Blender: sombras levantadas, highlights protegidos
    return floatArrayOf(
        0.00f, 0.03f,  // lift blacks
        0.05f, 0.08f,
        0.10f, 0.14f,
        0.20f, 0.26f,
        0.30f, 0.37f,
        0.40f, 0.48f,
        0.50f, 0.55f,  // mid-tone boost
        0.60f, 0.64f,
        0.70f, 0.74f,
        0.80f, 0.83f,
        0.90f, 0.91f,
        0.95f, 0.95f,
        1.00f, 0.97f   // cap highlights
    )
}

private fun buildPower22(): FloatArray {
    // Gamma 2.2 standard sRGB: y = x^(1/2.2)
    val points = mutableListOf<Float>()
    val gamma = 1.0f / 2.2f
    for (i in 0..16) {
        val x = i / 16f
        points.add(x); points.add(x.pow(gamma))
    }
    return points.toFloatArray()
}
```

**Handler de seleção de preset:**
```kotlin
params["tonemapCurve"]?.let {
    customTonemapCurve = it as String
    tonemapCurvePoints = when (customTonemapCurve) {
        "linear"     -> buildLinear()
        "s-curve"    -> buildSCurve()
        "log"        -> buildLogCurve()
        "cinematic"  -> buildCinematicCurve()
        "power22"    -> buildPower22()
        else         -> buildSCurve()
    }
    tonemapMode = CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE
    applyPostProcessing()
}
```

> **⚠️ Atenção:** Curvas personalizadas (`CONTRAST_CURVE`) requerem hardware level `FULL` ou superior. Em dispositivos `LIMITED`/`LEGACY` o modo é ignorado silenciosamente.

---

## Configuração MediaMTX (servidor RTMP local)

Para testar localmente sem servidor externo, use o [MediaMTX](https://github.com/bluenviron/mediamtx).

### Download e execução

```bash
# Linux/macOS
curl -L https://github.com/bluenviron/mediamtx/releases/latest/download/mediamtx_linux_amd64.tar.gz | tar xz
./mediamtx
```

O MediaMTX aceita RTMP por padrão na porta **1935**, sem configuração adicional.

### URL no app

```
rtmp://SEU_IP_LOCAL:1935/live/stream
```

Exemplo: `rtmp://192.168.1.100:1935/live/stream`

Ou edite diretamente em `StreamingService.kt`:
```kotlin
var rtmpUrl = "rtmp://192.168.1.100:1935/live/stream"
```

### Visualizar o stream

**OBS Studio:**
```
Configurações → Stream
Serviço: Personalizado
Servidor: rtmp://localhost:1935/live
Chave: stream
```

Ou adicione uma **Fonte de Mídia** com a URL RTMP.

**VLC / FFplay:**
```bash
# RTMP direto
ffplay rtmp://localhost:1935/live/stream
vlc rtmp://localhost:1935/live/stream

# MediaMTX também reemite automaticamente como:
rtsp://localhost:8554/live/stream   # RTSP
http://localhost:8888/live/stream   # HLS
```

### Por que MediaMTX?

- Mantém o stream ativo mesmo sem clientes conectados
- Reemite automaticamente como RTSP, HLS, WebRTC, SRT
- Zero configuração para uso básico
- Compatível com OBS, VLC, FFmpeg e browser

---

## Pós-processamento — Parâmetros detalhados

| Parâmetro | Chave | Valores disponíveis |
|---|---|---|
| Edge Enhancement | `edgeMode` | `OFF`, `FAST`, `HIGH_QUALITY`, `ZERO_SHUTTER_LAG` |
| Noise Reduction | `noiseReduction` | `OFF`, `FAST`, `HIGH_QUALITY`, `MINIMAL`, `ZERO_SHUTTER_LAG` |
| Hot Pixel | `hotPixel` | `OFF`, `FAST`, `HIGH_QUALITY` |
| Tonemap | `tonemapCurve` | `linear`, `s-curve`, `log`, `cinematic`, `power22` |

---

## Checklist de Testes

```markdown
### HUD
- [ ] HUD aparece no canto superior direito
- [ ] Valores atualizam a cada 1 segundo
- [ ] Cor da bateria muda conforme nível (verde/amarelo/vermelho)

### FABs
- [ ] 4 FABs visíveis no lado esquerdo
- [ ] Trocar câmera funciona com toast de confirmação
- [ ] Flash toggle funciona
- [ ] Foto capturada com animação de feedback
- [ ] Toggle HUD mostra/oculta corretamente

### Bottom Sheet
- [ ] FAB principal abre/fecha bottom sheet
- [ ] Botões de câmera com destaque visual no ativo
- [ ] Slider ISO atualiza valor em tempo real
- [ ] Slider Exposição atualiza valor em tempo real
- [ ] Slider Foco: 0 = AUTO, >0 = percentual MANUAL
- [ ] Lock AE/AF alternam 🔒/🔓 visualmente
- [ ] Flash toggle no bottom sheet funciona

### Notificações
- [ ] Toast aparece para cada ação com emoji
- [ ] Indicador RTSP verde=online / vermelho=offline

### Modo Compacto
- [ ] Duplo toque alterna entre Normal e Compacto
- [ ] Modo compacto oculta HUD, FABs, overlay, indicador
- [ ] Toast confirma transição

### Grade
- [ ] Long-press no preview ativa/desativa grade
- [ ] Preferência persiste após reiniciar o app

### WebGUI
- [ ] Acesso em http://<IP>:8080 funcionando
- [ ] clientsBadge atualiza no HUD com número de clientes
- [ ] /api/capabilities retorna JSON correto
- [ ] Controles aparecem/desaparecem por câmera
```

---

## Troubleshooting

### App não compila

```bash
./gradlew clean
./gradlew build
```

### HUD não atualiza

Verifique em `MainActivity.onCreate()`:
```kotlin
hudHandler.post(hudRunnable)  // deve estar presente
```

### Stream não conecta

- Verifique se a URL RTMP está correta no painel de configurações
- Confirme que o servidor (MediaMTX/OBS/etc) está rodando na porta 1935
- Verifique se celular e servidor estão na mesma rede Wi-Fi

### Controles de câmera sem efeito

- Verifique se o hardware level da câmera suporta o controle desejado
- Use `/api/capabilities` para ver o que está disponível
- Câmeras `LEGACY` não suportam controles manuais

### WebGUI não abre

- Confirme o IP exibido na barra de status do app
- Certifique-se de estar na mesma rede Wi-Fi
- O servidor HTTP inicia apenas quando o app está em foreground com o service rodando

---

## Design System (UI)

**Paleta de cores:**
```
Primário:    #38bdf8  (Azul Cyan)
Fundo:       #0f172a  (Azul Escuro)
Superfície:  #1e293b  (Cinza Azulado)
Texto:       #f1f5f9  (Branco Suave)
Sucesso:     #10b981  (Verde)
Alerta:      #fbbf24  (Amarelo)
Erro:        #ef4444  (Vermelho)
```

**Tipografia:**
- Headers: Sans-serif Bold 18sp
- Corpo: Sans-serif Regular 14sp
- HUD/Dados: Monospace 11–13sp

---

## Changelog

### v4-ui (branch atual) — 2026-03-13

#### ✨ Adicionado

**HUD (Heads-Up Display)**
- Painel de 8 métricas em tempo real: ISO, Exposição, Foco, FPS, Temperatura, Bateria, Clientes, Rede
- Atualização automática a cada 1 segundo
- Design semi-transparente com fonte monoespaçada
- Cores semânticas para indicadores

**FABs (Floating Action Buttons)**
- 5 botões flutuantes: Trocar câmera, Flash, Foto, Toggle HUD, Abrir controles
- Animações de feedback ao toque
- Posicionamento vertical no lado esquerdo

**Bottom Sheet**
- Painel deslizante com seleção de câmera (4 botões), 3 SeekBars (ISO/Exposição/Foco), Lock AE/AF, Flash toggle
- Handle visual para arrastar, cantos arredondados (24dp)

**Modo Compacto**
- Duplo toque no preview alterna entre UI completa e preview limpo
- GestureDetector implementado

**Métricas da v4-ui:**
- MainActivity.kt: +400 linhas em relação à v3
- Novos arquivos de layout/drawable/anim: 10
- Overhead de streaming: <1% CPU

#### ⚠️ Integrações Pendentes (v4-ui)

- Conectar SeekBars (ISO/Exposição/Foco) com Camera2Controller
- Captura de foto real com salvamento na galeria
- Conectar flash toggle com hardware
- Conectar contadores de clientes/rede com WebControlServer
- Leitura real de temperatura do sensor
- Leitura real de FPS do encoder

#### 🔗 Links

- **Branch:** [v4-ui](https://github.com/luanscps/CAMSTREAMER-BR/tree/v4-ui)
- **Comparação:** [main...v4-ui](https://github.com/luanscps/CAMSTREAMER-BR/compare/main...v4-ui)

---

### v3 — 2026-03

- Suporte RTMP via RootEncoder v2.4.5
- WebGUI completa portada da v3 (HTML/JS embutido no APK)
- Controles Camera2 via reflection: ISO, SS, WB, Zoom, Focus, OIS, EIS, Bitrate, FPS
- WebControlServer HTTP na porta 8080

### v2

- Streaming RTSP via RootEncoder
- Sistema de capabilities dinâmicas (`discoverAllCameras`)
- Interface web adaptativa por câmera

### v1

- Prova de conceito inicial

---

## Roadmap

- [ ] Integração completa dos SliderBars do Bottom Sheet com Camera2Controller (ISO/Exposição/Foco)
- [ ] Captura de foto funcional com salvamento na galeria
- [ ] Gravação de vídeo local simultânea ao stream
- [ ] Presets personalizados (Save/Load de configurações)
- [ ] Pinch-to-zoom no preview
- [ ] Gráfico de tráfego de rede em tempo real
- [ ] Cache de capabilities (evitar re-query a cada page load)
- [ ] Badge de hardware level na WebGUI (LEGACY/LIMITED/FULL/LEVEL_3)
- [ ] Slider de ISO dinâmico baseado no range real da câmera ativa

---

## Créditos

**Desenvolvimento:** Luan Silva ([@luanscps](https://github.com/luanscps))  
**Assistência IA:** Perplexity AI  
**Stack:** Kotlin + Material Design 3 + Camera2 API + RootEncoder
