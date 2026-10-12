# Compare highlight preparation

`ClientCompareStore` keeps one immutable input while H changes visibility.
`LumiPendingChangeOverlay` retains the renderer input until Compare is replaced,
cleared, or disconnected. H keeps the bounded GPU cache, so showing an unchanged
highlight again does not repeat preparation or uploads.

`CompareOverlayGeometry` reports completed blocks in its two background passes.
`LumiCompareOverlayRenderer` adds the visible batch uploads to this local progress.
`HighlightPreparationProgress` is an immutable client value; it does not create a
server history operation. Completed, hidden, and failed preparations have no
active progress indicator.

`LumiOperationHud` receives a progress supplier from the registered overlay owner.
It displays a translated percentage and bar below the current operation panel and
above notifications. Both the in-world HUD and Lumi screens use the same panel.
The workspace's GUI HUD setting still applies.

The first highlight is presented after its visible batches are ready. Subsequent
camera movement keeps the existing highlight visible while new batches upload.
Preparation remains asynchronous. Uploads retain the two-batch and 4 ms launch
budgets, and the resident cache retains its 128 MiB limit and exact transient
fallback. No changed blocks or interior x-ray edges are omitted.

Unit coverage includes immutable input retention, both geometry progress passes,
and valid progress bounds. `COMPARE_RENDER` exercises the registered owner and
actual H binding across three hide/show cycles, checks retained bytes and immediate
readiness, and verifies that clearing Compare releases the cache.
It also checks screenshots for the preparation bar and its disappearance when
ready, hidden, or cleared.
The partial-upload screenshot must contain the bar and no individual outlines;
the ready screenshot must contain the highlight and no preparation bar.

## Verification

On 2026-10-12, JDK 21, RTX 2080, 854 by 480, with the client capped at 30 FPS:

- All 949 unit tests passed; `compileGametestJava` and `remapJar` passed.
- `COMPARE_RENDER` passed, including normal/x-ray dense and scattered geometry,
  the zero-byte resident-cache fallback, native H toggles, and HUD screenshots.
- The initial 100,000-block preparation took 1.716 seconds. Repeated H toggles
  retained exactly the same cached bytes and were ready immediately, with no
  active preparation indicator.
- The final 60-frame scattered workload had median CPU submission times of
  0.251 ms (normal) and 0.237 ms (x-ray). GPU elapsed medians were 10.015 ms and
  9.950 ms; these include submission gaps, rather than isolated shader execution.

Run the checks from PowerShell with JDK 21:

```powershell
.\gradlew.bat test compileGametestJava remapJar --no-daemon --no-parallel --max-workers=1
.\gradlew.bat '-Dlumi.gametest.suite=COMPARE_RENDER' runClientGameTest --no-daemon --no-parallel --max-workers=1
```

Local evidence is preserved in `build/reports/lumi-compare-render/highlight.csv`,
`highlight-preparation.csv`, and `highlight-screenshots/`. Client tests recreate
their run directory; copy evidence before another run. The synthetic fixture
does not mutate saved history. The player's original forest was not exercised.
