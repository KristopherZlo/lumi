# Compare highlight preparation

[Documentation home](README.md) / Compare highlight preparation

## What the player sees

Compare shows a preparation percentage and bar when the workspace uses the GUI HUD.
The bar appears below the current operation panel and above notifications.
The in-world HUD and Lumi screens use the same panel.

The first highlight appears when its visible batches are ready for drawing.
Later camera movement keeps the existing highlight visible while new batches upload.
Completed, hidden, and failed preparation have no active bar.

The H binding changes visibility and retains the prepared cache.
Showing the same prepared highlight again does not repeat geometry preparation or uploads.
Replacing or clearing Compare, disconnecting, and stopping the client release renderer resources.

## Read the code

| Step | Owner | Responsibility |
| --- | --- | --- |
| 1 | [ClientCompareStore](../../src/main/java/io/github/lumi/client/state/ClientCompareStore.java) | Keeps the immutable comparison input while visibility changes. |
| 2 | [LumiPendingChangeOverlay](../../src/main/java/io/github/lumi/client/LumiPendingChangeOverlay.java) | Retains renderer input and exposes `highlightProgress` for the visible comparison. |
| 3 | [CompareOverlayGeometry](../../src/main/java/io/github/lumi/client/CompareOverlayGeometry.java) | Reports completed blocks during its two background geometry passes. |
| 4 | [LumiCompareOverlayRenderer](../../src/main/java/io/github/lumi/client/LumiCompareOverlayRenderer.java) | Combines geometry progress with visible upload work and controls initial presentation. |
| 5 | [HighlightPreparationProgress](../../src/main/java/io/github/lumi/client/state/HighlightPreparationProgress.java) | Provides immutable completed and total work counts, a fraction, and a percentage. |
| 6 | [LumiClient.onInitializeClient](../../src/main/java/io/github/lumi/client/LumiClient.java) | Supplies `PENDING_OVERLAY::highlightProgress` to the HUD. |
| 7 | [LumiOperationHud.renderHighlightProgress](../../src/main/java/io/github/lumi/client/ui/LumiOperationHud.java) | Draws the translated percentage and progress bar in both HUD contexts. |

## Progress and ownership

This progress describes client geometry preparation and visible GPU uploads.
It does not create a server history operation or acquire a dimension freeze.
The percentage measures work counts, not elapsed time or estimated time remaining.

Geometry preparation runs on a background worker.
The render thread uploads, draws, and releases GPU buffers.
The HUD reads the progress value through its supplier.
`LumiPendingChangeOverlay.highlightProgress` returns inactive progress while Compare is hidden.

The translation key is `luma.status.highlight_preparing`.
Translation files live under [assets/lumi/lang](../../src/main/resources/assets/lumi/lang).

## Rendering limits

Each frame starts at most two retained batch uploads.
New uploads stop after a 4 ms launch budget.
One batch can exceed that budget.
The resident GPU cache has a 128 MiB limit.
Results above the limit use transient drawing for remaining visible batches.
The fallback must preserve changed blocks and interior x-ray edges.

## Verification

Unit tests cover immutable input retention, both geometry progress passes, and valid progress bounds:

- [ClientCompareStoreTest](../../src/test/java/io/github/lumi/client/state/ClientCompareStoreTest.java).
- [CompareOverlayGeometryTest](../../src/test/java/io/github/lumi/client/CompareOverlayGeometryTest.java).
- [HighlightPreparationProgressTest](../../src/test/java/io/github/lumi/client/state/HighlightPreparationProgressTest.java).

The `COMPARE_RENDER` suite uses the registered overlay owner and actual H binding.
[LumiCompareToggleScenario](../../src/gametest/java/io/github/lumi/client/LumiCompareToggleScenario.java) checks these states:

| State | Required result |
| --- | --- |
| Geometry preparation | The bar appears and the first highlight remains hidden. |
| Partial visible upload | The bar remains visible and no individual highlight outlines appear. |
| Ready | The highlight appears and the preparation bar disappears. |
| Hidden | The bar disappears. Prepared cache data remains available. |
| Three hide/show cycles | Cached bytes remain equal and prepared visibility returns immediately. |
| Cleared | Cached bytes return to zero and preparation becomes inactive. |

The suite also checks dense and scattered geometry, normal and x-ray drawing, and the zero-byte resident-cache fallback.

Run the checks from PowerShell with JDK 21:

```powershell
.\gradlew.bat test compileGametestJava remapJar --no-daemon --no-parallel --max-workers=1
.\gradlew.bat '-Dlumi.gametest.suite=COMPARE_RENDER' runClientGameTest --no-daemon --no-parallel --max-workers=1
```

Client tests recreate their run directory.
Copy evidence before another run.
See [Testing](testing.md#client-gametests) for other suites and report locations.

### Recorded run: 2026-10-12

The recorded run used JDK 21, an RTX 2080, an 854-by-480 framebuffer, and a 30 FPS client limit.
All 949 unit tests passed.
`compileGametestJava`, `remapJar`, and `COMPARE_RENDER` also passed.

| Measurement | Recorded result |
| --- | --- |
| Initial 100,000-block preparation | 1.716 seconds |
| Repeated H toggles | Equal cached bytes, immediate readiness, and no active preparation bar |
| Scattered CPU submission median, normal / x-ray | 0.251 / 0.237 ms across 60 frames |
| Scattered GPU elapsed median, normal / x-ray | 10.015 / 9.950 ms across 60 frames |

GPU elapsed samples include submission gaps.
They do not isolate shader execution time.
They do not establish performance for arbitrary player worlds.
The fixture does not mutate saved history.

The recorded artifacts are local build output, not files in the Git checkout:

- `build/reports/lumi-compare-render/highlight.csv`.
- `build/reports/lumi-compare-render/highlight-preparation.csv`.
- `build/reports/lumi-compare-render/highlight-screenshots/`.
