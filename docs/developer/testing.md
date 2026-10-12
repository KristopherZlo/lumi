# Testing

[Documentation home](README.md) / Testing

Run commands from the repository root with JDK 21.
On macOS or Linux, replace `.\gradlew.bat` with `./gradlew`.
Quote `-D` arguments in PowerShell.

## Choose checks

| Change | Checks |
| --- | --- |
| Form, value object, or domain rule | Matching unit tests, then `test` |
| Structural Java change | Compile all source sets and run `test` |
| Minecraft capture, hooks, or live apply | Matching unit tests and `runGameTest` |
| Client protocol or player workflow | Matching unit tests and the `SMOKE` client suite |
| Screen layout or input | `UI` and `UI_SCALE` client suites |
| Compare overlay | Geometry/store tests and `COMPARE_RENDER` |
| Restore persistence or recovery | Restore/recovery tests, `EXACT`, `RECOVERY`, and the process-crash matrix |
| Idle or hot-path performance | `regressionPerformanceTest` and a relevant controlled workload |

A unit test cannot prove that Minecraft persisted and reloaded an exact world.
A synthetic benchmark cannot prove multiplayer correctness.

## Compile all source sets

```powershell
.\gradlew.bat compileJava compileTestJava compileGametestJava --no-daemon
```

## Unit tests

```powershell
.\gradlew.bat test --no-daemon
```

Run one test class:

```powershell
.\gradlew.bat test --tests 'io.github.lumi.client.ui.SaveScreenControllerTest' --no-daemon
.\gradlew.bat test --tests 'io.github.lumi.domain.service.SaveServiceTest' --no-daemon
.\gradlew.bat test --tests 'io.github.lumi.minecraft.operation.RestoreOperationTest' --no-daemon
```

Unit tests live under `src/test/java`.
The report is `build/reports/tests/test/index.html`.
JUnit XML results live under `build/test-results/test/`.
The ordinary `test` task excludes the `performance` tag.

## Server GameTests

```powershell
.\gradlew.bat runGameTest --no-daemon
```

The build recreates its test world under `build/run/gameTest/world`.

Registered suites cover history operations, live actions, projectiles, TNT, fluids, falling blocks, pistons, redstone, WorldEdit, and Axiom.
See the [GameTest mod entry points](../../src/gametest/resources/fabric.mod.json) for the complete list.

To filter a server test, pass its registered test ID:

```powershell
.\gradlew.bat '-Dfabric-api.gametest.filter=lumi-gametest:lumi_redstone_machine_game_tests_rg_machine_two' runGameTest --no-daemon
```

The server runtime log is `build/run/gameTest/logs/latest.log`.
A normal `build` also includes `runGameTest`.

## Client GameTests

Start with the focused smoke gate:

```powershell
.\gradlew.bat '-Dlumi.gametest.suite=SMOKE' runClientGameTest --no-daemon
```

For the default functional selection:

```powershell
.\gradlew.bat runClientGameTest --no-daemon
```

[LumiClientTestSuite](../../src/gametest/java/io/github/lumi/gametest/LumiClientTestSuite.java) defines the selectors:

| Selector | Focus |
| --- | --- |
| `ALL` | Default functional suites. Benchmark and process-crash runs require their extra configuration. |
| `SMOKE` | First-minute client/server actions, exact state, lighting, close, and reopen |
| `UI` | Screen workflows and control states |
| `UI_SCALE` | Fixed Lumi scale, mouse input, tooltips, and viewport sizes |
| `COMPARE_RENDER` | Overlay correctness, preparation HUD, H toggles, CPU submission, GPU timing, and screenshots |
| `INTERACTION` | Player interaction diagnostics |
| `RECOVERY` | Interrupted operation recovery through the UI |
| `EXACT` | Exact Restore acceptance scenarios |
| `RESTORE_CRASH` | Producer/verifier process-crash harness. Use the matrix task below. |
| `BENCHMARK` | Opt-in history performance workloads |

For example:

```powershell
.\gradlew.bat '-Dlumi.gametest.suite=UI_SCALE' runClientGameTest --no-daemon
.\gradlew.bat '-Dlumi.gametest.suite=EXACT' runClientGameTest --no-daemon
```

The Compare rendering gate also uses the registered overlay owner and the actual H binding.
It checks preparation and partial-upload screenshots before the first highlight appears.
The ready screenshot must show the highlight without the preparation bar.
Hide/show cycles must preserve the prepared cache.
Clearing Compare must release it.
See [Highlight preparation](highlight-preparation.md#verification) for the focused command and recorded evidence.

Client run files, logs, and screenshots live under `build/run/clientGameTest/`.
Behavior scenarios also write reports under `build/reports/lumi-behavior/`.
The Compare renderer writes `build/run/clientGameTest/compare-render.csv`.
Preserve results before another client run recreates its run directory.
Crash verifiers deliberately retain the producer's interrupted world.

## Real process-crash matrix

```powershell
.\gradlew.bat runRestoreCrashMatrix --no-daemon
```

The task runs separate producer and verifier processes at eight Restore cutpoints:
`prepared`, `applying`, `write`, `barrier`, `force`, `verify`, `publication`, and `cleanup`.
Each producer must reach its marker before the verifier runs.
Each verifier recovers and reopens the interrupted world.

Markers live under `build/reports/lumi-crash-matrix/`.
[build.gradle](../../build.gradle) defines the task chain.
[LumiRestoreCrashClientGameTest](../../src/gametest/java/io/github/lumi/gametest/LumiRestoreCrashClientGameTest.java) defines the scenarios.
This matrix is separate from ordinary `build` and client functional runs.

## Performance checks

Run the tagged regression measurements:

```powershell
.\gradlew.bat regressionPerformanceTest --no-daemon
```

The task measures idle polling, origin durability, native palette capture, and overlapping zone shells.
Measurements go to `build/reports/lumi-regressions/`.
Its test report goes to `build/reports/tests/regressionPerformanceTest/index.html`.

Use a small history benchmark to check the harness:

```powershell
.\gradlew.bat '-Dlumi.gametest.suite=BENCHMARK' '-Dlumi.benchmark.enabled=true' '-Dlumi.benchmark.baseSize=64' '-Dlumi.benchmark.changeSize=32' '-Dlumi.benchmark.layers=2' '-Dlumi.benchmark.commits=3' '-Dlumi.benchmark.restoreSamples=4' runClientGameTest --no-daemon
```

[LumiHistoryBenchmarkConfig](../../src/gametest/java/io/github/lumi/gametest/LumiHistoryBenchmarkConfig.java) defines supported parameters and profiles.
Report warm loaded, cold stored, and mixed paths separately.
Use identical fixtures, heap settings, and environments for comparisons.

For repeated cold runs from one immutable fixture, use the Windows runner:

```powershell
.\scripts\run-cold-restore-benchmark.ps1 -Samples 5 -BaseSize 512 -ChangeSize 512 -Layers 16 -Commits 1
```

This is a large performance investigation, not a quick contribution check.
The [script](../../scripts/run-cold-restore-benchmark.ps1) recreates `build/cold-restore/` and reports each isolated sample.

## Before a release

Release checks:

- Repeated vanilla and Lumi idle measurements.
- Exact Save, Compare, and Restore workloads at several sizes.
- Real process-crash coverage across persistence phases.
- Multiple-client conflicts and disconnects before and after apply starts.
- Real integrated coverage for supported WorldEdit and Axiom workflows.
- Enforcement of one mutating operation per dimension.
- Load and memory regression gates.

The 100,000-change profile is one workload, not the full product contract.
State which gates ran, which passed, and which remain unverified in each release report.
