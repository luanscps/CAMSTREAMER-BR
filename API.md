# API HTTP do CAMSTREAMER-BR

Este documento descreve a API HTTP exposta pelo app **CAMSTREAMER-BR** na branch `v5-CAMUI`, com foco em comportamento real, fluxo de uso, exemplos de consumo e observações práticas de integração. A API é servida localmente pelo `WebControlServer` embutido no app, usando NanoHTTPD na porta `8080`, e centraliza seus handlers no módulo `WebControlApi`.[cite:24]

## Visão geral

Quando o app está em foreground com o serviço ativo, ele sobe um servidor HTTP local acessível pela rede em `http://<IP-DO-CELULAR>:8080`.[cite:24] A API foi desenhada para duas funções principais: expor o estado interno do pipeline de câmera/stream e permitir controle remoto em tempo real da câmera, encoder e recursos avançados como captura RAW.[cite:24]

Na prática, a API trabalha como a camada de integração da WebGUI, mas também pode ser consumida por scripts, painéis externos, apps móveis, automações locais e ferramentas de QA. As respostas usam JSON na maior parte dos endpoints e incluem `Access-Control-Allow-Origin: *`, o que facilita integração via browser e frontends externos.[cite:24]

## Base URL

Use a base URL abaixo, substituindo pelo IP local do celular:

```text
http://<IP-DO-CELULAR>:8080
```

Exemplo:

```text
http://192.168.1.55:8080
```

O valor de `<IP-DO-CELULAR>` é exibido na barra de status do app e depende da rede Wi‑Fi/local em que o dispositivo está conectado.[cite:16]

## Como a API se encaixa no sistema

O fluxo do projeto é: `MainActivity` controla a UI, `StreamingService` mantém o ciclo de vida do stream, `Camera2Controller` concentra o estado e os comandos da câmera, e `WebControlApi` transforma esse estado em rotas HTTP para a WebGUI e clientes externos.[cite:16][cite:24] Isso significa que quase toda chamada de controle enviada para a API acaba convergindo para `cameraController.updateSettings(params)` ou para métodos de ciclo de stream expostos pelo `StreamingService`.[cite:24]

## Requisitos para consumir a API

Para a API responder corretamente, algumas condições operacionais importam:

- O app deve estar aberto ou em estado compatível com o serviço rodando em foreground.[cite:16]
- O `StreamingService` precisa estar ativo para comandos relacionados a stream surtirem efeito.[cite:24]
- O cliente HTTP deve estar na mesma rede local do celular para acessar `:8080`.[cite:16]
- Em cenários de publicação RTMP, o app também precisa de um servidor RTMP receptor configurado, como MediaMTX, para que o stream realmente tenha para onde ser enviado.[cite:37][cite:38]

## Formato de respostas

A API usa majoritariamente `application/json` e respostas simples. Em geral:

- Endpoints de leitura retornam objetos JSON completos.[cite:24]
- Endpoints de controle retornam `{"status":"ok"}` ou um JSON de ação, como `{"status":"ok","action":"start"}`.[cite:24]
- Endpoints de erro retornam `500` com `{"status":"error","message":"..."}`.[cite:24]
- O endpoint de download RAW retorna binário `image/x-adobe-dng` quando a captura já foi concluída.[cite:24]

## Autenticação e acesso

O README refinado já documenta que a WebGUI usa autenticação baseada em sessão/cookie através do módulo `WebControlAuth`, embora o núcleo das rotas analisadas em `WebControlApi` esteja focado no tratamento funcional dos endpoints e não na camada de login em si.[cite:25] Para integrações próprias, vale validar na prática se o `WebControlServer` exige cookie para todos os caminhos ou apenas para páginas/fluxos específicos da interface web.[cite:25]

## Endpoints

## `GET /api/status`

