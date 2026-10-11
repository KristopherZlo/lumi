# Four performance changes

Implementation follows the [cause investigation](performance-investigation.md).
Minecraft APIs, persistence formats, canonical object IDs, ref CAS, origin
durability and captured-generation boundaries remain the existing contracts.
Rust is outside this work.

## Restore persistence

`RestorePersistence` owns one worker task at a time. Production
`MinecraftWorldStateApply` supplies the existing background executor. Tests may
use the synchronous adapter default or a controlled executor.

Creation and advancement of the journal precede world apply. Verified world
persistence precedes publication; publication and working-index durability
precede runtime installation and background journal cleanup. The dimension
remains frozen until cleanup completes. Safe return and degraded journals use
the same worker. A tick may await the worker only through its remaining
deadline. Slow `force` calls therefore cannot extend that deadline.

`RestorePublication.persist` and `persistReturn` run on the worker and must use
immutable prepared state and thread-safe repositories. Generation tracking and
chunk retention run in `finishOnServer` / `finishReturnOnServer`, before waiting
for index durability. Minecraft runtime selection belongs in `installed`, which
runs on the server thread after durable publication. Standalone synchronous
`publish` / `publishReturn` calls retain their original behavior for callers.
Once a persistence task owns activation, cancellation is refused rather than
abandoning an in-flight journal. Shutdown joins the owned writer before closing
the prepared plan and leaves unfinished durable journals for recovery.
Shutdown also closes the prepared plan and native sessions when joining a failed
publication; the durable recovery journal is retained. This has a regression test.

The regression controls every journal/publication/cleanup task and verifies
that world apply and freeze release cannot cross an unfinished write. A blocked
writer test measures a 5 ms tick wait independently of disk speed.

Native JFR validation exposed an indirect tick wait: progress feedback reread
the branch ref while its worker held the repository monitor across `force`.
Progress now carries the immutable request context without repository I/O.
Accepted/progress events update activity and progress only; client history keeps
its latest published head until a terminal event or authoritative snapshot.
Authority checks and activation/ref validation continue to use fresh reads.
The client regression covers both stale queued refs and speculative future refs.

## Save metadata reads

Pack entry headers and compressed payloads are read in one contiguous buffer.
Indexed lengths are bounded before allocation, and entry lengths, LZ4 output,
content hashes, EOF and pack headers retain their validation.

Save sorts changed chunk metadata by the existing physical pack order one region
at a time (at most 1024 chunks). A Save-local LRU reuses only successfully
verified immutable chunk trees, with ceilings of 256 nodes and 4096 child
references. Oversized nodes bypass the cache; failures are never cached. Restore
verification readers remain fresh. No extra parallel decoding tasks are added.

## Working-index coalescing

Production groups ordinary index scheduling for up to 50 ms using a delayed
executor, without sleeping on a persistence worker. The writer still snapshots
the newest complete working/builder state and verifies durable origins before
each atomic forced write. Only one writer owns the index.

A Save/Restore durability boundary, captured-generation clear, recovery tracking,
explicit durability wait or blocked vanilla publication bypasses the delay.
Urgency survives an origin that has not finished yet. An already queued timer
cannot dispatch the same writer again after an urgent flush. Chunk retention
and publication gates still require the actual durable generations, not timer
completion. Existing synchronous/test adapters retain zero-delay scheduling.

Shutdown takes ownership of pending timer dispatch before the server session
closes its durability worker. If a timer has already claimed dispatch, shutdown
waits until its worker submission finishes. Subsequent index writes stay with the
closing caller or an already-owned origin worker, without a new delayed task or
submission to a closing pool. Origin validation and forced writes still apply.
Regressions cover the claimed-dispatch race and final generation/origin durability
after the executor stops accepting tasks.

An opt-in performance regression records actual `jdk.FileForce` events for a
32-mutation burst, comparing immediate and production 50 ms scheduling in three
fresh repositories. It requires fewer forced writes and rereads generation 33
from disk after an urgent boundary, checking that vanilla publication is allowed.

## Native palette capture

