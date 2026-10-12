# Compare overlay rendering investigation

Date: 2026-10-12. Branch: `perf/compare-overlay`.

Large Compare results rebuilt a boxed position set, checked six neighbors,
emitted exposed fills, and emitted twelve cube edges per block on every frame.
Adjacent blocks repeatedly submitted the same colored edges. Geometry outside
the camera frustum still went through vertex construction and upload. Leaf
state changes are valid Compare results; suppressing them would hide changes.

The relevant previous fixes were preserved:

- `821acb767`: exact directional block overlays replaced storage-section boxes.
- `def196a66`: finish fills before switching to the outline buffer, because
  switching a shared buffer can close the previous builder.
- `ead5627aa`: remove shared interior fills to prevent translucent stacking.
  This corrected opacity, but neighbor discovery remained in the frame loop.

`CompareOverlayGeometry` now prepares face and edge masks on a background
executor. Same-color edges have one owner, including across batch boundaries;
different-color overlaps and the interior block grid remain. A regression
checks the complete colored-edge union against ordinary cube geometry over
mixed kinds, all axes and negative section boundaries.

`CompareOverlayGpuMesh` owns retained local-coordinate vertex buffers. The
renderer applies camera transforms, opacity and depth mode at draw time. It
culls spatial batches by distance and frustum and reuses offscreen buffers until
space is needed. All fills still finish before all outlines. No world state,
Compare semantics, history storage, Save or Restore path changed.

Preparation is cancelled when its immutable input is replaced. Hiding or
cancelling Compare, disconnecting and stopping the client release GPU buffers.
Uploads start at most two batches per frame and stop starting batches after
4 ms. A single batch can exceed that target. The resident vertex cache is
limited to 128 MiB; overflow uses one transient spatial batch at a time, with
the same complete geometry. It may be slower when a visible result exceeds the
cache budget. Index buffers use Minecraft's shared sequential-buffer pool.

## Measurements

The `COMPARE_RENDER` client test uses the production renderer in a real visible
Minecraft 1.21.11 client, NVIDIA RTX 2080, JDK 21.0.6, 854 by 480 framebuffer,
and a 30 FPS cap. It supplies synthetic immutable Compare coordinates without
editing saved history: a 32-cubed dense volume and 100,000 checkerboard canopy
coordinates. Both A/B fixtures disable overlay fog while preserving world fog.
The camera and geometry are identical. Colored screenshot checks exclude blank
views. Sixty steady-state frames follow warmup; the new renderer waits for
visible uploads to complete. The baseline predates `0a24701dd`; the final
renderer was measured twice in separate client processes.

Times below describe the overlay, not complete game frame time. CPU submission
includes geometry construction, uploads and draw submission. GPU elapsed queries
include uploads and submission gaps; they are not isolated shader time. The
after columns show the range of medians from the two final runs.

| Fixture | CPU before, ms | CPU after, ms | GPU before, ms | GPU after, ms |
| --- | ---: | ---: | ---: | ---: |
| Dense, normal | 81.835 | 0.132–0.133 | 7.434 | 1.167–3.090 |
| Dense, x-ray | 78.730 | 0.056–0.080 | 14.321 | 0.437–1.923 |
| Canopy, normal | 272.360 | 0.242–0.295 | 60.474 | 4.346–9.257 |
| Canopy, x-ray | 261.725 | 0.226–0.231 | 59.203 | 8.016–9.438 |

First preparation and incremental uploads take approximately 0.30 seconds for
the dense fixture and 1.68–1.73 seconds for the canopy. Geometry appears
incrementally during that period; it is not rebuilt each subsequent frame.
Switching to x-ray reused the same resident byte count: 16,034,688 for dense and
131,494,800 for canopy. The forced zero-byte cache case drew the dense fixture
with zero resident bytes at about 24 ms CPU submission per frame. Its colored
pixel footprint matches the cached screenshot by at least 99%.

Verification: 944 unit tests passed; compilation and `remapJar` passed. The
five-case client rendering test passed twice, including hide/release and the
forced transient path; the final run also checked matching screenshots.
The release JAR contains the new renderer classes and no GameTest classes.

Local raw evidence is under `build/reports/lumi-compare-render/`: `before.csv`,
`after.csv`, `after-repeat.csv`, preparation CSVs, environment metadata and
before/after screenshots. Run with:

```powershell
./gradlew.bat -Dlumi.gametest.suite=COMPARE_RENDER runClientGameTest --no-daemon --no-parallel --max-workers=1
```

The user's forest and a full-resolution uncapped gameplay session were not
measured. These results establish reduced work in the overlay renderer, not a
promise that the complete mod or every forest runs a fixed number of times
faster. Server persistence/crash tests were not repeated for this client-only
change.