Este é o endpoint mais importante para monitoramento. Ele retorna um snapshot amplo do estado atual do stream, da câmera selecionada, dos parâmetros ativos de captura e do estado de módulos auxiliares como visão avançada e RAW.[cite:24]

### O que ele entrega

O payload inclui, entre outros, os seguintes grupos de informação:[cite:24]

- Estado do streaming: `streaming`, `rtmp_url`, `resolution`, `bitrate_kbps`, `fps`.[cite:24]
- Estado de câmera: `camera_id`, `focus_mode`, `focus_dist`, `manual_sensor`, `zoom`, `wb`, `ois`, `eis`, `torch`.[cite:24]
- Estado de exposição/cor: `iso`, `exposure_ns`, `frame_duration_ns`, `ae_lock`, `awb_lock`, `edge`, `nr`, `hot_pixel`, `rggb_enabled`, `rggb_*`.[cite:24]
- Metadados ópticos: `focal_length`, `aperture`, `focus_distance_calibration`, `optical_zoom_index`, `optical_zoom_levels`.[cite:24]
- Telemetria de monitor: `monitor.iso`, `monitor.shutter_ns`, `monitor.af_state`, `monitor.ae_state`, `monitor.rggb_*`.[cite:24]
- Módulos avançados: `advanced_vision.yuv_enabled`, `depth_enabled`, `raw_ready`, `raw_filename`, `depth_mean_mm`, `depth_min_mm`, `depth_max_mm`.[cite:24]
- Inventário de câmeras: `cameras`, contendo a lista completa de `CameraCapabilities` para cada sensor descoberto.[cite:24]

### Exemplo de requisição

```bash
curl http://192.168.1.55:8080/api/status
```

### Exemplo de uso prático

Esse endpoint serve para painéis de monitoramento, overlays externos, dashboards técnicos e validação automatizada de estado. Um frontend pode fazer polling a cada 500 ms ou 1 s para atualizar indicadores de ISO, shutter, zoom, câmera ativa, stream online/offline e disponibilidade de captura RAW.[cite:24]

### Como interpretar

Se `streaming` for `true`, o encoder está transmitindo no momento.[cite:24] Se `advanced_vision.raw_ready` for `true`, existe um DNG pronto para ser baixado via `GET /api/raw/result`.[cite:24] Se o array `cameras` vier preenchido, ele pode ser usado para montar uma UI dinâmica de controles, escondendo funções não suportadas pela câmera atual.[cite:24][cite:16]

## `GET /api/capabilities`

Esse endpoint retorna apenas as capacidades detectadas das câmeras, sem o restante do estado operacional. Ele é ideal quando o cliente quer montar a interface de controle dinamicamente a partir do hardware disponível, sem depender do payload maior de `/api/status`.[cite:24]

### O que ele entrega

A resposta é um array de objetos `CameraCapabilities`, contendo campos como:[cite:24][cite:16]

- Identificação: `camera_id`, `name`, `facing`, `hardware_level`.[cite:24][cite:16]
- Flags de suporte: `supports_manual_sensor`, `supports_manual_post_processing`, `supports_raw`, `supports_burst_capture`, `supports_depth_output`, `supports_logical_multi_camera`.[cite:16]
- Ranges: `iso_range`, `exposure_time_range`, `ev_range`, `focus_distance_range`, `zoom_range`, `fps_ranges`.[cite:16]
- Resoluções: `available_resolutions`, `streaming_resolutions`, `raw_resolution`.[cite:25]
- Modos suportados: `supported_af_modes`, `supported_ae_modes`, `supported_awb_modes`.[cite:16]
- Hardware físico: `has_flash`, `has_ois`, `focal_lengths`, `apertures`.[cite:16]

### Exemplo de requisição

```bash
curl http://192.168.1.55:8080/api/capabilities
```

### Exemplo de uso prático

Este endpoint é o mais correto para construir uma WebGUI ou app remoto orientado por capacidade. Por exemplo:

