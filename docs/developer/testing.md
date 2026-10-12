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
| `PERSISTENCE_CRASH` | Save, Merge, and Import process-crash producers/verifiers; needs the publication runner |
| `BENCHMARK` | Opt-in history performance workloads |
| `MULTIPLAYER` | Two client JVMs against a TCP dedicated server; needs the multiplayer runner |

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

### Two real clients

Run `scripts/run-multiplayer-gate.ps1 -RunMinecraft` on Windows with JDK 21. It prepares the
existing GameTest classpath and launches separate owner and peer client JVMs.
Without `-RunMinecraft`, it exits before starting Gradle or clients. Minecraft
windows can take focus and use substantial CPU/GPU resources; schedule the run
when the computer is available. `-WindowStyle Hidden` hides the launcher window,
not Minecraft's rendering window.
Fabric starts a dedicated server in the owner JVM; both clients connect over TCP.
Each client has a separate game directory and offline identity. The file mailbox
only schedules test actions; Save, Restore and snapshots use production packets.
The runner preserves both logs and a candidate/version manifest in
`build/reports/lumi-multiplayer/`. A client failure, missing gate or timeout fails
the run. It stops only the JVMs it launched. The gate checks shared history,
two conflicting queued Saves, queued-client disconnect/reconnect, and revoked
actor authority at activation. A test-only mutation occupies the queue until
both real network requests are present; it never edits the world. Separate
GameTest-only mixins pause background Save publication and defer Restore ticks
after a physical block changes. Two TCP break packets must survive Save
publication as later dirty generations; restoring both commits checks their
exact boundaries. The Restore initiator then disconnects after mutation while
the freeze and journal are present. The server must finish the exact target,
clear the journal, and synchronize the reconnecting client. Pauses have a
three-minute timeout and are excluded from the production jar. These gates
cover one dimension; cross-dimension concurrency and standalone server JVM
coverage remain separate checks.

## Real process-crash matrix

GameTest-only persistence probes can cut staged object writes, pack force/move,
index force, commit completion, forced ref temporary files, Save journal
publication, captured-generation cleanup and final journal removal. They remain
disarmed during fixture creation, force a unique marker, and halt with exit code
86. These probes do not establish coverage until a producer reaches each marker
and a separate verifier reopens its preserved world.

`working-cleared` cuts after `MutationDurabilityTracker.complete` has drained
the captured-generation write. Normal Save uses this completion collaborator;
the repository's `clearCaptured` method belongs to startup recovery. A producer
fails immediately if its operation terminates without the selected crash marker.

`PERSISTENCE_CRASH` selects the Save/Import producer and verifier. The producer
creates durable prior history and an independent section oracle before arming
the cut. The verifier checks the complete reachable object inventory, expected
HEAD/revision, prior Save restoration, and a second clean reopen. Import uses a
separate donor repository so its object pack cannot be skipped as duplicate
data. An unpublished import must be safely retryable and must leave the
canonical HEAD and live world unchanged. This fixture covers one section;
large captures and entity/block-entity fixtures remain separate evidence.

Run `scripts/run-publication-crash-matrix.ps1 -RunMinecraft -Kind save` or
`-RunMinecraft -Kind import`. The explicit switch is required before any launch;
the matrix repeatedly opens Minecraft windows and can disrupt other games.
`-Phase journal-created` selects a focused Save cut; omit `-Phase` for all
declared cuts. Each cut receives a new preserved game directory. The runner
requires producer exit 86, its matching marker, verifier exit 0, and the named
verification gate. Results and logs are under `build/reports/lumi-publication-crash/`.
The runner prepares Gradle once per invocation and records a schema-2 manifest
with both process exit codes and SHA-256 fingerprints of the launch inputs and
completed cut's artifacts. JVM durations are recorded as UTC start/end times.

To continue a partially completed matrix on the same clean candidate, pass one
or more of these manifests with `-ResumeFrom`:

```powershell
.\scripts\run-publication-crash-matrix.ps1 -RunMinecraft -Kind merge -ResumeFrom 'build/reports/lumi-publication-crash/<run>/manifest.json'
```

Only fully verified requested cuts are reused. The commit, clean tree, matrix
kind, cold-fixture setting, compiled classpath/JDK/configuration fingerprint,
host/OS/GPU driver, JVM environment options, process receipts, marker, verifier
gate, logs, and preserved world must match. The runner hashes actual files,
including directory contents; Git SHA alone is insufficient for ignored build
outputs. Invalid evidence stops the invocation before clients start. Schema-1
reports from previous runs cannot be reused automatically. Keep the source
artifact directories; resumed manifests reference their unchanged contents.
Remaining cuts still get new worlds and separate crashed/restarted JVMs. A full
resumption starts no clients but still performs launch preparation and evidence
validation. These are functional crash results, not new performance samples.

The release runner accepts the same manifests per gate, for example
`-CrashResumeFrom @{ MergeCrash = 'build/reports/lumi-publication-crash/<run>/manifest.json' }`.
Other release gates and manual evidence requirements remain in force.

