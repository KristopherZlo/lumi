# Reliability regression results (2026-10-11)

Baseline: `755750430` (`fix(preview): apply live biome tint`). Fixes and tests:
`068b40bce` through `f49bbb22b`. Minecraft 1.21.11, Fabric Loader 0.19.2,
Fabric API 0.141.3+1.21.11, Java 21.0.6. Native before/after worlds used isolated
checkouts on the same E: drive. The baseline checkout retained the original
production code. Runs used the same native Axiom and WorldEdit runtime jars.

## Functional verification

| Check | Result | Local evidence |
| --- | --- | --- |
| `gradlew test --rerun-tasks --no-daemon` | 876 tests, no failures or skips | `build/regression-unit.log`, `build/test-results/test/` |
| `gradlew regressionPerformanceTest --no-daemon` | 3 measurements passed | `build/regression-performance-final.log`, `build/test-results/regressionPerformanceTest/` |
| Native server GameTests | All 35 required tests passed | `build/regression-build.log` |
| `gradlew -Dlumi.gametest.suite=UI runClientGameTest --no-daemon` | UI workflow and UI contract passed, including typed Merge of conflicting edits | `build/regression-ui-final.log` |
| `gradlew runRestoreCrashMatrix --no-daemon` | All four producer/verifier pairs passed | `build/regression-crash-matrix-retry.log`, `build/reports/lumi-crash-matrix/` |
| `gradlew build -x runGameTest --no-daemon` | Passed after the server tests above | `build/regression-build-final.log` |

The crash matrix terminates real Minecraft processes at write, barrier, force
and verification cut points, then reopens each world. Its verifiers check exact
block and entity recovery, original entity UUID, expected ref revision, journal
completion and released freeze ownership. An initial attempt stalled in Fabric
client-test startup before world loading; the complete sequential retry passed.

The first full build reached and passed all 35 server tests, then its test
compilation collided with another Gradle rerun in the same checkout. That was
verification orchestration error. The complete unit suite and build were then
run sequentially and passed.

## Component measurements

The opt-in task warms both idle paths, measures nine paired batches of 1,000
polls, and records file-read JFR controls. Durable origin batches use one warmup
and seven measured fresh repositories. They include section-payload construction
and durable publication, followed by exact disk rereads outside the timed region.
Percentiles use nearest rank. Raw CSV and JFR files are in
`build/reports/lumi-regressions/`.

| Workload | Batch p50 | Batch p95 |
| --- | ---: | ---: |
| 1,000 fresh workspace metadata reads | 142.064 ms | 164.292 ms |
| 1,000 cached automatic-version polls | 0.059 ms | 0.266 ms |
| 32 first-touch air sections through durable origin/index publication | 23.266 ms | 42.832 ms |
| 256 first-touch air sections through durable origin/index publication | 106.550 ms | 110.313 ms |

The JFR control observed 40 repository file reads for 20 fresh polls and zero
for 20 cached polls. This demonstrates removal of that idle I/O path; it is not
a complete vanilla/Lumi idle comparison. Allocation CSV columns cover the
calling thread, not retained world heap or all JVM threads.

## Native small Save/Restore comparison

Three fresh JVMs per version used this existing profile, seed 710:

```powershell
./gradlew "-Dlumi.benchmark.enabled=true" "-Dlumi.benchmark.baseSize=64" "-Dlumi.benchmark.changeSize=32" "-Dlumi.benchmark.layers=2" "-Dlumi.benchmark.commits=3" "-Dlumi.benchmark.restoreSamples=4" runClientGameTest --no-daemon
```

Each run completed five Saves and four Restores with exact snapshot checks.
All six runs failed the existing complete-server-tick limit of 50 ms. The
limit was not changed. The isolated profile command should additionally pass
`-Dlumi.gametest.suite=BENCHMARK` as described in the regression guide.

| Metric | Samples per version | Before p50 | After p50 | Before p95 | After p95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Save total | 15 | 798 ms | 897 ms | 1,897 ms | 1,476 ms |
| Restore total | 12 | 767 ms | 756 ms | 1,378 ms | 1,464 ms |
| Restore apply | 12 | 237 ms | 291 ms | 319 ms | 368 ms |
| Maximum complete tick per Restore, rounded | 12 | 100 ms | 104 ms | 130 ms | 148 ms |

