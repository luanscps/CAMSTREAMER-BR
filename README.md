# CAMSTREAMER-BR

> App Android para **transmissão ao vivo via RTMP** com controle total da câmera pelo hardware Camera2 API e painel web de controle remoto acessível pela rede local.

**Powered by:** Kotlin + Camera2 API + [RootEncoder](https://github.com/pedroSG94/RootEncoder) + WebGUI embutida

---

## Stack Técnica

| Camada | Tecnologia |
|---|---|
| Linguagem | Kotlin |
| Build | Gradle (Groovy + KTS) |
| Camera API | Android Camera2 API |
| Streaming | [RootEncoder](https://github.com/pedroSG94/RootEncoder) v2.4.5 via JitPack |
| Preview | `OpenGlView` (RootEncoder) |
| Servidor Web | `WebControlServer` — NanoHTTPD embutido porta **8080** |
| Protocolo principal | **RTMP** |
| Captura still RAW | `DngCreator` + `ImageReader (RAW_SENSOR)` |
| Serialização JSON | Gson (snake_case) |
| Persistência | `SharedPreferences` |
| Background | `ForegroundService` + `WakeLock` |
| Autenticação WebGUI | Cookie de sessão via `WebControlAuth` |
| Heartbeat remoto | `HeartbeatService` + `HeartbeatLoop` |
| Comandos remotos | `RemoteCommandsService` + `CamuiRemoteCommandExecutor` |

---

## Arquitetura

```
CamStreamerApp (Application)
    │
    └─► SplashActivity → LoginActivity / RegisterActivity
                │
                └─► MainActivity
                        │
                        ├─ bind ──► StreamingService (ForegroundService)
                        │               │
                        │               ├─ RtmpStreamer (wrapper RootEncoder RtmpCamera2)
                        │               │       └─ Camera2Controller (Camera2 API + reflexão)
                        │               │               ├─ CameraCapabilitiesReader → CameraCapabilities
                        │               │               ├─ RawCaptureManager (DNG pipeline)
                        │               │               ├─ YuvFrameProcessor (processamento YUV)
                        │               │               └─ DepthFusionProcessor (depth output)
                        │               │
                        │               └─ WebControlServer (NanoHTTPD :8080)
                        │                       ├─ WebControlAuth (sessões + cookies)
                        │                       ├─ WebControlApi (rotas HTTP)
                        │                       └─ WebControlHtml (HTML/JS/CSS inline → assets)
                        │
                        ├─ GridOverlayView (overlay regra dos terços)
                        ├─ HeartbeatService (keepalive remoto)
                        └─ RemoteCommandsService → CamuiRemoteCommandExecutor
```

### Mapa de arquivos Kotlin

| Arquivo | Responsabilidade |
|---|---|
| `CamStreamerApp.kt` | Application class (inicialização global) |
| `SplashActivity.kt` | Splash + redirect para Login ou MainActivity |
| `LoginActivity.kt` | Autenticação de usuário |
| `RegisterActivity.kt` | Cadastro de usuário |
| `MainActivity.kt` | HUD, FABs, Bottom Sheet, GestureDetector, GridOverlay |
| `StreamingService.kt` | ForegroundService — ciclo de vida do stream |
| `RtmpStreamer.kt` | Wrapper do RootEncoder `RtmpCamera2` |
| `Camera2Controller.kt` | Núcleo da câmera — ISO, SS, WB, Zoom, Focus, OIS, EIS, Tonemap, RAW |
| `CameraCapabilitiesReader.kt` | Lê `CameraCharacteristics` e monta `CameraCapabilities` |
| `CameraCapabilities.kt` | Data class com todas as capacidades de um sensor |
| `RawCaptureManager.kt` | Pipeline DNG: `DngCreator` em `HandlerThread` dedicada |
| `YuvFrameProcessor.kt` | Processamento de frames YUV_420_888 |
| `DepthFusionProcessor.kt` | Fusão de depth output do sensor |
| `WebControlServer.kt` | NanoHTTPD — roteamento de requisições HTTP |
| `WebControlApi.kt` | Handlers das rotas: status, control, capabilities, RAW |
| `WebControlAuth.kt` | Geração e validação de cookies de sessão |
| `WebControlHtml.kt` | Serve `index.html` e assets da WebGUI |
| `GridOverlayView.kt` | View de grade de regra dos terços |
| `HeartbeatLoop.kt` | Loop de heartbeat periódico |
| `HeartbeatService.kt` | ForegroundService para heartbeat remoto |
| `RemoteCommandsService.kt` | Recebe e enfileira comandos remotos |
| `CamuiRemoteCommandExecutor.kt` | Executa os comandos recebidos no service |

---

## Quickstart

### Requisitos

- Android 8.0+ (API 26)
- Permissões: `CAMERA`, `RECORD_AUDIO`, `INTERNET`, `FOREGROUND_SERVICE`
- Android 13+: `POST_NOTIFICATIONS`
- Para salvar DNG em Android ≤ 9: `WRITE_EXTERNAL_STORAGE` (maxSdkVersion=28)

### Instalação

```bash
# Clone o repositório
git clone https://github.com/luanscps/CAMSTREAMER-BR.git
cd CAMSTREAMER-BR

# Checkout da branch de desenvolvimento ativa
git checkout v5-CAMUI
```

### Build

**Via Android Studio:**
```
1. File → Open
2. Selecione a pasta CAMSTREAMER-BR
3. Aguarde o Gradle sync automático
4. Run → Run 'app' (Shift+F10)
```

**Via Terminal:**
```bash
./gradlew installDebug
```

### Conectando ao dispositivo

```bash
1. Ative "Depuração USB" nas opções do desenvolvedor
2. Conecte via cabo USB
3. Aceite a autorização no celular
4. ./gradlew installDebug
```

### Primeiro uso

1. Abra o app — conceda todas as permissões solicitadas
2. Faça login ou cadastro
3. Toque no ⚙️ para abrir o painel de configurações
4. Insira a URL RTMP: `rtmp://192.168.1.100:1935/live/stream`
5. Toque em **Aplicar** e depois no botão shutter para iniciar

---

## Como o streaming funciona

Este app **publica** vídeo para um destino **RTMP**. Isso significa que ele **não é** o servidor final de distribuição: ele atua como **encoder/publicador** e precisa de um servidor ou software que **receba** a transmissão.

Em outras palavras:

```text
CAMSTREAMER-BR (Android) ──RTMP publish──► Servidor RTMP (ex: MediaMTX) ──► Clientes/players
```

### O que você precisa para funcionar

Você precisa de **um receptor RTMP** acessível pela rede. Exemplos:

- **MediaMTX** — opção recomendada para uso local, testes e distribuição multiprotocolo.
- **Nginx + RTMP module**.
- **Wowza**, **Ant Media** ou outro servidor compatível com RTMP.

> Sem um servidor RTMP, o app não tem para onde enviar o stream. Nesse caso, apertar “Start Stream” apenas tentará conectar à URL configurada e falhará se não houver um endpoint ouvindo.

### Recomendação prática

Para desenvolvimento, QA e uso em rede local, a opção mais simples é usar **MediaMTX**. Ele recebe RTMP e pode redistribuir o mesmo stream como **RTSP**, **HLS** e **WebRTC**, facilitando testes com VLC, navegador, OBS e FFplay.

## Como receber com MediaMTX

### 1. Suba o servidor MediaMTX

**Linux/macOS**

```bash
curl -L https://github.com/bluenviron/mediamtx/releases/latest/download/mediamtx_linux_amd64.tar.gz | tar xz
./mediamtx
```

**Windows**

1. Baixe o release do MediaMTX no GitHub.
2. Extraia o `.zip`.
3. Execute `mediamtx.exe`.

### 2. Defina a URL RTMP no app

No CAMSTREAMER-BR, configure a URL no formato:

```text
rtmp://IP_DO_SERVIDOR:1935/live/stream
```

Exemplo:

```text
rtmp://192.168.1.100:1935/live/stream
```

Onde:

- `192.168.1.100` = IP do computador/servidor rodando o MediaMTX
- `1935` = porta padrão do RTMP
- `live` = path/application
- `stream` = nome/chave do stream

### 3. Inicie a transmissão no app

Depois de aplicar a URL:

1. Abra o preview da câmera.
2. Toque no botão de iniciar stream.
3. O app vai publicar para o MediaMTX.

Se a conexão estiver correta, o stream passa a ficar disponível para leitura nos protocolos abaixo.

## Como consumir o stream recebido

Depois que o MediaMTX receber o stream `live/stream`, você pode consumi-lo de várias formas.

### RTMP

```text
rtmp://IP_DO_SERVIDOR:1935/live/stream
```

Exemplos:

```bash
ffplay rtmp://192.168.1.100:1935/live/stream
vlc rtmp://192.168.1.100:1935/live/stream
```

### RTSP

```text
rtsp://IP_DO_SERVIDOR:8554/live/stream
```

Exemplos:

```bash
ffplay rtsp://192.168.1.100:8554/live/stream
vlc rtsp://192.168.1.100:8554/live/stream
```

### HLS

```text
http://IP_DO_SERVIDOR:8888/live/stream
```

Exemplo:

```text
http://192.168.1.100:8888/live/stream
```

### WebRTC

```text
http://IP_DO_SERVIDOR:8889/live/stream
```

Exemplo:

```text
http://192.168.1.100:8889/live/stream
```

## Exemplos práticos de consumo

### VLC

Abra:

- `Mídia` → `Abrir fluxo de rede`
- Cole uma das URLs (`rtsp://...`, `rtmp://...` ou `http://...`)

### FFplay

```bash
ffplay rtsp://192.168.1.100:8554/live/stream
```

ou

```bash
ffplay rtmp://192.168.1.100:1935/live/stream
```

### Navegador

- Para **HLS**: `http://192.168.1.100:8888/live/stream`
- Para **WebRTC**: `http://192.168.1.100:8889/live/stream`

### OBS Studio como viewer/input

No OBS, você pode adicionar uma **Fonte de Mídia** ou **VLC Video Source** apontando para:

- `rtsp://192.168.1.100:8554/live/stream`
- ou `rtmp://192.168.1.100:1935/live/stream`

Isso é útil para usar o celular como câmera sem fio dentro de uma cena OBS.

## Cenários de uso recomendados

### Cenário 1 — Teste local simples

```text
Celular com CAMSTREAMER-BR ──► MediaMTX no PC ──► VLC / Navegador / OBS
```

### Cenário 2 — Celular como câmera para OBS

```text
Celular ──RTMP──► MediaMTX ──RTSP/RTMP──► OBS
```

Use o OBS para compor overlays, cenas, gravação local e retransmissão.

### Cenário 3 — Distribuição web

```text
Celular ──RTMP──► MediaMTX ──HLS/WebRTC──► Navegadores na rede
```

## Troubleshooting de ingest RTMP

### O app não conecta ao iniciar stream

Verifique:

- Se o MediaMTX está em execução.
- Se a porta **1935** está aberta.
- Se o IP configurado no app está correto.
- Se celular e servidor estão na mesma rede.
- Se a URL está no formato `rtmp://IP:1935/live/stream`.

### O stream publica mas não abre no navegador

- Teste primeiro via **RTSP** ou **RTMP** no VLC/FFplay.
- Para browser, use **8888** para HLS e **8889** para WebRTC.
- Confirme que o path do stream é o mesmo (`live/stream`).

---

## Interface do App (v5-CAMUI)

### HUD — Heads-Up Display

**Localização:** Canto superior direito da tela

Exibe 8 métricas em tempo real (atualização a cada 1s):

| Métrica | Descrição |
|---|---|
| 🔆 ISO | Sensibilidade atual |
| ☀️ Exposição | Tempo de shutter atual |
| 🎯 Foco | Modo AUTO ou distância manual |
| 🎥 FPS | Taxa de quadros do encoder |
| 🌡️ Temperatura | Temperatura do dispositivo |
| 🔋 Bateria | Nível com cor semântica |
| 👥 Clientes | Clientes conectados ao painel web |
| 🌐 Rede | Tráfego de rede em MB/s |

**Cores semânticas:** 🟢 >50% / temp OK · 🟡 20–50% / temp elevada · 🔴 <20% / temp crítica

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

---

### Bottom Sheet — Controles Avançados

**Ativação:** Toque no FAB ⚙️ ou deslize de baixo para cima

- **Seleção de câmera:** 4 botões (Wide / Ultra / Tele / Front), ativo destacado em azul
- **ISO:** SeekBar 50–3200, valor em tempo real com fonte monoespaçada
- **Exposição:** SeekBar 1/8000s–1/15s
- **Foco:** SeekBar 0–100% — 0 = AUTO, >0 = distância MANUAL
- **Locks:** AE Lock 🔒 / AF Lock 🔒
- **Flash:** toggle ON/OFF

---

### Modo Compacto + Gestos

- **Duplo toque** no preview alterna entre UI completa e preview limpo (sem HUD/FABs)
- **Long-press** no preview ativa/desativa grade de regra dos terços (preferência salva em `SharedPreferences`)

---

## Painel Web de Controle (WebGUI)

Com o app em foreground e serviço rodando, acesse:

```
http://<IP-DO-CELULAR>:8080
```

O IP local é exibido na barra de status do app.

> ⚠️ **Autenticação:** A WebGUI usa cookie de sessão via `WebControlAuth`. Faça login com as credenciais configuradas no app antes de usar os controles.

---

## API HTTP — Rotas disponíveis

Todas as rotas retornam JSON com `Access-Control-Allow-Origin: *`.

### `GET /api/status`

Retorna estado completo do sistema. Payload principal:

```json
{
  "streaming": true,
  "rtmp_url": "rtmp://192.168.1.100:1935/live/stream",
  "camera_id": "0",
  "resolution": "1920x1080",
  "bitrate_kbps": 4000,
  "fps": 30,
  "focus_mode": "continuous-video",
  "focus_dist": 0.0,
  "focus_distance_calibration": "CALIBRATED",
  "iso": 800,
  "exposure_ns": 16666666,
  "frame_duration_ns": 33333333,
  "manual_sensor": false,
  "focal_length": 4.30,
  "aperture": 1.5,
  "zoom": 0.0,
  "wb": "auto",
  "ois": true,
  "eis": false,
  "ae_lock": false,
  "awb_lock": false,
  "torch": false,
  "edge": "high_quality",
  "nr": "high_quality",
  "hot_pixel": "high_quality",
  "rggb_enabled": false,
  "rggb_r": 1.0, "rggb_gr": 1.0, "rggb_gb": 1.0, "rggb_b": 1.0,
  "optical_zoom_index": 0,
  "optical_zoom_levels": ["0.6x", "1x", "3x", "10x"],
  "monitor": {
    "iso": 800,
    "shutter_ns": 16666666,
    "af_state": 2,
    "ae_state": 2,
    "rggb_r": 2.1, "rggb_gr": 1.0, "rggb_gb": 1.0, "rggb_b": 1.8
  },
  "advanced_vision": {
    "yuv_enabled": false,
    "depth_enabled": false,
    "last_yuv_ts_ns": 0,
    "depth_mean_mm": 0.0,
    "depth_min_mm": 0.0,
    "depth_max_mm": 0.0,
    "raw_ready": false,
    "raw_filename": ""
  },
  "cameras": [ /* array de CameraCapabilities */ ]
}
```

---

### `POST /api/control`

Envia um objeto JSON no body para alterar configurações em runtime.

| Parâmetro | Tipo | Exemplo | Observação |
|---|---|---|---|
| `streamAction` | String | `"start"`, `"stop"`, `"restart"` | Controla o stream |
| `rtmpUrl` | String | `"rtmp://..."` | Altera URL e reinicia stream |
| `camera` / `camera_id` | String | `"0"`, `"2"` | Troca câmera (dispatch no mainLooper) |
| `iso` | Int | `800` | ISO manual (requer `manualSensor=true`) |
| `shutterSpeed` / `exposure_ns` | String/Long | `"1/60"` / `16666666` | Velocidade do obturador |
| `frameDuration` | Long | `33333333` | Duração do frame em ns |
| `exposure` | Int | `2` | Compensação EV (modo auto) |
| `focus` | Float | `0.5` | Distância de foco normalizada |
| `focusmode` | String | `"af"`, `"manual"` | Modo de foco |
| `afTrigger` | Boolean | `true` | Dispara AF one-shot |
| `whiteBalance` | String | `"auto"`, `"daylight"`, `"cloudy"`, `"tungsten"`, `"fluorescent"`, `"shade"` | Modo AWB |
| `zoom` | Float | `0.0`–`1.0` | Zoom mapeado para range real do sensor |
| `lantern` | Boolean | `true`/`false` | Flash/lanterna |
| `flashMode` | Boolean | `true`/`false` | Flash de captura |
| `ois` | Boolean | `true`/`false` | Optical Image Stabilization |
| `eis` | Boolean | `true`/`false` | Electronic Image Stabilization |
| `aeLock` | Boolean | `true`/`false` | Trava de exposição automática |
| `awbLock` | Boolean | `true`/`false` | Trava de balanço de branco |
| `bitrate` | Int | `4000000` | Bitrate em bps — aplica stopStream+prepareVideo+startStream |
| `fps` | Int | `30` | FPS do encoder |
| `resolution` | String | `"4k"`, `"1080p"`, `"720p"`, `"WxH"` | Resolução — aplica restart do stream |
| `manualSensor` | Boolean | `true`/`false` | Habilita/desabilita sensor manual |
| `noiseReduction` | String | `"OFF"`, `"FAST"`, `"HIGH_QUALITY"`, `"MINIMAL"` | NR do ISP |
| `edgeMode` | String | `"OFF"`, `"FAST"`, `"HIGH_QUALITY"` | Edge enhancement |
| `hotPixel` | String | `"OFF"`, `"FAST"`, `"HIGH_QUALITY"` | Correção hot pixel |
| `tonemapCurve` | String | `"linear"`, `"s-curve"`, `"log"`, `"cinematic"`, `"power22"` | Curva de tonemap (requer FULL) |
| `rggbEnabled` | Boolean | `true`/`false` | Habilita ganhos RGGB manuais |
| `rggb_r` / `rggb_gr` / `rggb_gb` / `rggb_b` | Float | `1.8` | Ganhos individuais do canal RGGB |
| `yuvCapture` | Boolean | `true`/`false` | Habilita processador YUV |
| `depthFusion` | Boolean | `true`/`false` | Habilita fusão depth |

> **Cast seguro:** todos os parâmetros booleanos e inteiros aceitam tanto `true`/`false` JSON quanto `0`/`1` numérico — o `Camera2Controller` converte internamente para evitar `ClassCastException`.

---

### `GET /api/capabilities`

Retorna array com as capacidades de cada câmera detectada.

```json
[
  {
    "camera_id": "0",
    "hardware_level": "FULL",
    "facing": "BACK",
    "name": "Wide",
    "supports_manual_sensor": true,
    "supports_raw": true,
    "iso_range": [50, 3200],
    "exposure_time_range": [100000, 100000000],
    "ev_range": [-8, 8],
    "focus_distance_range": [0.0, 10.0],
    "zoom_range": [1.0, 8.0],
    "streaming_resolutions": ["3840x2160", "1920x1080", "1280x720"],
    "raw_resolution": "4032x3024",
    "supported_af_modes": ["off", "auto", "continuous-video"],
    "supported_awb_modes": ["auto", "daylight", "cloudy", "tungsten"],
    "has_flash": true,
    "has_ois": true,
    "focal_lengths": [4.30],
    "apertures": [1.5]
  }
]
```

> **`streaming_resolutions`** contém apenas as resoluções filtradas para streaming (máx 3, aspecto 16:9: 4K/1080p/720p). **`raw_resolution`** é o tamanho real do `ImageReader RAW_SENSOR` — `null` se a câmera não suportar RAW.

---

### `POST /api/raw/capture`

Dispara captura RAW still. Retorna `202 Accepted` **imediatamente** sem bloquear.

```json
{ "status": "capturing", "message": "Captura iniciada. Consulte GET /api/raw/result?poll=1" }
```

**Importante:** só funciona em câmeras com `supports_raw: true` no `/api/capabilities`.

---

### `GET /api/raw/result`

**Com `?poll=1`** — polling leve (500ms recomendado), retorna JSON:

```json
{ "ready": false, "filename": "", "size_kb": 0 }
// ou, quando pronto:
{ "ready": true, "filename": "raw_20260607_143022.dng", "size_kb": 22800 }
```

**Sem `?poll=1`** — download binário do DNG (`Content-Type: image/x-adobe-dng`) se pronto, ou `404` se ainda não disponível. O cache é zerado após o download para evitar entregar a captura anterior em nova requisição.

---

## Pipeline de Captura RAW DNG

O `Camera2Controller` gerencia uma sessão dedicada para captura RAW:

```
1. POST /api/raw/capture
   └─► Camera2Controller.captureRawStill()
           │
           ├─ Cria ImageReader(RAW_SENSOR, rawWidth, rawHeight, maxImages=4)
           ├─ Abre câmera com rawSurface inclusa na sessão (via cam2.addImageListener)
           ├─ Dispara session.capture() one-shot (TEMPLATE_STILL_CAPTURE)
           │
           ├─ onImageAvailable → guarda Image ABERTA na pendingImageQueue
           ├─ onCaptureCompleted → drena fila, chama RawCaptureManager.processImage(image, result)
           │
           └─ RawCaptureManager.rawHandler.post {
                   DngCreator(characteristics, result).writeImage(outputStream, image)
                   → image.close()  ← único ponto de close, após DngCreator terminar
                   → onRawFrame(RawFrame)  ← notifica Camera2Controller
              }

2. Camera2Controller.onRawFrame()
   ├─ lastRawDngBytes = frame.dngBytes
   ├─ lastRawFilename = "raw_YYYYMMDD_HHmmss.dng"
   ├─ saveToMediaStore()  ← salva DNG em DCIM/CAMSTREAMER/
   ├─ captureLatch.countDown()  ← libera thread de finalização
   └─ startPreview() + startStream()  ← retoma stream após sessão RAW

3. GET /api/raw/result?poll=1  ← polling 500ms pela WebGUI
4. GET /api/raw/result          ← download do DNG binário
```

> **Requisitos de hardware:** `supportsRaw = true` (campo em `CameraCapabilities`). **Não** requer `supportsManualSensor` — câmeras `LIMITED` que suportam `RAW_SENSOR` funcionam normalmente.

> **⚠️ Tonemap personalizado (`CONTRAST_CURVE`)** requer hardware level `FULL` ou superior. Em dispositivos `LIMITED`/`LEGACY` o modo é ignorado silenciosamente.

---

## Sistema de Capabilities

### `CameraCapabilities` — data class completa

```kotlin
data class CameraCapabilities(
    val cameraId: String,
    val hardwareLevel: String,           // LEGACY, LIMITED, FULL, LEVEL_3
    val facing: String,                  // BACK, FRONT, EXTERNAL
    val name: String,                    // Wide, Ultra Wide, Telephoto, Frontal
    val focusDistanceCalibration: String,// UNCALIBRATED, APPROXIMATE, CALIBRATED

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

    // Resoluções
    val availableResolutions: List<String>,   // todas as resoluções brutas YUV_420_888
    val streamingResolutions: List<String>,   // filtradas 16:9: máx 4K/1080p/720p
    val rawResolution: String?,               // resolução real do ImageReader RAW_SENSOR

    // Modos suportados
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

### Exemplo: Galaxy Note10+

| Câmera | Level | Zoom | Foco manual | ISO | RAW | Flash | OIS |
|---|---|---|---|---|---|---|---|
| Wide (ID 0) | FULL | ✅ 1×–8× | ✅ 0–10D | ✅ 50–3200 | ✅ | ✅ | ✅ |
| Frontal (ID 1) | LIMITED | ❌ fixa | ❌ fixo | ⚠️ | ❌ | ❌ | ❌ |
| Ultra Wide (ID 2) | LIMITED | ⚠️ 0.6×–2× | ❌ fixo | ⚠️ | ❌ | ❌ | ❌ |
| Telephoto (ID 3) | FULL | ✅ 2×–10× | ✅ | ✅ | ✅ | ❌ | ✅ |

---

## Tonemap / Curvas Cinematográficas

| Preset | Uso | Comportamento |
|---|---|---|
| `linear` | RAW-like, sem processamento | `y = x` |
| `s-curve` | Blockbuster — contraste alto, blacks crushed | Interpolação cúbica (estilo DaVinci/ACES) |
| `log` | ARRI Alexa / RED — máximo dynamic range | `y = c·log10(a·x + b) + d` (Log-C) |
| `cinematic` | Blender Filmic — lift shadows, roll highlights | Piecewise com lift/cap |
| `power22` | Standard sRGB para monitores | `y = x^(1/2.2)` |

Configurar via `POST /api/control` com `{"tonemapCurve": "log"}`.

---

## Configuração MediaMTX (servidor RTMP local)

```bash
# Linux/macOS
curl -L https://github.com/bluenviron/mediamtx/releases/latest/download/mediamtx_linux_amd64.tar.gz | tar xz
./mediamtx
```

| Protocolo | URL |
|---|---|
| RTMP | `rtmp://localhost:1935/live/stream` |
| RTSP | `rtsp://localhost:8554/live/stream` |
| HLS | `http://localhost:8888/live/stream` |

**Visualizar com VLC/FFplay:**
```bash
ffplay rtmp://localhost:1935/live/stream
```

**OBS Studio:** Configurações → Stream → Personalizado → `rtmp://localhost:1935/live` / chave `stream`

---

## Pós-processamento — Parâmetros detalhados

| Parâmetro | Chave API | Valores disponíveis |
|---|---|---|
| Edge Enhancement | `edgeMode` | `OFF`, `FAST`, `HIGH_QUALITY`, `ZERO_SHUTTER_LAG` |
| Noise Reduction | `noiseReduction` | `OFF`, `MINIMAL`, `FAST`, `HIGH_QUALITY`, `ZERO_SHUTTER_LAG` |
| Hot Pixel | `hotPixel` | `OFF`, `FAST`, `HIGH_QUALITY` |
| Tonemap | `tonemapCurve` | `linear`, `s-curve`, `log`, `cinematic`, `power22` |
| Ganhos RGGB | `rggbEnabled` + `rggb_r/gr/gb/b` | Float por canal |

---

## Checklist de Testes

```markdown
### HUD
- [ ] HUD no canto superior direito, atualiza a cada 1s
- [ ] Cor da bateria muda (verde/amarelo/vermelho)

### FABs
- [ ] Trocar câmera com toast de confirmação
- [ ] Flash toggle funciona
- [ ] Foto capturada com feedback
- [ ] Toggle HUD mostra/oculta

### Bottom Sheet
- [ ] FAB ⚙️ abre/fecha bottom sheet
- [ ] Botão de câmera ativo destacado em azul
- [ ] Slider ISO atualiza em tempo real
- [ ] Slider Exposição atualiza em tempo real
- [ ] Slider Foco: 0 = AUTO, >0 = MANUAL
- [ ] Lock AE/AF alternam 🔒/🔓

### Modo Compacto
- [ ] Duplo toque alterna Normal ↔ Compacto
- [ ] Grade de regra dos terços via long-press, preferência persiste

### WebGUI
- [ ] Acesso em http://<IP>:8080 com login
- [ ] GET /api/status retorna JSON completo
- [ ] GET /api/capabilities retorna capacidades por câmera
- [ ] POST /api/control — start/stop/restart stream
- [ ] Controles aparecem/somem conforme câmera ativa
- [ ] Slider de bitrate sincroniza ao recarregar página
- [ ] Resoluções exibem apenas streaming_resolutions (máx 3)

### Captura RAW
- [ ] Card RAW aparece somente em câmera com supports_raw=true
- [ ] raw_resolution exibida corretamente
- [ ] POST /api/raw/capture retorna 202
- [ ] Polling ?poll=1 detecta quando raw_ready=true
- [ ] Download do DNG funciona no browser
- [ ] DNG salvo em DCIM/CAMSTREAMER/ no dispositivo
- [ ] Stream retoma normalmente após captura RAW
- [ ] Múltiplas capturas RAW em sequência sem crash
```

---

## Troubleshooting

### App não compila
```bash
./gradlew clean
./gradlew build
```

### Stream não conecta
- Verifique se a URL RTMP está correta (IP e porta 1935)
- Confirme que servidor (MediaMTX/OBS) está rodando
- Celular e servidor devem estar na mesma rede Wi-Fi

### Controles de câmera sem efeito
- Use `GET /api/capabilities` para verificar o que a câmera suporta
- Câmeras `LEGACY` não suportam controles manuais
- Curvas de tonemap requerem hardware level `FULL`

### WebGUI não abre
- Confirme o IP na barra de status do app
- O servidor HTTP inicia apenas com o service rodando em foreground
- Certifique-se de estar na mesma rede Wi-Fi

### Captura RAW falhando
- Verifique `supports_raw` em `/api/capabilities` para a câmera ativa
- Acompanhe os logs: `adb logcat | grep -E "RawCaptureManager|Camera2Controller|WebControlApi"`
- Em dispositivos lentos, o poll pode precisar de mais de 10s para o DNG ficar pronto
- Certifique-se de que o app está em foreground durante a captura (a sessão RAW fecha o preview temporariamente)

### HUD não atualiza
Verifique em `MainActivity.onCreate()`:
```kotlin
hudHandler.post(hudRunnable)  // deve estar presente
```

### Debug de capabilities
```bash
adb logcat | grep "Camera2Controller"
```
Ou no browser:
```javascript
fetch('/api/capabilities').then(r => r.json()).then(console.log)
```

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

### v5-CAMUI (branch atual) — 2026-06

#### ✨ Adicionado

**Captura RAW DNG**
- `RawCaptureManager` — pipeline dedicado com `DngCreator` em `HandlerThread` separada
- Sessão Camera2 dedicada para RAW via `cam2.addImageListener()` (surface registrada antes do capture)
- Pareamento `Image ↔ TotalCaptureResult` via `pendingImageQueue` (evita race condition)
- `Image` mantida aberta até `DngCreator.writeImage()` terminar — sem alocação de 23 MB no heap
- Salvamento em `MediaStore` (Android 10+) ou `FileOutputStream + MediaScannerConnection` (≤ Android 9)
- Retomada automática de `startPreview() + startStream()` após sessão RAW

**Rotas HTTP para RAW**
- `POST /api/raw/capture` — assíncrono, retorna 202 imediatamente
- `GET /api/raw/result?poll=1` — polling leve em JSON sem baixar o arquivo
- `GET /api/raw/result` — download binário DNG com cache zerado após servir

**WebGUI v5**
- Card "Captura RAW Still" com botão, status e badge `raw_ready`
- Download via `fetch() + Blob URL` (cross-origin compatível com Chrome)
- `streaming_resolutions` filtradas (16:9, máx 4K/1080p/720p) — backend não expõe resoluções brutas
- `raw_resolution` exibida no card RAW via `cap.raw_resolution`
- Slider de bitrate sincroniza com valor atual ao recarregar a página
- Remoção de toggle RAW órfão (apontava para função deletada — `ReferenceError`)

**Capabilities**
- Novos campos: `streamingResolutions`, `rawResolution`, `focusDistanceCalibration`
- `supportsRaw` baseado em `RAW_SENSOR` — independente de `supportsManualSensor`

#### 🐛 Corrigido

**Pipeline RAW (série de 15+ commits)**
- ANR: `DngCreator.writeImage()` movido para `HandlerThread` dedicada
- `OutOfMemoryError`: eliminado `copyAndClose()` que alocava ~23 MB no heap
- `maxImages already acquired`: `Image` fechada somente no `finally` do `rawHandler.post{}`
- Deadlock: `rawReaderHandler` e `rawImageHandler` agora são threads separadas
- Metadados errados no DNG: `TotalCaptureResult` de preview não contamina mais a captura still
- `IllegalArgumentException` — surface RAW não estava na `CameraCaptureSession`
- `isRawCaptureFeasible` não bloqueia mais por `supportsManualSensor`
- Download HTTP retornava sempre 404 — `lastRawDngBytes` agora preenchido corretamente
- Sessão RAW quebrava o stream — `startPreview()` antes de `startStream()` no finally

**Camera2Controller / WebGUI / Manifest**
- Cast seguro para `Boolean/Int/String` em `ois`, `eis`, `aeLock`, `awbLock`, `torch`, `rggbEnabled`, `manualSensor` (evita `ClassCastException` com JSON numérico)
- `bitrate` e `resolution` aplicados em runtime via `stopStream + prepareVideo + startStream`
- Troca de câmera despachada no `mainLooper` (evita `CalledFromWrongThreadException`)
- Handler de `exposure_ns`/`shutter_speed` com `parseTimeParam` correto
- `AndroidManifest.xml` restaurado após regressão que destruiu activities/serviços/permissões
- Typo `CameraMetamer` → `CameraMetadata` (causava falha de compilação)

---

### v4-ui — 2026-03

- HUD de 8 métricas, FABs, Bottom Sheet (ISO/Exposição/Foco), modo compacto, grade de terços
- WebGUI completa portada para HTML/JS embutido no APK
- Controles Camera2 via reflection: ISO, SS, WB, Zoom, Focus, OIS, EIS, Bitrate, FPS
- WebControlServer HTTP na porta 8080

### v3 — 2026-02

- Suporte RTMP via RootEncoder v2.4.5
- Sistema de capabilities dinâmicas (`discoverAllCameras`)

### v2 / v1

- v2: Streaming RTSP + capabilities
- v1: Prova de conceito

---

## Roadmap

- [ ] Conectar sliders de ISO/Exposição/Foco da UI nativa ao `Camera2Controller`
- [ ] Captura de foto JPEG funcional com salvamento na galeria
- [ ] Gravação de vídeo local simultânea ao stream
- [ ] Presets personalizados (Save/Load de configurações via SharedPreferences)
- [ ] Pinch-to-zoom no preview
- [ ] Gráfico de tráfego de rede em tempo real no HUD
- [ ] Cache de capabilities por câmera (evitar re-query a cada page load)
- [ ] Badge de hardware level na WebGUI (LEGACY/LIMITED/FULL/LEVEL_3)
- [ ] Slider de ISO dinâmico baseado no `iso_range` real da câmera ativa
- [ ] Conectar contadores de clientes e tráfego de rede do HUD ao `WebControlServer`
- [ ] Leitura real de FPS do encoder no HUD
- [ ] Leitura real de temperatura do sensor no HUD
- [ ] Testes automatizados de integração para o pipeline RAW em múltiplos devices

---

## Créditos

**Desenvolvimento:** Luan Silva ([@luanscps](https://github.com/luanscps))  
**Assistência IA:** Perplexity AI  
**Stack:** Kotlin + Material Design 3 + Camera2 API + RootEncoder
