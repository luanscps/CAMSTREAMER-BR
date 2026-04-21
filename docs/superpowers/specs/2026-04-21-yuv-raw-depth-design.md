# YUV, RAW e Depth Fusion Design

**Projeto:** CAMSTREAMER-BR

**Objetivo:** estender o pipeline atual baseado em Camera2 + RootEncoder para suportar captura paralela de frames YUV_420_888, captura single-shot RAW_SENSOR e leitura de DEPTH16 para futuros recursos de análise, diagnóstico e depth fusion.

## Contexto

O projeto atual usa `RtmpCamera2` com `Camera2Controller` como camada de controle fino de parâmetros Camera2. O pipeline está focado em preview + RTMP e já expõe capacidades detalhadas por câmera usando `CameraCapabilities` e `CameraCapabilitiesReader`.

O código atual ainda não possui:
- `ImageReader` paralelo para YUV_420_888
- captura sob demanda de RAW_SENSOR
- leitura contínua de DEPTH16
- sincronização simples por timestamp entre YUV e depth
- funções utilitárias na `CameraCapabilities` para verificar suporte real a YUV/RAW/depth fusion

## Metas

1. Permitir captura paralela YUV_420_888 sem quebrar o stream RTMP.
2. Permitir captura RAW sob demanda apenas em dispositivos compatíveis.
3. Permitir leitura contínua de DEPTH16 para uso em depth fusion e diagnóstico.
4. Expor helpers de decisão em `CameraCapabilities`.
5. Manter o design desacoplado, com novos componentes pequenos e responsabilidade única.

## Fora de escopo

- salvar DNG em disco nesta fase
- reconstrução 3D
- bokeh em tempo real no preview
- reprocessamento RAW/YUV avançado
- UI final para depth fusion

## Abordagem escolhida

A abordagem escolhida é adicionar componentes independentes baseados em `ImageReader` e integrá-los gradualmente ao `Camera2Controller`.

Motivos:
- reduz acoplamento com o pipeline atual de RTMP
- permite ativação seletiva por capability
- facilita testes unitários de parsing e utilidades
- evita uma reescrita completa do pipeline de streaming

## Arquivos novos

### `app/src/main/java/com/camera2rtsp/YuvFrameProcessor.kt`
Responsável por abrir `ImageReader` em formato `YUV_420_888`, consumir `Image` e converter para uma `data class YuvFrame` imutável.

### `app/src/main/java/com/camera2rtsp/RawCaptureManager.kt`
Responsável por gerenciar captura single-shot RAW com `ImageReader(RAW_SENSOR)` e encapsular os bytes e metadados em `RawFrame`.

### `app/src/main/java/com/camera2rtsp/DepthFusionProcessor.kt`
Responsável por consumir frames `DEPTH16`, calcular estatísticas básicas e expor utilitários por pixel.

## Arquivos modificados

### `app/src/main/java/com/camera2rtsp/CameraCapabilities.kt`
Adicionar helpers como:
- `supportsYuvImageReader()`
- `bestYuvWithConstantFps()`
- `bestRawResolution()`
- `isRawCaptureFeasible()`
- `hasUsableDepthSensor()`
- `recommendedDepthResolution()`
- `supportsYuvDepthFusion()`
- `depthFusionScore()`

### `app/src/main/java/com/camera2rtsp/Camera2Controller.kt`
Adicionar estado e pontos de integração para habilitar/desabilitar os processadores, além de armazenar callbacks e últimos metadados lidos.

## Estruturas propostas

### `data class YuvFrame`
Campos:
- `width`, `height`
- `timestampNs`
- `yPlane`, `uPlane`, `vPlane`
- `yRowStride`, `uvRowStride`, `uvPixelStride`

### `data class RawFrame`
Campos:
- `width`, `height`
- `timestampNs`
- `buffer`
- `isoUsed`, `exposureNsUsed`
- `captureResult`

### `data class DepthFrame`
Campos:
- `width`, `height`
- `timestampNs`
- `depthData`
- `minDepthMm`, `maxDepthMm`, `meanDepthMm`

Métodos úteis:
- `depthAt(x, y)`
- `confidenceAt(x, y)`

## Integração incremental

### Fase 1
Criar classes e testes de parsing sem integrar ao stream.

### Fase 2
Adicionar helpers em `CameraCapabilities` e validar devices compatíveis.

### Fase 3
Adicionar estado no `Camera2Controller` para controlar os novos módulos.

### Fase 4
Integrar YUV primeiro, depois RAW sob demanda e por fim depth.

## Riscos

1. **Conflito de superfícies na sessão Camera2**
   - Mitigação: integrar primeiro de forma opt-in e limitada a devices FULL/LEVEL_3 quando necessário.

2. **Overhead de memória e GC**
   - Mitigação: `maxImages` pequeno, `acquireLatestImage()`, callbacks enxutos.

3. **Diferenças entre dispositivos**
   - Mitigação: toda ativação depende de `CameraCapabilities`.

4. **RAW indisponível na maioria dos aparelhos**
   - Mitigação: manter RAW como recurso sob demanda, nunca obrigatório.

## Estratégia de testes

- testes unitários para parsing de resolução, escolha de config e helpers de capability
- testes instrumentados futuros para fluxo real de `ImageReader`
- validação manual em device com `/api/capabilities`
- log detalhado no `Camera2Controller`

## Critérios de sucesso

- projeto compila com os novos arquivos adicionados
- `CameraCapabilities` consegue informar suporte a YUV, RAW e depth fusion
- YUV pode ser habilitado sem quebrar o fluxo principal do app
- RAW fica protegido por capability e uso sob demanda
- DEPTH16 pode ser lido em devices compatíveis