- Mostrar slider de ISO apenas quando `supports_manual_sensor=true` e `iso_range` existir.[cite:16]
- Exibir card de RAW apenas quando `supports_raw=true`.[cite:25]
- Esconder OIS se `has_ois=false`.[cite:16]
- Limitar o seletor de resolução ao array `streaming_resolutions` em vez de listar todas as saídas brutas do sensor.[cite:25]

### Observação de cache

O `WebControlApi` mantém cache de capabilities por câmera atual e invalida esse cache quando chegam parâmetros como `camera`, `camera_id`, `yuvCapture` ou `depthFusion` no endpoint de controle.[cite:24] Isso reduz reconsulta desnecessária, mas o cliente não deve assumir que as capabilities são globalmente estáticas quando há troca de câmera ou modos avançados.[cite:24]

## `POST /api/control`

Este é o endpoint de escrita da API. Ele recebe um JSON arbitrário com um conjunto de chaves de configuração e aplica as alterações ao `Camera2Controller` e, quando necessário, ao `StreamingService`.[cite:24]

### Como o body funciona

O body é lido como JSON e convertido em `Map<String, Any>`, depois processado em duas etapas principais:[cite:24]

1. Se o JSON contiver `streamAction`, a API aciona `start`, `stop` ou `restart` diretamente no `StreamingService`.[cite:24]
2. Em seguida, o restante do mapa é repassado para `cameraController.updateSettings(params)`, que trata câmera, exposição, foco, zoom, cor, pós-processamento, bitrate, resolução e modos avançados.[cite:24]

### Exemplo mínimo — iniciar o stream

```bash
curl -X POST http://192.168.1.55:8080/api/control \
  -H "Content-Type: application/json" \
  -d '{"streamAction":"start"}'
```

### Exemplo mínimo — parar o stream

```bash
curl -X POST http://192.168.1.55:8080/api/control \
  -H "Content-Type: application/json" \
  -d '{"streamAction":"stop"}'
```

### Exemplo — mudar URL RTMP e reiniciar

```bash
curl -X POST http://192.168.1.55:8080/api/control \
  -H "Content-Type: application/json" \
  -d '{"rtmpUrl":"rtmp://192.168.1.100:1935/live/stream"}'
```

Quando `rtmpUrl` é enviada, a API atualiza a URL no `StreamingService`, para o stream atual e o reinicia em seguida, desde que a instância do serviço exista.[cite:24]

### Exemplo — mudar câmera

```bash
curl -X POST http://192.168.1.55:8080/api/control \
  -H "Content-Type: application/json" \
  -d '{"camera":"2"}'
```

Os commits da branch `v5-CAMUI` e o README refinado indicam que a troca de câmera passou a ser despachada no `mainLooper`, reduzindo risco de erro de thread ao aplicar a mudança em runtime.[cite:25]

### Exemplo — controle manual de sensor

```bash
curl -X POST http://192.168.1.55:8080/api/control \
  -H "Content-Type: application/json" \
  -d '{
    "manualSensor": true,
    "iso": 800,
    "exposure_ns": 16666666,
    "frameDuration": 33333333
  }'
```

Esse padrão é útil para cenas controladas, testes de exposição, pipelines de grading e casos em que se quer impedir que o ISP mude ISO/shutter automaticamente.[cite:24][cite:16]

### Exemplo — foco manual

```bash
curl -X POST http://192.168.1.55:8080/api/control \
  -H "Content-Type: application/json" \
  -d '{
    "focusmode": "manual",
    "focus": 0.5
  }'
```

### Exemplo — white balance + locks

```bash
curl -X POST http://192.168.1.55:8080/api/control \
  -H "Content-Type: application/json" \
  -d '{
    "whiteBalance": "daylight",
    "aeLock": true,
    "awbLock": true
  }'
```

### Exemplo — bitrate e resolução

