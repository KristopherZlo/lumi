# Preserve the cold stored Restore path

Branch: `perf/cold-restore-pipeline`, baseline: `6e424fa7c` (`main`).

## Cause and selected change

Restore intent prewarm retained every chunk in the first decoded window,
including unloaded chunks eligible for direct vanilla-storage rewrites.
FULL generation and lighting-neighbor tickets also create surrounding chunk
holders. `ChunkLoadGate` must reject both live chunks and existing holders:
rewriting their storage would race vanilla loading or pending unload writes.
Those refusals propagate into subsequent windows as `RESIDENT` fallbacks.

The first baseline measurement applied 4,194,724 changes over 1,042 chunks,
including the 1,024-chunk dense fixture. Only 110 used stored rewrites;
932 fell back to loading. These are measured path counts, not evidence that
the load gate is unnecessary.

Prewarm now retains already-resident chunks and first-window chunks whose
decoded delta requires loading. A shared `DecodedSection.supportsStoredRewrite`
predicate keeps this decision aligned with the stored adapter: missing
directional deltas and changed POI still require loaded application.
An eligible cold chunk rejected later because its storage is missing,
unsupported, or concurrently resident still uses the existing loading fallback.

The change keeps transferred resident prewarm, fresh-return recomposition,
the 128-cold-chunk window and slab bounds, background decoding, entity cleanup,
the single final barrier, region force, exact durable reread, and ref publication
ordering. It does not change the persisted format or increase concurrency.

The first prewarm-only candidate did not materially improve three fresh-JVM
samples: 16.482, 15.077 and 16.561 seconds, with 110/142/142 stored chunks.
The remaining cause was the interaction with resident-window coalescing
(`8e688d1a2`): a window may contain resident chunks plus 128 cold chunks, but
`PreparedWorldMutationSession` marked classification complete after just one
128-total-chunk write batch. Bulk loading then retained the unclassified tail,
promoting eligible cold chunks and their surrounding holders to fallback.

Classification now traverses the complete window in sequential batches of at
most 128 before requesting any fallback tickets. The two limits keep their
original meanings; resident coalescing and cold-memory limits stay intact.
The new 34-resident/128-cold regression failed before this ordering correction.
It also holds the second write future incomplete to prove no loaded apply or
persistence starts while the tail is unclassified.

## History and alternatives

The retained block-ticket prewarm (`260a2b5cc`, `3ecd97360`) improved its historical
median from 5.853 to 4.109 seconds. Preserve that benefit for resident builders.
Entity prewarm (`cda1a694e`, reverted by `e71b5756a`) added heap without a gain.
Extra decode lookahead regressed stored application; origin packing
(`65d7e5a6e`, reverted by `29bf7c206`) regressed cold and warm Restore.
See [the investigation](performance-investigation.md) for their original contexts.

Ready-chunk write pipelining, bounded lookahead for necessary fallback loads,
and exact stored POI rewrites remain candidates. They need separate evidence;
first remove unnecessary loading before adding scheduling or storage machinery.

## Verification

Two new regression tests were run against unchanged production code and both
failed on unwanted prewarm tickets. They also cover transfer without mutation,
final durable verification, resident/POI selection across multiple sections,
negative coordinates and loading after a stored rejection.

Native comparisons use the existing immutable `build/cold-restore/fixture` and
its SHA-256 manifest. Every sample opens a new copied save in a fresh JVM and
performs Latest-to-Initial as its first mutation, then checks the exact endpoint
digest. Both variants use the same one-worker Gradle configuration and a 1-GiB,
two-processor Minecraft JVM. JVM-cold is not an operating-system disk-cache flush.
The JSONL report and properties retain separate preparation, readiness,
application, write, barrier/force, verification, tick and heap measurements.

### Repeated comparison, October 12, 2026