Save median increased by about 12% and apply median by about 23% in this sample;
Restore total median was similar. These are observed differences, not evidence
of no regression. Native entity counts and durable storage timings varied
between JVMs. The apply submetrics show substantial storage-sync variation,
but these runs do not establish the cause of the timing differences.

Raw events are archived under `history-before/` and `history-after/` in the
regression report directory; `history-comparison.csv` contains the aggregates.

## Native large Save comparison

The existing player-scale profile issues an immediate Save after each of 30
WorldEdit volumes, from one block to 4,194,304 blocks. It checks the expected
builder history-key count before saving, retains the origin backlog for Save
to drain, and enforces the unchanged 6,800-ms limit on every Save. An additional
setup Save makes 31 Saves per JVM. This is a Save workload, not a large Restore
measurement or a full retained-memory test.

```powershell
./gradlew "-Dlumi.gametest.suite=BENCHMARK" "-Dlumi.benchmark.enabled=true" "-Dlumi.benchmark.profile=player-scale-30" "-Dlumi.benchmark.heap=4G" "-Dlumi.benchmark.saveOnly=true" runClientGameTest --no-daemon
```

The first before/after commands omitted the suite selector. Both Save profiles
completed successfully, but both overall Gradle runs subsequently failed the
separate Exact Restore scenario's stored-path assertion: the fixture remained
resident instead of exercising the requested storage path. Exact world-state
assertions passed. These overall runs must not be described as passing suites.

One additional fresh JVM per version explicitly selected `BENCHMARK`. Both
complete runs passed, including the unchanged immediate-Save gate. The harness
now selects that isolated suite automatically when a benchmark is enabled and
no explicit suite is configured; explicit selections retain precedence.

| Changed blocks in a Save | Before first run | After first run | Before isolated repeat | After isolated repeat |
| --- | ---: | ---: | ---: | ---: |
| 1,048,576 | 2,325 ms | 1,748 ms | 1,269 ms | 1,323 ms |
| 2,097,152 | 2,529 ms | 1,622 ms | 1,675 ms | 1,688 ms |
| 4,194,304 | 4,789 ms | 5,520 ms | 3,238 ms | 3,311 ms |

Across the 60 differently sized builder Saves per version, p50 was 802/861 ms
and p95 was 2,325/1,748 ms before/after. These heterogeneous percentiles describe
this workload, not one fixed-size operation. The largest isolated-repeat Save
was about 2% slower after the fixes; the first pair differed by about 15%.
Two runs per version do not establish a stable speed change. Both versions
show substantial variation, and no universal speedup or lack of regression is
claimed. Setup Saves are excluded from these aggregates.

The post-largest-Save heap samples were 2,129 and 2,755 MiB before, versus 2,681
and 2,028 MiB after. No OOM occurred with the configured 4-GiB heap. These are
post-operation occupied-heap samples without forced collection, not peaks or
retained-memory measurements. They do not justify a captured-input rewrite.
Raw events and samples are in `large-save-before/`, `large-save-after/`,
`large-save-samples.csv` and `large-save-heap-samples.csv` under the regression
report directory. Focused command logs are
`build/regression-large-save-before-focused.log` and
`build/regression-large-save-after-focused.log`.

After the game-test-only selector correction, an additional current-version
run omitted `lumi.gametest.suite` and passed. Exactly one client scenario started,
all 31 Saves completed, and the largest Save took 2,958 ms. This run verifies
automatic benchmark isolation and is reported separately from the two paired
before/after runs above. Its log is `build/regression-benchmark-isolation.log`;
raw events are archived in `benchmark-isolation/` under the regression report
directory.

## Remaining limits

The component fixes do not establish release readiness. The complete-tick gate
still fails before and after the changes. The default client `ALL` run also
exposed the existing unloaded-fixture coverage problem described above.
Repeated complete vanilla/Lumi idle measurements, native multi-client conflict
and disconnect coverage, and the full large Restore/load matrix were not run.
The existing dense-profile three-second gate limits every complete Restore;
it is not a median requirement and was not relaxed.

Save's whole captured input was measured rather than redesigned. Heap samples
after Saves include the world, builder tools and unreachable allocations. They
do not prove a peak or retained bound. A streaming change requires a separate
memory investigation while preserving the exact frozen generation boundary.
No storage format, content hash, ref-CAS rule, recovery journal, force barrier
or durable reread was removed or weakened by these fixes.