`MinecraftSectionPaletteCapture` holds the existing section lock while copying
native bit-storage indexes and only the palette entries actually used by its
4096 cells. It sorts distinct encoded states and remaps compact short indexes
directly into `SectionBlob.fromPalette`. Uniform, local and global native palettes
use the same canonical payload as the former expanded-list capture. Unused native
entries are excluded, and global native IDs are compacted before conversion to
short. Block-entity canonical NBT remains unchanged.

Two access-widener entries expose the immutable native data snapshot and its
existing public accessors to the adapter; no additional mixin is installed.
Tests cover palette transitions, 4096 distinct states, block entities, snapshot
immutability, stale native entries and lock release after failure.

## Verification

On 2026-10-11, Java 21.0.6 and Minecraft 1.21.11:

- `test`: 897 tests, no failures, errors or skips.
- `regressionPerformanceTest`: 7 tests, no failures, errors or skips.
- `build -x runGameTest` and `compileGametestJava`: passed, including access
  widener validation and the remapped distributable.
- Server GameTests: all 35 required tests passed after publication ownership
  was corrected (`18597f1a4`).
- Client UI suite: both scenarios passed on the shutdown fix (`cd0a338a0`).
- Real-process Restore crash matrix: all eight producer/verifier pairs passed
  on `18597f1a4`; each verifier checks recovery and a second reopen.

The real-process crash matrix now includes `prepared`, `applying`, `publication`
and `cleanup` in addition to `write`, `barrier`, `force` and `verify`. Reopening
before ref publication must freeze for recovery. Once the verified target ref is
durable, the existing `PublishedApplyRecovery` proof finalizes cleanup during
startup; both publication/cleanup crashes must show the exact target without a
recovery dialog. Each verifier reopens again
to check world/entity/ref state and absence of an active journal or freeze.

The first server-suite run exposed server-only chunk retention in checkpoint
Undo publication. File persistence and server-side generation tracking were
split without relaxing that guard. A real-worker regression covers pending and
checkpoint tracking; the rerun passed all 35 required server GameTests.

## Component measurements

The final performance run measures seven samples of 128 section captures after
two warmups, comparing the former expanded-list algorithm with native palette
capture in the same JVM. Canonical payload equality is tested independently.

| Used states | Expanded p50, ms | Native p50, ms | Expanded median allocated bytes | Native median allocated bytes |
| --- | ---: | ---: | ---: | ---: |
| 1 | 17.232 | 0.460 | 4,298,768 | 2,140,176 |
| 257 | 49.013 | 11.277 | 7,963,664 | 5,160,976 |
| 4096 | 138.263 | 117.398 | 66,938,896 | 47,326,224 |

The index benchmark uses three fresh repositories and a manually drained worker
queue. Ordinary mutations are followed by an urgent boundary, so it measures
the burst and forced persistence rather than waiting out the timer. Actual
`jdk.FileForce` counts were 32 versus 1 in every pair; p50 burst time was
74.992 versus 3.124 ms. Each pair rereads exact generation 33 from disk and
checks the vanilla publication gate. These are component results, not a claim
that whole-world Restore is faster by the same ratio.

CSV files are in `build/reports/lumi-regressions/`: `palette-*.csv`,
`index-burst-{immediate,coalesced,forces}.csv`. Unit/benchmark reports are in
`build/reports/tests/{test,regressionPerformanceTest}/`.

## Native small workload

Three fresh integrated JVMs per version use identical 64x64x2 base,
32x32 changes, three commits and four Restore samples. Baseline is `7a99788ca`
in a detached worktree; final production code is `cd0a338a0`. Runs are sequential
with no JFR recording. No failing run is removed from the comparison.

| Measurement | Baseline p50 / p95, ms | Final p50 / p95, ms |
| --- | ---: | ---: |
| Save total (15 samples per version) | 1400 / 2545 | 902 / 1253 |
| Restore total (12 samples per version) | 805.5 / 1715 | 801.5 / 1025 |
| Restore application | 254.5 / 678 | 243 / 275 |
| Maximum complete server tick per Restore | 116 / 249 | 33 / 50 |