Save has twelve cuts; Import has nine, including preview publication. A subset
run proves only its listed cuts. `-Kind merge` covers the loose object and commit
writes during preparation, then the eight existing Restore persistence phases
through a real divergent Merge. Its verifier resumes the target, checks the
source-wins section oracle and both commit parents, restores the prior current
commit, and reopens the world again. Return-checkpoint recovery needs its own
run and is not implied by resume-target evidence.
The dense block oracle preserves current-only edits wherever the source equals
the common base; source-wins conflict resolution does not replace the whole
section with the source section.

The branch hotkey test driver dismisses onboarding after the target branch's
snapshot arrives, just as the Save and dashboard drivers do. Onboarding may
appear during that snapshot wait in a fresh crash-fixture client.

Crash verifiers call the production runtime Restore entry point; the separate
multiplayer gate exercises the client packet path. The fixtures use Fabric's
consistent test-world settings and wait for both active and queued operations.

On Windows, use the prepared-launch runner for Restore too:

```powershell
.\scripts\run-publication-crash-matrix.ps1 -RunMinecraft -Kind restore
```

It prepares the GameTest classpath once, then launches fresh producer/verifier
JVMs directly at all eight cuts. This removes sixteen nested Gradle invocations;
it still starts sixteen real clients. Like the publication runner, it uses a 2 GiB
heap and a separate game directory per cut. It requires exit 86 and the exact
Restore marker, then exit 0 and a success gate emitted after recovery, exact
world/ref/journal assertions, and the second clean reopen. Use `-RestoreCold`
to include the existing cold stored-world digest fixture. The release runner's
`RestoreCrash` gate uses this path. Only the producer receives crash-injection
properties; the verifier receives the expected phase with injection disarmed,
as in the original Gradle matrix.

The original Gradle task remains available, including on other operating systems:

```powershell
.\gradlew.bat runRestoreCrashMatrix --no-daemon
```

The task runs separate producer and verifier processes at eight Restore cutpoints:
`prepared`, `applying`, `write`, `barrier`, `force`, `verify`, `publication`, and `cleanup`.
Each producer must reach its marker before the verifier runs.
Each verifier recovers and reopens the interrupted world.

The prepared-launch runner preserves markers and worlds under
`build/reports/lumi-publication-crash/`; the Gradle task keeps markers under
`build/reports/lumi-crash-matrix/`.
[build.gradle](../../build.gradle) defines the task chain.
[LumiRestoreCrashClientGameTest](../../src/gametest/java/io/github/lumi/gametest/LumiRestoreCrashClientGameTest.java) defines the scenarios.
This matrix is separate from ordinary `build` and client functional runs.

The runner contract has standalone checks in
`scripts/tests/CrashMatrixDefinition.Tests.ps1`. They do not start Gradle or Minecraft.
The runner loads its helper types relative to its own script directory, including
on Windows PowerShell 5.1.

```powershell
.\scripts\tests\CrashMatrixDefinition.Tests.ps1
```

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

### Repeated endpoint Restore

The benchmark also accepts `-Dlumi.benchmark.soak=true` with an isolated
`existingWorldSource`, `existingWorld`, and `coldManifest` from the cold fixture
producer. It alternates Initial and Latest, verifies the manifest's exact area
after every Restore, observes unpaused ticking, then closes and reopens Latest.
`soakCycles` defaults to 20; `observeSeconds` defaults to 1800 wall-clock seconds.
Use 2 cycles and 10 seconds to check the harness before a long run.

Post-Restore samples report cumulative process CPU, used/committed heap, and FPS.
The exact oracle runs outside the timed Restore interval and can load its area.
This is a functional soak, not a foreground GPU, ordinary-gameplay, or vanilla
equivalence result. Those measurements need matching ordinary-client controls.

Record AFK/background FPS throttling and server delay warnings during a soak.
Fabric GameTest limits client ticks to one per frame and synchronizes client/server
phases even with the network synchronizer disabled. Low FPS can therefore delay
the server and distort load measurements.

### Save memory investigation

Add `-Dlumi.benchmark.memory=true` to a benchmark run. A test-only sampler records
process heap every 2 ms throughout each UI Save and releases its thread afterward.
Samples include client and integrated-server allocations; the observed maximum
is a sampled peak, not a proof that no shorter peak occurred. `memoryGc=true`
requests GC after the timed operation and reports whether a collection occurred.
Post-GC used heap is not a retained-object graph measurement.

Use `heapDumpSave=<exact-save-name>` to write one live `.hprof` after that Save.
Inspect its dominators and paths to GC roots before claiming capture was released.
Start with the small benchmark, then the `player-scale-30` `saveOnly` profile under
fixed heap settings. Record fixture size, NBT/entropy, GC and sampler overhead.
Do not include exact snapshot oracles or heap-dump pauses in Save timing.
For a small native fixture, `memoryOnly=true` stops after Save measurements;
it requires `memory=true` and does not run or approve Restore performance gates.

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