```bash
curl -X POST http://192.168.1.55:8080/api/control \
  -H "Content-Type: application/json" \
  -d '{
    "bitrate": 4000000,
    "resolution": "1080p",
    "fps": 30
  }'
```

Na branch `v5-CAMUI`, bitrate e resolução foram documentados como aplicados de forma real via `stopStream + prepareVideo + startStream`, em vez de ficarem apenas refletidos na UI.[cite:25]

### Exemplo — OIS, EIS e lanterna

```bash
curl -X POST http://192.168.1.55:8080/api/control \
  -H "Content-Type: application/json" \
  -d '{
    "ois": true,
    "eis": false,
    "lantern": true
  }'
```

### Exemplo — tonemap e pós-processamento

```bash
curl -X POST http://192.168.1.55:8080/api/control \
  -H "Content-Type: application/json" \
  -d '{
    "tonemapCurve": "log",
    "noiseReduction": "HIGH_QUALITY",
    "edgeMode": "FAST",
    "hotPixel": "HIGH_QUALITY"
  }'
```

O README da branch informa que curvas tonemap customizadas com `CONTRAST_CURVE` exigem hardware level `FULL` ou superior; em câmeras `LIMITED`/`LEGACY`, o pedido pode ser ignorado silenciosamente pelo hardware.[cite:16]

### Exemplo — ganhos RGGB

```bash
curl -X POST http://192.168.1.55:8080/api/control \
  -H "Content-Type: application/json" \
  -d '{
    "rggbEnabled": true,
    "rggb_r": 1.8,
    "rggb_gr": 1.0,
    "rggb_gb": 1.0,
    "rggb_b": 1.6
  }'
```

Esse grupo é útil em experimentação de balanço fino e testes relacionados ao pipeline de cor. A leitura dos ganhos ativos também pode ser acompanhada em `/api/status` nos campos `rggb_*` de nível superior e em `monitor`.[cite:24]

### Exemplo — ativar módulos avançados

```bash
curl -X POST http://192.168.1.55:8080/api/control \
  -H "Content-Type: application/json" \
  -d '{
    "yuvCapture": true,
    "depthFusion": true
  }'
```

### Como consumir este endpoint de forma robusta

Algumas práticas tornam a integração mais confiável:

- Antes de enviar um comando, consulte `/api/capabilities` para saber se a câmera atual realmente suporta o recurso.[cite:16][cite:24]
- Depois do comando, consulte `/api/status` para validar se o estado efetivamente mudou.[cite:24]
- Para parâmetros sensíveis ao hardware, trate sucesso HTTP como “comando aceito”, não como garantia de efeito visível no sensor.[cite:16][cite:24]
- Considere debouncing em sliders de foco/zoom/ISO para não saturar a API com muitas chamadas por segundo.

### Compatibilidade de tipos

Os ajustes recentes da branch `v5-CAMUI` indicam cast seguro para vários parâmetros booleanos e inteiros, aceitando inclusive casos em que o JSON envia `0/1` em vez de `true/false`.[cite:25] Isso melhora a interoperabilidade com frontends e bibliotecas que normalizam tipos de forma inconsistente.[cite:25]

### Resposta típica

```json
{"status":"ok"}
```

ou, para ação de stream:

```json
{"status":"ok","action":"start"}
```

Em caso de falha interna, a resposta segue este padrão:[cite:24]

```json
{"status":"error","message":"<detalhe do erro>"}
```

## `POST /api/raw/capture`

Este endpoint inicia uma captura still RAW assíncrona. Ele não devolve o arquivo na mesma resposta; apenas dispara o processo no `Camera2Controller` e retorna `202 Accepted` imediatamente.[cite:24]

### Como funciona por dentro

O `WebControlApi` limpa `lastRawDngBytes` e `lastRawFilename`, chama `cameraController.captureRawStill(context)` e responde com um JSON indicando que a captura foi iniciada.[cite:24] O restante do pipeline ocorre em segundo plano e passa por `RawCaptureManager`, `ImageReader(RAW_SENSOR)` e `DngCreator` em thread dedicada.[cite:23][cite:24]

