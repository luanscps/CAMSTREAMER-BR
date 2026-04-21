# YUV / RAW / Depth Fusion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** adicionar suporte incremental a YUV_420_888, RAW_SENSOR e DEPTH16 no CAMSTREAMER-BR sem quebrar o pipeline atual de stream RTMP.

**Architecture:** o trabalho será dividido em componentes pequenos e isolados: processador YUV, gerenciador RAW, processador de profundidade, helpers em `CameraCapabilities` e integração gradual no `Camera2Controller`. O plano prioriza baixo risco, TDD e commits pequenos.

**Tech Stack:** Kotlin, Android Camera2 API, ImageReader, RootEncoder/RtmpCamera2, Gradle.

---

## File Structure

- Create: `docs/superpowers/specs/2026-04-21-yuv-raw-depth-design.md`
- Create: `docs/superpowers/plans/2026-04-21-yuv-raw-depth-plan.md`
- Create: `app/src/main/java/com/camera2rtsp/YuvFrameProcessor.kt`
- Create: `app/src/main/java/com/camera2rtsp/RawCaptureManager.kt`
- Create: `app/src/main/java/com/camera2rtsp/DepthFusionProcessor.kt`
- Modify: `app/src/main/java/com/camera2rtsp/CameraCapabilities.kt`
- Modify: `app/src/main/java/com/camera2rtsp/Camera2Controller.kt`

### Task 1: Documentação base

**Files:**
- Create: `docs/superpowers/specs/2026-04-21-yuv-raw-depth-design.md`
- Create: `docs/superpowers/plans/2026-04-21-yuv-raw-depth-plan.md`

- [ ] **Step 1: Criar o diretório de docs**

Run: `mkdir -p docs/superpowers/specs docs/superpowers/plans`
Expected: diretórios criados sem erro

- [ ] **Step 2: Salvar o design validado**

Criar `docs/superpowers/specs/2026-04-21-yuv-raw-depth-design.md` com o design aprovado.

- [ ] **Step 3: Salvar este plano**

Criar `docs/superpowers/plans/2026-04-21-yuv-raw-depth-plan.md` com este conteúdo.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-04-21-yuv-raw-depth-design.md docs/superpowers/plans/2026-04-21-yuv-raw-depth-plan.md
git commit -m "docs: add YUV RAW depth design and implementation plan"
```

### Task 2: Criar `YuvFrameProcessor`

**Files:**
- Create: `app/src/main/java/com/camera2rtsp/YuvFrameProcessor.kt`

- [ ] **Step 1: Criar a data class `YuvFrame`**

Adicionar uma `data class` com campos para largura, altura, timestamp, planos Y/U/V e strides.

- [ ] **Step 2: Adicionar o parser `fromImage`**

Implementar função de fábrica para converter `Image` em `YuvFrame`.

- [ ] **Step 3: Criar a classe `YuvFrameProcessor`**

Implementar `init()`, `surface()` e `release()` usando `ImageReader.newInstance(..., YUV_420_888, ...)`.

- [ ] **Step 4: Garantir consumo com `acquireLatestImage()`**

Usar `acquireLatestImage()` para evitar backlog.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/camera2rtsp/YuvFrameProcessor.kt
git commit -m "feat: add YUV frame processor"
```

### Task 3: Criar `RawCaptureManager`

**Files:**
- Create: `app/src/main/java/com/camera2rtsp/RawCaptureManager.kt`

- [ ] **Step 1: Criar a data class `RawFrame`**

Adicionar estrutura com `buffer`, `timestampNs`, `isoUsed`, `exposureNsUsed` e `captureResult`.

- [ ] **Step 2: Criar o `ImageReader` RAW**

Usar `ImageFormat.RAW_SENSOR` e `maxImages = 1`.

- [ ] **Step 3: Implementar callback de leitura**

Consumir `acquireNextImage()` e preencher `RawFrame`.