The fixture has 4,194,304 dense blocks over 1,024 distant chunks. Each Restore
reported 4,194,724 changed blocks over 1,042 chunks, including nearby changes.
Its 1,815-file manifest digest is
`87d5d94d8e17fdafc072dfbb150b8c269a89882587a2aa17f83a79ec2cec3848`.
The baseline uses production files from `6e424fa7c`; the complete candidate uses
`c146b483b`. Both use the same measurement harness. Sources were restored after
the baseline runs. Native runs were sequential, with Oracle Java's busy language
server temporarily stopped under the user's authorization.

| Fresh-JVM sample | Main, seconds | Complete change, seconds | Main stored / loaded | Change stored / loaded |
| --- | ---: | ---: | ---: | ---: |
| 1 | 16.116 | 8.221 | 110 / 932 | 1024 / 18 |
| 2 | 14.812 | 8.322 | 142 / 900 | 1024 / 18 |
| 3 | 14.921 | 8.160 | 142 / 900 | 1024 / 18 |
| 4 | 15.604 | 9.716 | 110 / 932 | 1024 / 18 |
| 5 | 13.787 | 9.382 | 110 / 932 | 1024 / 18 |
| Median | **14.921** | **8.322** | | |

All ten endpoint digests matched. The median confirmation-to-durable-terminal
time decreased **44.2%**, or **1.79 times faster** on this fixture. The properties
key `confirmationToClientAckMillis` is a legacy name for this terminal-event
measurement, not a separate rendered-client acknowledgement. Additional heap
median decreased from **366.5 to 113.7 MiB**. Application median decreased from
3.929 to 1.584 seconds. Chunk readiness fell from roughly 4.4--5.9 seconds to
0.24--0.30 seconds; the first baseline's readiness is available in its log.

Run order was three baseline cold samples, three prewarm-only samples, three
complete samples, two additional baseline samples, baseline warm controls,
two additional complete samples, and candidate warm controls. The fresh JVMs
and repeated baseline reduce warmup/order bias, but do not establish a confidence
interval or an operating-system cold-cache result.

Warm controls used three fresh JVMs per variant, seed 710, a 64x64x2 base,
32x32 edits, three commits and four loaded-only Restores per JVM. The respective
run medians were **903 / 805.5 / 857.5 ms** on main and
**886 / 824 / 788.5 ms** with the change. Ambient captures differ between these
generated worlds; this is a control for a large warm-path regression, not proof
of statistical equivalence or a warm-path speedup.

Failed performance gates are retained. One warm run per variant exceeded the
unchanged 50-ms tick limit: **75.8 ms** on main and **53.4 ms** on the candidate;
all four Restores in each run completed. Four of five baseline cold samples and
one of five candidate samples also had ticks above 50 ms, with maxima of
**280.2 and 150.0 ms**. The strict 512x512x16 targets of three seconds total and
750 ms application are still unmet. Cold sample success records exactness and
the requested stored-path coverage; it does not replace those strict budgets.

The full unit suite passed **928 tests**, including five new regressions, and
the performance suite passed **nine tests**. The native EXACT suite passed after
making its cold precondition explicit. Its first run preserved every endpoint
but failed four stored-path assertions. The fixture now waits for 20 consecutive
ticks without active terrain, updating/pending-unload holders or loaded entities
before each stored operation. All original state and path assertions remain;
each of the four stored/mixed transitions applied 32 stored chunks. This follows
the coverage gap already recorded in `performance-investigation.md`.

Raw measurements are in `build/cold-restore/{baseline,complete}-oct12-0*.properties`
and `build/reports/lumi-ui-audit/cold-*-oct12-0*.log`. Derived cold/warm CSVs and
`cold-ab-summary-oct12.json` are in the same report directory. These local
artifacts are not committed; the table preserves the observed results.

For native cold samples, `-Dlumi.benchmark.minimumStoredChunks=1024` requires
the full dense fixture to retain the stored path in addition to its exact digest.
Crash producers accept `-Dlumi.gametest.restoreCrashCold=true`: they add a
131,072-block, 32-chunk distant target/return pair, wait for vanilla unload,
complete intent prewarm, and assert no fixture chunk or holder was loaded.
Recovery and a second clean reopen must both match that target digest as well
as the original nearby blocks, armor stand, ref, journal and freeze checks.