### Exemplo de requisição

```bash
curl -X POST http://192.168.1.55:8080/api/raw/capture
```

### Resposta típica

```json
{
  "status": "capturing",
  "message": "Captura iniciada. Consulte GET /api/raw/result?poll=1"
}
```

### Quando usar

Esse endpoint é indicado para:

- Captura de still DNG para grading ou pós-produção.[cite:25]
- Teste de sensor e metadados reais de captura.[cite:23]
- Painéis web que oferecem um botão “capturar RAW” com feedback assíncrono.[cite:25]

### Pré-condição importante

A câmera ativa precisa suportar RAW. Isso pode ser verificado em `/api/capabilities` pelo campo `supports_raw` e também, em algumas UIs, pela presença de `raw_resolution`.[cite:25]

## `GET /api/raw/result?poll=1`

Este endpoint existe para polling leve do status da captura RAW. Ele não envia o arquivo, só informa se o DNG está pronto e, quando disponível, devolve nome e tamanho em KB.[cite:24]

### Exemplo de requisição

```bash
curl "http://192.168.1.55:8080/api/raw/result?poll=1"
```

### Resposta possível — ainda processando

```json
{"ready":false,"filename":"","size_kb":0}
```

### Resposta possível — pronto

```json
{"ready":true,"filename":"raw_20260607_143022.dng","size_kb":22800}
```

### Como consumir

O fluxo recomendado é:

1. Chamar `POST /api/raw/capture`.[cite:24]
2. Fazer polling em `GET /api/raw/result?poll=1` a cada 500 ms ou 1 s.[cite:24]
3. Quando `ready=true`, iniciar o download real com `GET /api/raw/result`.[cite:24]

Esse modelo evita prender a conexão por vários segundos e torna a UI mais responsiva.[cite:24]

## `GET /api/raw/result`

Sem o parâmetro `poll=1`, esse endpoint devolve o binário do DNG pronto para download. O `Content-Type` é `image/x-adobe-dng` e o servidor também envia `Content-Disposition` com o filename.[cite:24]

### Exemplo de download com curl

```bash
curl -o captura.dng http://192.168.1.55:8080/api/raw/result
```

### Exemplo de consumo em JavaScript

```javascript
const r = await fetch('http://192.168.1.55:8080/api/raw/result');
if (!r.ok) throw new Error('RAW ainda não disponível');
const blob = await r.blob();
const url = URL.createObjectURL(blob);
const a = document.createElement('a');
a.href = url;
a.download = 'captura.dng';
a.click();
URL.revokeObjectURL(url);
```

O README refinado registra que a WebGUI da `v5-CAMUI` usa exatamente a estratégia `fetch() + Blob URL` para evitar problemas de download cross-origin com navegação direta em alguns navegadores.[cite:25]

### Importante sobre o ciclo de cache

Depois de servir o download, a API zera `lastRawDngBytes` e `lastRawFilename` para evitar entregar uma captura antiga na próxima requisição.[cite:24] Isso significa que o cliente deve baixar o arquivo assim que detectar `ready=true` e não assumir persistência indefinida no endpoint.[cite:24]

### Resposta de erro / não pronto

Se ainda não houver captura pronta, a resposta será `404` com JSON semelhante a:[cite:24]

```json
{"status":"not_ready","message":"Nenhuma captura RAW disponivel"}
```

## Fluxos de integração recomendados

## Fluxo 1 — Dashboard de monitoramento

Esse fluxo é o mais simples para um painel externo:

1. Consumir `/api/status` periodicamente.[cite:24]
2. Exibir `streaming`, `camera_id`, `iso`, `exposure_ns`, `fps`, `zoom`, `wb` e `raw_ready`.[cite:24]
3. Quando `raw_ready=true`, habilitar botão de download do DNG.[cite:24]