- [ ] **Step 4: Expor `pendingResult` para casar bytes + metadata**

Adicionar `pendingResult: TotalCaptureResult?` para posterior integração com callback de captura.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/camera2rtsp/RawCaptureManager.kt
git commit -m "feat: add RAW capture manager"
```

### Task 4: Criar `DepthFusionProcessor`

**Files:**
- Create: `app/src/main/java/com/camera2rtsp/DepthFusionProcessor.kt`

- [ ] **Step 1: Criar a data class `DepthFrame`**

Adicionar campos para dimensões, timestamp, `depthData`, `minDepthMm`, `maxDepthMm`, `meanDepthMm`.

- [ ] **Step 2: Implementar parser DEPTH16**

Decodificar profundidade usando `((value and 0xFFF8) >> 3)` e confiança com `(value and 0x0007)`.

- [ ] **Step 3: Adicionar utilitários `depthAt` e `confidenceAt`**

Esses métodos serão usados por futuros módulos de fusion.

- [ ] **Step 4: Criar `DepthFusionProcessor`**

Implementar `ImageReader` com `DEPTH16`, `init()`, `surface()` e `release()`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/camera2rtsp/DepthFusionProcessor.kt
git commit -m "feat: add depth fusion processor base"
```

### Task 5: Expandir `CameraCapabilities`

**Files:**
- Modify: `app/src/main/java/com/camera2rtsp/CameraCapabilities.kt`

- [ ] **Step 1: Adicionar helper YUV**

Adicionar:
```kotlin
fun supportsYuvImageReader(): Boolean = yuvResolutions.isNotEmpty()
```

- [ ] **Step 2: Adicionar helper de seleção YUV**

Adicionar:
```kotlin
fun bestYuvWithConstantFps(targetFps: Int = 30): Pair<String, Int>? {
    val fps = fpsRanges.filter { it.size >= 2 && it[0] == it[1] && it[1] >= targetFps }
        .maxByOrNull { it[1] }?.get(1) ?: return null
    val res = yuvResolutions.firstOrNull() ?: return null
    return Pair(res, fps)
}
```

- [ ] **Step 3: Adicionar helpers RAW**

Adicionar:
```kotlin
fun bestRawResolution(): String? = rawResolutions.firstOrNull()
fun isRawCaptureFeasible(): Boolean =
    supportsRaw && supportsManualSensor && rawResolutions.isNotEmpty()
```

- [ ] **Step 4: Adicionar helpers DEPTH**

Adicionar:
```kotlin
fun hasUsableDepthSensor(): Boolean =
    supportsDepthOutput && outputFormats.contains("DEPTH16")

fun recommendedDepthResolution(): String? =
    availableResolutions.minByOrNull { res ->
        val parts = res.split("x")
        (parts.getOrNull(0)?.toIntOrNull() ?: 9999) *
        (parts.getOrNull(1)?.toIntOrNull() ?: 9999)
    }
```

- [ ] **Step 5: Adicionar helpers de fusion**

Adicionar:
```kotlin
fun supportsYuvDepthFusion(): Boolean =
    supportsYuvImageReader() && hasUsableDepthSensor() &&
    (hardwareLevel == "FULL" || hardwareLevel == "LEVEL_3")

fun depthFusionScore(): Int {
    var score = 0
    if (supportsYuvImageReader()) score += 30
    if (hasUsableDepthSensor()) score += 30
    if (supportsManualSensor) score += 20
    if (hardwareLevel == "FULL" || hardwareLevel == "LEVEL_3") score += 20
    return score
}
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/camera2rtsp/CameraCapabilities.kt
git commit -m "feat: add YUV RAW and depth capability helpers"
```

### Task 6: Preparar `Camera2Controller`

**Files:**
- Modify: `app/src/main/java/com/camera2rtsp/Camera2Controller.kt`

- [ ] **Step 1: Adicionar estado para YUV**