### Reproduction

Use JDK 21. The native resource limit init script used for both variants is:

```groovy
allprojects {
    tasks.withType(Test).configureEach {
        maxParallelForks = 1
        maxHeapSize = '512m'
        jvmArgs '-XX:ActiveProcessorCount=1'
    }
    tasks.withType(JavaExec).configureEach {
        maxHeapSize = '1g'
        jvmArgs '-XX:ActiveProcessorCount=2'
    }
}
```

Save it as `build/reports/lumi-ui-audit/limited-interaction.init.gradle`. Common
Gradle arguments are `--offline --no-daemon --max-workers=1`, that init script,
`-Dorg.gradle.parallel=false`, and
`"-Dorg.gradle.jvmargs=-Xmx768m -XX:ActiveProcessorCount=1"`. An offline run needs
the already-resolved dependencies. The measurements used
`--gradle-user-home C:\tmp\lumi-regression-gradle` and an explicit installed
JDK-21 toolchain path.

Run `test regressionPerformanceTest compileGametestJava remapJar` for local
checks. Run `-Dlumi.gametest.suite=EXACT runClientGameTest` for exactness. Native
checks used `-Dlumi.gametest.operationTimeoutTicks=2400` for bounded fixture waits.

For each cold sample, add these properties before `runClientGameTest` and use a
different result path. Verify the immutable source digest before and after:

```text
-Dlumi.gametest.suite=BENCHMARK
-Dlumi.benchmark.enabled=true
-Dlumi.benchmark.coldMode=measure
-Dlumi.benchmark.coldManifest=<absolute fixture.properties path>
-Dlumi.benchmark.coldResult=<absolute sample.properties path>
-Dlumi.benchmark.existingWorldSource=<absolute immutable fixture directory>
-Dlumi.benchmark.existingWorld=Lumi behavior seed 710
-Dlumi.benchmark.minimumStoredChunks=1024
```

Quote each argument containing spaces. Use the minimum-stored guard for the
candidate; it intentionally fails on this baseline. The existing
`scripts/run-cold-restore-benchmark.ps1` creates a new fixture, so do not invoke
it over the preserved A/B fixture directory during this comparison.

For each phase `prepared`, `applying`, `write`, `barrier`, `force`, `verify`,
`publication`, and `cleanup`, invoke `runClientGameTest` twice, sequentially:

- Producer: `-Dlumi.gametest.suite=RESTORE_CRASH`,
  `-Dlumi.gametest.restoreCrashCold=true`,
  `-Dlumi.gametest.restoreCrashMode=produce`,
  `-Dlumi.gametest.restoreCrashPhase=<phase>`, and
  `-Dlumi.gametest.restoreCrashMarker=<fresh absolute marker path>`.
- Verifier: `-Dlumi.gametest.suite=RESTORE_CRASH`,
  `-Dlumi.gametest.restoreCrashMode=verify`, and
  `-Dlumi.gametest.restoreCrashExpectedPhase=<phase>`.

The producer must reach its marker. Verification preserves that world's run
directory and checks recovery followed by a second clean reopen. Do not run a
different native suite between the pair. The outer `runRestoreCrashMatrix` task
does not forward the optional cold-fixture flag to its child Gradle processes;
these cold checks invoke the producer/verifier pair directly.

## Remaining options

The complete candidate spends roughly 1.7--1.9 seconds reading and preparing
stored chunks and 1.4--1.7 seconds writing them. These counters span asynchronous
work and cannot be added as independent tick costs. The next experiment should
trace immutable payload preparation and ready-chunk writes, then overlap only
measured waits within the existing 128-chunk backpressure limit. Adding another
decoder pool or increasing cache/slab limits repeats previously rejected work.

For worlds where changed POI or missing/unsupported storage forces loading,
bounded lookahead of necessary fallback chunks remains useful to test. Exact
stored POI rewrites are a larger change: chunk and POI storage need coordinated
force, reread and crash coverage. Neither option is implemented here.