## Fluxo 2 — UI dinâmica orientada por capabilities

Esse é o fluxo ideal para uma UI externa robusta:

1. Ler `/api/capabilities` ao carregar a página.[cite:24]
2. Renderizar controles apenas para recursos realmente suportados pela câmera ativa.[cite:16][cite:24]
3. Enviar alterações via `/api/control`.[cite:24]
4. Confirmar o estado resultante via `/api/status`.[cite:24]

## Fluxo 3 — Captura RAW end-to-end

1. Verificar se a câmera suporta RAW por `/api/capabilities`.[cite:25]
2. Chamar `POST /api/raw/capture`.[cite:24]
3. Polling em `GET /api/raw/result?poll=1`.[cite:24]
4. Download com `GET /api/raw/result`.[cite:24]
5. Atualizar a UI de status para indicar fim da captura e limpeza do cache.[cite:24]

## Boas práticas de integração

- Use `/api/capabilities` como fonte de verdade para decidir o que mostrar na UI.[cite:16][cite:24]
- Use `/api/status` como fonte de verdade para telemetria e confirmação de estado.[cite:24]
- Trate ações de `/api/control` como comandos sujeitos a limites de hardware; sucesso HTTP não garante que o ISP aplicou tudo exatamente como pedido.[cite:16][cite:24]
- Em hardware `LIMITED` ou `LEGACY`, espere quedas de compatibilidade em tonemap, controles manuais e pós-processamento avançado.[cite:16]
- Para debugging, cruze os resultados HTTP com `adb logcat | grep "Camera2Controller"` ou `grep -E "RawCaptureManager|Camera2Controller|WebControlApi"` durante testes de RAW.[cite:16][cite:25]

## Exemplos de consumo em código

### JavaScript — ler status

```javascript
const r = await fetch('http://192.168.1.55:8080/api/status');
const status = await r.json();
console.log(status.streaming, status.iso, status.zoom);
```

### JavaScript — aplicar configuração

```javascript
await fetch('http://192.168.1.55:8080/api/control', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    manualSensor: true,
    iso: 400,
    exposure_ns: 8333333,
    whiteBalance: 'daylight'
  })
});
```

### Python — ler capabilities

```python
import requests

caps = requests.get('http://192.168.1.55:8080/api/capabilities').json()
for cam in caps:
    print(cam['camera_id'], cam['name'], cam.get('supports_raw'))
```

### Python — capturar RAW

```python
import time
import requests

requests.post('http://192.168.1.55:8080/api/raw/capture')

while True:
    poll = requests.get('http://192.168.1.55:8080/api/raw/result?poll=1').json()
    if poll['ready']:
        break
    time.sleep(0.5)

raw = requests.get('http://192.168.1.55:8080/api/raw/result')
with open('captura.dng', 'wb') as f:
    f.write(raw.content)
```

## Integração com RTMP

O app publica vídeo para um destino RTMP, então a integração típica envolve um servidor receptor como MediaMTX. O app é o publicador; o servidor recebe e redistribui o stream para leitores como VLC, FFplay, navegador e OBS.[cite:37][cite:40][cite:44]

### Fluxo recomendado

```text
CAMSTREAMER-BR (Android) ──RTMP publish──► MediaMTX ──► RTSP / HLS / WebRTC / VLC / OBS / Browser
```

### Exemplo de integração com a API de controle

#### 1) Configurar URL e iniciar stream

```bash
curl -X POST http://192.168.1.55:8080/api/control   -H "Content-Type: application/json"   -d '{
    "rtmpUrl":"rtmp://192.168.1.100:1935/live/stream",
    "streamAction":"start"
  }'
```

#### 2) Verificar status

```bash
curl http://192.168.1.55:8080/api/status
```