Adicionar propriedades:
```kotlin
var yuvProcessorEnabled = false
var yuvProcessor: YuvFrameProcessor? = null
var yuvFrameCallback: ((YuvFrame) -> Unit)? = null
```

- [ ] **Step 2: Adicionar estado para RAW**

Adicionar propriedades:
```kotlin
var rawCaptureEnabled = false
var rawManager: RawCaptureManager? = null
var rawFrameCallback: ((RawCaptureManager.RawFrame) -> Unit)? = null
```

- [ ] **Step 3: Adicionar estado para depth**

Adicionar propriedades:
```kotlin
var depthFusionEnabled = false
var depthProcessor: DepthFusionProcessor? = null
var depthFrameCallback: ((DepthFrame) -> Unit)? = null
```

- [ ] **Step 4: Adicionar métricas voláteis**

Adicionar:
```kotlin
@Volatile var lastDepthMeanMm = 0f
@Volatile var lastDepthMinMm = 0
@Volatile var lastDepthMaxMm = 0
@Volatile var lastYuvTimestampNs = 0L
```

- [ ] **Step 5: Criar métodos de release dedicados**

Adicionar métodos `releaseYuvProcessor()`, `releaseRawManager()` e `releaseDepthProcessor()`.

- [ ] **Step 6: Atualizar `release()`**

Garantir que o `release()` atual feche os três módulos antes de limpar o worker.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/camera2rtsp/Camera2Controller.kt
git commit -m "feat: prepare Camera2Controller for YUV RAW and depth modules"
```

### Task 7: Integração opt-in de YUV

**Files:**
- Modify: `app/src/main/java/com/camera2rtsp/Camera2Controller.kt`

- [ ] **Step 1: Criar função `enableYuvProcessor(...)`**

Implementar criação do `YuvFrameProcessor` usando resolução escolhida a partir de `CameraCapabilitiesReader.read(...)`.

- [ ] **Step 2: Atualizar callback YUV**

No callback do processor, atualizar `lastYuvTimestampNs` e disparar `yuvFrameCallback`.

- [ ] **Step 3: Criar função `disableYuvProcessor()`**

Encerrar o `ImageReader` e limpar estado.

- [ ] **Step 4: Proteger por capability**

Antes de habilitar, validar `supportsYuvImageReader()`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/camera2rtsp/Camera2Controller.kt
git commit -m "feat: add opt-in YUV processor control"
```

### Task 8: Integração opt-in de RAW e depth

**Files:**
- Modify: `app/src/main/java/com/camera2rtsp/Camera2Controller.kt`

- [ ] **Step 1: Criar `enableRawCapture(...)` e `disableRawCapture()`**

Ativar apenas se `isRawCaptureFeasible()` retornar true.

- [ ] **Step 2: Criar `enableDepthFusion(...)` e `disableDepthFusion()`**

Ativar apenas se `hasUsableDepthSensor()` ou `supportsYuvDepthFusion()` retornarem true.

- [ ] **Step 3: Atualizar callback depth**

Ao receber `DepthFrame`, preencher `lastDepthMeanMm`, `lastDepthMinMm` e `lastDepthMaxMm`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/camera2rtsp/Camera2Controller.kt
git commit -m "feat: add opt-in RAW and depth control"
```

### Task 9: Verificação final

**Files:**
- Modify: `app/src/main/java/com/camera2rtsp/*.kt`

- [ ] **Step 1: Rodar compilação debug**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Verificar warnings principais**

Run: `./gradlew lintDebug`
Expected: sem erros bloqueantes relacionados aos novos arquivos

- [ ] **Step 3: Revisão manual rápida**

Checklist:
- nomes e tipos consistentes
- nenhum método órfão
- nenhum helper contradizendo os campos existentes
- release fecha todos os recursos

- [ ] **Step 4: Commit final**

```bash
git add app/src/main/java/com/camera2rtsp/*.kt
git commit -m "chore: validate YUV RAW and depth integration scaffolding"
```
