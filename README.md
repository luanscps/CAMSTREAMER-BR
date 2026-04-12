# CAMSTREAMER-BR

Aplicativo Android para **transmissão ao vivo via RTMP** com controle completo da câmera pelo hardware Camera2 API e painel web de controle remoto acessível pela rede local.

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

## Quickstart

### Requisitos
- Android 8.0+ (API 26)
- Permissões: `CAMERA`, `RECORD_AUDIO`, `INTERNET`, `FOREGROUND_SERVICE`
- Android 13+: `POST_NOTIFICATIONS`

### Instalação
1. Clone o repositório
2. Abra no Android Studio
3. Build → Run no dispositivo

### Primeiro uso
1. Abra o app — conceda as permissões solicitadas
2. Toque no ⚙️ para abrir o painel de configurações
3. Insira a URL RTMP do seu servidor (ex: `rtmp://192.168.1.100:1935/live/stream`)
4. Toque em **Aplicar**
5. Toque no botão shutter para iniciar a transmissão

### Grade de composição
- **Long-press** no preview ativa/desativa a grade de regra dos terços
- A preferência é salva automaticamente

---

## Painel Web de Controle (WebGUI)

Com o app rodando, acesse pelo browser da rede local:

```
http://<IP-DO-CELULAR>:8080
```

O IP local é exibido na barra de status do app.

### Controles disponíveis na WebGUI

| Controle | Parâmetro |
|---|---|
| Iniciar / Parar stream | `startStream` / `stopStream` |
| ISO manual | `iso` (ex: `800`) |
| Velocidade do obturador | `shutterSpeed` (ex: `1/60`) |
| Compensação de exposição | `exposure` (EV, modo auto) |
| Foco | `focus` + `focusmode` (af / manual) |
| Balanço de branco | `whiteBalance` (auto, daylight, cloudy, tungsten, fluorescent) |
| Zoom | `zoom` (0.0 – 1.0) |
| Lanterna | `lantern` (true/false) |
| OIS / EIS | `ois` / `eis` (true/false) |
| Bitrate em voo | `bitrate` (ex: `4000000`) |
| FPS | `fps` (ex: `30`) |
| Resolução | `resolution` (4k / 1080p / 720p / WxH) |
| Troca de câmera | `camera` (id) |
| Redução de ruído | `noiseReduction` |
| Edge mode | `edgeMode` |

---

## Configuração MediaMTX (servidor RTMP local)

Para testar localmente sem um servidor externo, use o [MediaMTX](https://github.com/bluenviron/mediamtx).

### Instalação rápida

```bash
# Linux / macOS
curl -L https://github.com/bluenviron/mediamtx/releases/latest/download/mediamtx_linux_amd64.tar.gz | tar xz
./mediamtx
```

### Configuração mínima (`mediamtx.yml`)

```yaml
rtmp:
  enable: yes
  address: :1935

paths:
  live:
    source: publisher
```

### URL no app
```
rtmp://<IP-DA-MAQUINA>:1935/live/stream
```

### Visualizar o stream
```bash
ffplay rtmp://localhost:1935/live/stream
# ou
vlc rtmp://localhost:1935/live/stream
```

---

## Capabilities da Câmera

O app descobre automaticamente as capacidades do hardware via `Camera2Controller.discoverAllCameras()`. Informações expostas:

- **Hardware Level**: LEGACY / LIMITED / FULL / LEVEL_3
- **Facing**: FRONT / BACK / EXTERNAL
- **ISO Range**: mín – máx (ex: 50–3200)
- **Exposure Range**: mín – máx em EV
- **FPS Ranges**: todos os ranges suportados
- **Resoluções**: lista completa de saídas de vídeo
- **AF Modes**: CONTINUOUS_VIDEO, AUTO, OFF, MACRO, etc.
- **AE Modes**: ON, OFF, ON_ALWAYS_FLASH, etc.
- **AWB Modes**: AUTO, DAYLIGHT, CLOUDY, TUNGSTEN, FLUORESCENT, etc.
- **Flash**: suportado (true/false)
- **OIS**: estabilização óptica (true/false)
- **Focal Lengths**: distâncias focais disponíveis
- **Abertura**: f/número(s) disponíveis

Todas as capabilities são enviadas para a WebGUI e usadas para popular os controles dinamicamente.

---

## Tonemap / Curvas de Cor

O `Camera2Controller` suporta controle de pós-processamento via `CaptureRequest`:

| Parâmetro | Chave | Valores |
|---|---|---|
| Edge Enhancement | `edgeMode` | `OFF`, `FAST`, `HIGH_QUALITY`, `ZERO_SHUTTER_LAG` |
| Noise Reduction | `noiseReduction` | `OFF`, `FAST`, `HIGH_QUALITY`, `MINIMAL`, `ZERO_SHUTTER_LAG` |
| Hot Pixel | `hotPixel` | `OFF`, `FAST`, `HIGH_QUALITY` |
| Tonemap | interno | `FAST`, `HIGH_QUALITY`, `CONTRAST_CURVE` |

> **Nota:** O controle de tonemap por curvas personalizadas (`CONTRAST_CURVE`) requer hardware level `FULL` ou superior. Em dispositivos `LIMITED`/`LEGACY` o modo é ignorado silenciosamente.

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

## Changelog

### v4-ui (branch atual)
- Nova UI nativa Android substituindo a interface web no celular
- `isStreaming` sincronizado diretamente do `StreamingService` (sem dessincronização)
- `clientsBadge` atualizado no HUD a cada segundo via `WebControlServer.connectedClients`
- `GridOverlayView` ativável por long-press no preview com persistência
- Remoção de código morto: `RtspServer.kt` (orphan), `build.gradle.kts` (duplicata)
- Documentação consolidada em README único

### v3
- Suporte RTMP via RootEncoder v2.4.5
- WebGUI completa portada da v3 (HTML/JS embutido no APK)
- Controles Camera2 via reflection: ISO, SS, WB, Zoom, Focus, OIS, EIS, Bitrate, FPS
- WebControlServer HTTP na porta 8080

### v2
- Streaming RTSP via RootEncoder
- Controles básicos de câmera

### v1
- Prova de conceito inicial