Verifique `streaming=true`, `rtmp_url` correto e `fps/bitrate` ativos.[cite:24]

#### 3) Consumir o stream no MediaMTX

- RTMP: `rtmp://192.168.1.100:1935/live/stream`
- RTSP: `rtsp://192.168.1.100:8554/live/stream`
- HLS: `http://192.168.1.100:8888/live/stream`
- WebRTC: `http://192.168.1.100:8889/live/stream`[cite:40][cite:44]

#### 4) Teste rápido com ffplay

```bash
ffplay rtmp://192.168.1.100:1935/live/stream
```

### Exemplo de automação prática

Se a ideia é montar uma cena de OBS com controle remoto do celular:

1. Chame `/api/control` para setar `resolution`, `bitrate`, `whiteBalance` e `manualSensor`.
2. Inicie o stream com `streamAction=start`.
3. No OBS, use o URL RTSP/HLS/WebRTC gerado pelo MediaMTX como fonte de entrada.[cite:24][cite:37][cite:40]

## Debug de erros HTTP comuns

### `400 Bad Request`

Geralmente indica JSON inválido, corpo vazio ou parâmetro de tipo incompatível. A API espera um JSON válido em `POST /api/control` e converte o body para `Map<String, Any>`; nomes errados ou payload malformado quebram o parse.[cite:24]

#### Como corrigir

- Confirme que o `Content-Type` é `application/json`.
- Garanta aspas corretas em strings e booleanos válidos.
- Evite mandar números como string quando o campo espera número, e vice-versa.

### `404 Not Found`

No fluxo RAW, `GET /api/raw/result` retorna `404` quando não existe DNG pronto ainda.[cite:24] Isso não é erro fatal; é apenas o comportamento esperado enquanto a captura está em andamento.

#### Como corrigir

- Use `GET /api/raw/result?poll=1` para saber quando o arquivo ficou pronto.[cite:24]
- Só faça o download binário depois que `ready=true`.[cite:24]

### `500 Internal Error`

Indica erro interno em `handleControl` ou falha ao processar a captura RAW.[cite:24] A resposta retorna a mensagem do erro para facilitar debugging.

#### Como corrigir

- Leia `adb logcat` com filtros como `Camera2Controller`, `WebControlApi` e `RawCaptureManager`.[cite:16][cite:23][cite:24]
- Verifique se a câmera realmente suporta o recurso solicitado.
- Em RAW, confirme `supports_raw=true` no `/api/capabilities`.[cite:24][cite:25]

### `202 Accepted` sem resultado imediato

Esse status é normal em `POST /api/raw/capture`.[cite:24] A captura RAW não é síncrona; o arquivo demora alguns instantes até ficar pronto.

#### Como corrigir

- Prossiga com polling em `GET /api/raw/result?poll=1`.
- Não tente baixar o arquivo imediatamente após o `POST`.[cite:24]

### Stream não inicia apesar de `status=ok`

Isso normalmente significa que a API aceitou o comando, mas o encoder não conseguiu publicar por causa de rede, URL ou falta de receptor RTMP.[cite:24][cite:37]

#### Como corrigir

- Confirme se existe um servidor RTMP ativo, como MediaMTX.
- Verifique o IP e a porta `1935`.
- Confirme que celular e servidor estão na mesma rede local.
- Teste o stream diretamente em `ffplay` ou VLC.[cite:37][cite:40]

## Estrutura de arquivos recomendada

Para expandir a API sem virar um arquivo monolítico, a estrutura ideal é separar responsabilidade por domínio.[cite:24]

### Organização sugerida