All operations completed. The unchanged 50 ms complete-tick gate failed in
all three baseline runs (249.324, 122.750 and 116.579 ms). It passed two of the
three final runs; the other reached 50.2325 ms. This is a remaining gate failure,
not a proven stable 50 ms bound. Rounded CSV millisecond fields must not be used
to decide that gate. Restore's median total time is essentially unchanged.

Raw reports are in `build/reports/lumi-behavior/` and the detached baseline's
corresponding directory. Final reports start `20261011-085601`, `085704` and
`085812`; baseline reports start `20261011-081332`, `082332` and `082603`.
Aggregated samples and gate outcomes are
`build/reports/lumi-regressions/four-steps/{samples,gates}.csv`.

## Native large Save workload

Two fresh integrated JVMs per version run the existing `player-scale-30`
Save-only profile with a 4 GiB heap. Every run contains 31 Saves, with WorldEdit
edits ranging up to 4,194,304 blocks. Save owns its durability wait: pending
writes are not pre-drained. Preview generation is disabled by the existing
profile. Baseline and final revisions are the same as the small comparison.

All four runs completed and every Save passed the unchanged 6800 ms limit.

| Measurement | Baseline | Final |
| --- | ---: | ---: |
| All 62 Saves, p50 / p95, ms | 829 / 2397 | 797.5 / 2368 |
| 4,194,304-block Save, run 1 / run 2, ms | 4624 / 5199 | 2454 / 2705 |
| Maximum Save, ms | 5199 | 2705 |

Most profile edits are small, and the overall median changes little. The largest
Save improved in both pairs. Observed progress phases for that Save were:

| Phase | Baseline run 1 / run 2, ms | Final run 1 / run 2, ms |
| --- | ---: | ---: |
| Visible capture | 548 / 531 | 126 / 86 |
| Writing captured state | 501 / 565 | 176 / 170 |
| Building history tree | 1657 / 1748 | 42 / 49 |
| Finalizing working index | 50 / 49 | 295 / 303 |

These phases are sampled by the existing progress probe; they are not isolated
component benchmarks and do not sum to the precise end-to-end worker time.
The longer final index phase remains visible in the result. Save's faster total
does not establish faster large Restore or lower whole-process peak memory.

Baseline reports start `20261011-085931` and `090525`; final reports start
`20261011-090234` and `090828`. Raw operation samples, including block/key counts,
are in `build/reports/lumi-regressions/four-steps/large-save-samples.csv`.
Full Gradle/native logs are `build/four-step-{before,after}-large-{1,2}.log`.

## Reproduce

Use Java 21 and run native workloads sequentially. The crash matrix starts
child Gradle processes, so a custom Gradle cache must be set in
`GRADLE_USER_HOME`, not just the parent's command-line option.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot'
$env:GRADLE_USER_HOME = 'C:\tmp\lumi-regression-gradle'
.\gradlew.bat test regressionPerformanceTest build -x runGameTest --no-daemon
.\gradlew.bat runGameTest --no-daemon
.\gradlew.bat '-Dlumi.gametest.suite=UI' runClientGameTest --no-daemon
.\gradlew.bat runRestoreCrashMatrix --no-daemon
.\gradlew.bat '-Dlumi.benchmark.enabled=true' '-Dlumi.benchmark.baseSize=64' '-Dlumi.benchmark.changeSize=32' '-Dlumi.benchmark.layers=2' '-Dlumi.benchmark.commits=3' '-Dlumi.benchmark.restoreSamples=4' runClientGameTest --no-daemon
.\gradlew.bat '-Dlumi.benchmark.enabled=true' '-Dlumi.benchmark.profile=player-scale-30' '-Dlumi.benchmark.heap=4G' '-Dlumi.benchmark.saveOnly=true' runClientGameTest --no-daemon
```

Repeat small workloads three times and large Save workloads twice in each
checkout. A failing performance gate is part of the result; retain its report.
Native client tests recreate `build/run/clientGameTest`, while behavior reports
under `build/reports/lumi-behavior` survive. Preserve any diagnostic recordings
or worlds before starting the next native run.

Repeated vanilla/Lumi idle A/B, multi-client conflict/disconnect coverage and
the full size/load matrix were not run for these four changes. These results
do not establish release readiness or a three-second large Restore target.