```text
app/src/main/java/com/camera2rtsp/
├── web/
│   ├── WebControlServer.kt
│   ├── WebControlApi.kt
│   ├── WebControlAuth.kt
│   └── WebControlHtml.kt
├── camera/
│   ├── Camera2Controller.kt
│   ├── CameraCapabilities.kt
│   ├── CameraCapabilitiesReader.kt
│   └── RawCaptureManager.kt
├── streaming/
│   ├── StreamingService.kt
│   └── RtmpStreamer.kt
├── vision/
│   ├── YuvFrameProcessor.kt
│   └── DepthFusionProcessor.kt
├── remote/
│   ├── RemoteCommandsService.kt
│   └── CamuiRemoteCommandExecutor.kt
└── system/
    ├── HeartbeatService.kt
    └── HeartbeatLoop.kt
```

### Por que isso ajuda

- Facilita manutenção de endpoints por responsabilidade.
- Evita misturar lógica de câmera, stream e autenticação no mesmo arquivo.
- Ajuda a escrever testes por módulo.
- Reduz risco de regressões quando novos endpoints forem adicionados.[cite:24][cite:25]

### Padrão de expansão para novos endpoints

Para novos endpoints, o ideal é seguir este padrão:

1. Definir a rota em `WebControlServer`.
2. Implementar o handler em `WebControlApi` ou em um submódulo próprio.
3. Validar permissões/autenticação se necessário.
4. Expor o estado correspondente em `/api/status` ou `/api/capabilities` quando fizer sentido.
5. Documentar o endpoint no `API.md` com exemplo de `curl` e exemplo de integração por código.[cite:24]

## Performance: como otimizar requisições frequentes para `/api/status`

Como `/api/status` pode ser consultado com frequência por dashboards e UIs reativas, vale cuidar de latência, payload e volume de chamadas.[cite:24]

### Estratégias recomendadas

- **Cache no cliente:** faça polling a cada 500 ms ou 1 s, não a cada evento de teclado/mouse.[cite:24]
- **Debounce visual:** atualize sliders e badges em lote, não campo a campo.
- **Reutilize o JSON:** compare apenas campos que mudaram para evitar redesenho completo da UI.
- **Use `/api/capabilities` separadamente:** capacidades mudam muito menos que status dinâmico.[cite:24]
- **Evite polling desnecessário de RAW:** só consulte `GET /api/raw/result?poll=1` quando houver captura pendente.[cite:24]

### Modelo de consumo mais leve

Um dashboard técnico pode organizar o polling assim:

- `/api/status` a cada 1 s para telemetria principal.
- `/api/capabilities` somente ao iniciar ou ao trocar câmera.
- `/api/raw/result?poll=1` apenas durante uma captura RAW ativa.[cite:24]

### Otimização de payload

Se uma integração externa só precisar de poucos campos, o ideal é manter `/api/status` como fonte completa e o cliente selecionar o subconjunto necessário. Isso evita criar múltiplos endpoints redundantes e mantém o contrato centralizado.[cite:24]

## Limitações e pontos de atenção

A API é rica, mas está acoplada às capacidades reais do hardware Camera2 do dispositivo. Portanto, disponibilidade de foco manual, RAW, OIS, tonemap e ranges de zoom/ISO varia por câmera e por aparelho.[cite:16] Além disso, o histórico de commits da `v5-CAMUI` mostra que o pipeline RAW e a aplicação de certos controles passaram por várias correções recentes, o que reforça a necessidade de validar cenários reais em múltiplos devices antes de tratar o comportamento como universal.[cite:25]

## Conclusão operacional

Para consumo externo, a combinação mais segura é: usar `/api/capabilities` para descobrir suporte, `/api/control` para aplicar mudanças e `/api/status` para confirmar o resultado.[cite:24] Para RAW, use o trio `POST /api/raw/capture` + polling em `/api/raw/result?poll=1` + download final em `/api/raw/result`, que é exatamente o fluxo assíncrono desenhado pela branch `v5-CAMUI`.[cite:24][cite:23][cite:25]


## Versão deste documento

Esta é a versão atualizada do `API.md`, incluindo integração RTMP, debug HTTP, organização de arquivos e otimização de `GET /api/status`.
