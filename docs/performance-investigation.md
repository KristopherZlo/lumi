# Performance causes, previous attempts, and next experiments

Investigated on 2026-10-11, against production commit `8e2f9776f`, Minecraft
1.21.11 and Java 21.0.6. Production code was not changed for this investigation.
The only code addition is optional JFR instrumentation in the GameTest tick
probe. This report complements [the regression results](reliability-regression-results.md).

The subsequently authorized implementation is tracked in
[Four performance changes](performance-four-steps.md).

## Evidence and limits

The investigation followed `modules.md`, the Save/Restore/persistence owners,
their tests, Git changes and reverts, `behavior-test-findings.md`, and
`LUMI_ARCHITECTURE_RELIABILITY_RESEARCH_RU.md`. Historical results below belong
to their original fixtures and machines; they are not new before/after results.

New recordings are local artifacts under `build/reports/lumi-investigation/`:

- `small-phased.jfr`, `small-phased-gc.log`, `small-phased-run.log`: five Saves
  and four Restores, base 64, change 32, two layers, three changes, seed 710.
- `large.jfr`, `large-gc.log`, `large-run.log`: the existing player-scale-30
  Save-only workload, 31 successful Saves, changes up to 4,194,304 blocks,
  a 4-GiB heap, and the unchanged 6,800-ms Save gate.
- `small-phased-summary.txt`, `large-tree-summary.txt`,
  `large-save30-summary.txt`: streaming JFR analysis.

Corresponding behavior reports are
`build/reports/lumi-behavior/20261011-070804-history-benchmark-64x64x2-3/`
and `build/reports/lumi-behavior/20261011-070135-history-benchmark-512x512x16-30-player-scale-30-save-only/`.
Their timestamps identify the world-scenario start, not the recording start.

JFR method samples are samples, not exact CPU accounting. Allocation weights
estimate allocation traffic, not retained heap. Instrumented runs locate causes;
the uninstrumented paired runs in the regression results remain the timing
comparison. File waits include time spent blocked or descheduled. The operating
system, device, and other processes were not independently controlled. No
specific disk or antivirus cause is claimed.

Two initial tick traces began after Lumi's START_SERVER_TICK listener and thus
missed its operation work. Their tick-span measurements are not used. The final
probe uses an earlier Fabric event phase. Its START-to-END spans include Lumi
and vanilla tick work. Minecraft's original complete-tick counter remains the
performance gate and also includes work outside those event boundaries.

## Confirmed causes

### Restore performs synchronous repository I/O on the server thread

`RestoreOperation.tick` creates the PREPARED journal synchronously. Creation
writes the generation sidecar when present and then the journal.
`applyTarget` synchronously advances it to APPLYING. `publishTarget` calls the
publication collaborator on the same thread; ordinary Restore calls
`BranchRefRepository.compareAndSet`, also a forced atomic file write.
Journal cleanup reads and deletes files on that thread too.

All four operation maxima in the final small recording exceeded 50 ms:
approximately 97, 65, 106 and 126 ms. The longest JFR span was 126.986 ms:

- two journal `FileForce` events consumed 44.166 and 39.144 ms;
- waiting for background preparation consumed 29.125 ms;
- no GC pause overlapped that tick.

These are direct call stacks, not an inference from high-level phase names.
Other traced ticks contained ref-publication forces of 31-46 ms. A cooperative
deadline cannot interrupt an already started file force. The previous shared
30-ms scheduler fix therefore does not eliminate this source of tick stalls.

The journal ordering is deliberate: durable PREPARED precedes mutation, durable
APPLYING precedes apply, verified durable world data precedes ref publication,
and durable captured-generation clearing precedes journal removal. Keep that
order when moving the file work to an executor.

### Restore application time includes required persistence waits

In the earlier uninstrumented small runs, median loaded apply was 11 ms while
median storage synchronization was 186 ms, including a 145-ms write barrier
and a 43-ms affected-region force. Those are independent medians and should
not be added to reconstruct a particular operation.

`MinecraftRestorePersistenceSession` stages immutable vanilla snapshots, waits
for queued writes, forces affected stores, and rereads exact persisted state.
`MinecraftRegionStorageSynchronizer` scopes force to affected regions, but the
preceding vanilla synchronization barrier is storage-wide. Unrelated queued
vanilla writes can therefore contribute to its wait.

This is not slow per-block placement. Native palette replacement, decoded slabs,
off-thread preparation, one final barrier and force/reread overlap already
exist. Removing the durable barrier or final reread would change the recovery
guarantee. Moving a wait off the tick does not make the device complete sooner.

### The reproduced Save tree outlier is dominated by pack reads

The final large Save took 5,568 ms through the UI test workflow. The server's
own operation log reported publication in 4,186 ms, including preparation and
capture; these clocks have different boundaries. Preparation reached 1,045
keys at 334 ms and capture reached 1,031 sections plus 14 entity chunks at
809 ms. The visible-world capture itself was about 475 ms.

The reported tree-building phase lasted 2,197 ms. JFR recorded approximately
2,055 ms of sequential file reads in its operation worker:

- 1,292 ms reading old ChunkTree entries;
- 763 ms opening pack channels and checking their headers;
- no GC pause in this phase.

Some individual reads of only 48-119 bytes took 0.1-0.3 seconds. This establishes
an I/O wait on the actual Save call path. It does not establish why the OS
delayed those reads. During the same phase the durability worker issued about
35 working-index forces, including calls crossing the phase boundaries.
Those writes were sequential; thread-name changes do not imply concurrent
writers to the index.

`MerkleTreeEditor.update` reads the old node for each changed chunk.
`WorldObjectRepository.ReadSession.readChunk` decodes each read and does not
cache the decoded tree node. The pack reader already reuses at most 32 open
channels, but opens still read a validated header and each object read performs
decompression/hash verification. Identical content-addressed nodes referenced
by multiple chunks can thus incur repeated reads. Measuring unique IDs and
channel misses is needed before choosing a cache size or a different read order.

The working-index writer already coalesces revisions while a write is in
flight. Once it catches up, the next mutation schedules another full atomic
index write. Ambient changes after Save releases its frozen capture can keep
this traffic active during object publication. Its publication gates are part
of exact recovery; do not remove them merely to reduce force counts.

### Whole-heap measurements do not establish a multi-gigabyte Save snapshot

`SectionBlob` already stores a palette and `short[4096]`. The index arrays for
1,031 captured sections occupy approximately 8.05 MiB before object headers,
palettes and block-entity payloads. The capture retains O(changed keys) state,
but that does not explain the complete 2-3-GiB occupied JVM heap.

`MinecraftSectionCapture` temporarily expands each native section to 4,096
string references. `SectionBlob` then constructs a sorted unique palette,
remaps the cells and copies index arrays. This is avoidable work on the tick,
even though state strings themselves are cached rather than recreated per cell.

In the large Save window, JFR allocation samples included substantial vanilla
palette serialization and Java LZ4 compressor allocations. A young collection
reduced occupied heap from about 3,106 to 1,779 MiB before the tree phase.
This supports allocation pressure, not a demonstrated permanent heap leak or
an attribution of all occupied memory to captured sections. Block entities,
NBT, natural world residency and builder-tool allocations need separate profiles.

### The exact reopen test does not reliably establish a stored apply path

The failing fixture has a distant 128x64x16 area: only 32 chunks. Existing
intent prewarm can load an initial nonresident window of up to 128 chunks.
The reopening logs show prewarm hits, followed by zero stored chunks and
RESIDENT fallbacks, while endpoint, ref, journal and freeze checks succeed.
Unlike the stored benchmark, `verifyReopened` does not wait for a stable,
gate-eligible unloaded fixture before each measured operation.

That combination explains why a successful exact Restore can fail the expected
path assertion. Subsequent reverse operations also inherit resident chunks from
the preceding Restore. A reopened world alone is not a cold-path precondition.
Current runs therefore leave a coverage gap; they do not demonstrate incorrect
restored block/entity state. Test real cold storage separately from intentional
UI prewarm, and retain all exact-state and path assertions in both workflows.

## Previous attempts and their outcomes

| Attempt | Git evidence | Recorded outcome |
| --- | --- | --- |
| Compact SectionBlob palettes and native Restore palettes | `56753ef13`, `807eeaba3`, `41bd021a6` | Retained. Current Save payloads are already compact; proposing this again as a missing optimization would be wrong. |
| Extra decode lookahead, July 27 | Recorded rejected candidate in `behavior-test-findings.md`; removed without an accepted commit | Natural median 50.111 to 48.423 s, only 3.37%; stored median 9.138 to 10.365 s, application +47.3%. Shared-executor contention. Rejected. |
| Bulk readiness, prepared slabs and native palettes, July 28 | Accepted changes and three-run results in the research log | Natural median 49.117 to 9.937 s; cold 230.303 to 10.488 s. Application 3.340 to 3.317 s. Major loading improvement, little application improvement. Stored mixed application regressed; no universal non-regression claim. |
| Copy captured origins into the Save pack | `65d7e5a6e`, reverted by `29bf7c206` | About 2% median Restore gain; cold Restore 9.290 to 10.595 s, warm 5.693 to 6.648 s, small Save median +4.6%. Rejected. |
| Shared cold read sessions and ordered two-way Merkle reads | `49804b8fa`, `b4668ef09`, `753f71f79` | Retained. Fresh-JVM diff median 2.882 to 2.482 s; total 11.231 to 10.265 s. These are Restore read improvements, not a decoded-node cache for Save's update loop. |
| Bounded origin batches and regional origin shards | `f4351e6ad`, `0040593ea`, `517f79b88` | Fixed the 68.182-s first-touch Save: 93 subsequent Save samples had median 926 ms, p95 1,807 ms, maximum 3,370 ms. Worst time improved 20.23 times. The benchmark stopped pre-draining durability outside its timer. |
| One final Restore storage barrier | `39302ac60` | Retained. Largest recorded direct Restore 13.415 to 7.718 s; sync 4.737 to 1.887 s. Still failed strict latency/tick/heap gates in that series. |
| Minimize Restore journal rewrites and wait within the deadline | `76be5b1b5`, `7cad188f9`, `520c29742`, `4b4fef98c` | Retained. Removed redundant phases and next-tick polling delays. Remaining journal/ref forces are still synchronous on the server thread, as the new recording proves. |
| Overlap index clear with ref publication | `f3057c3e`, reverted by `3d2ef0bdb` | Reverted about two minutes later. The inspected revert gives no reason or measurement; do not invent one. Current code retains ref publication before index clear. |
| Scoped force and pipelined force/reread | `74c64433f`, `7d0762a55`, `67ca809b0` | Retained. Avoid global force in the normal native path and overlap verification with force. The vanilla preceding write barrier remains broader than the affected regions. |
| Direct writes for clean resident chunks | `1bb9b0688`, reverted by `3b2bcf1be` | Only 156-162 of 871 chunks used the path; other chunks still needed vanilla Save. Duplicate I/O raised the measured median to 5.648 s. Rejected. |
| Transfer block-ticket prewarm across activation/slabs | `260a2b5cc`, `3ecd97360` | Retained. Median 5.853 to 4.109 s, about 1.42 times faster, not 3-4 times. Exact fresh-return deltas remained required. |
| Prewarm entity tickets | `cda1a694e`, reverted by `e71b5756a` | Median 4.156 s versus block-only 4.109 s; readiness unchanged and extra heap reached 770,590,192 bytes. Rejected. |
| Share a 30-ms allowance across dimensions | `c4cbffbd9` | Retained, with scheduler regression tests. Removed per-dimension budget multiplication; the independent 50-ms complete-tick gate remains red because blocking calls cannot honor that deadline. |

The historical 196-Save run showed first-to-last-quartile median growth of 25%,
principally in queue/publication time. It did not show increasing tree-building
time or linear retained-memory growth. A new long-history run with queue,
unique-object-read and catalog metrics is still needed to establish today's
history-length behavior.

## Recommended sequence

1. Move Restore journal create/advance, durable ref publication and cleanup
   into explicit background-owned steps. Keep actor/ref validation, world access,
   dimension ownership and final in-memory state installation on the server.
   Await each prerequisite before the next destructive step. Do not combine
   the phases or revive the reverted index/ref overlap without recovery proof.
   Add delayed/failing-I/O regressions and real process cuts at journal and
   ref/index boundaries. Expected benefit: fewer tick stalls; full-operation
   latency may stay similar or gain a tick while waiting.
2. Instrument working-index revisions, requested/durable boundaries, bytes,
   force count and vanilla queue wait. Experiment with demand-driven/coalesced
   index publication while retaining chunk/entity publication gates and exact
   Save-generation boundaries. Test repeated same-key edits during publication,
   retries and crashes. Simply skipping ambient changes is not an exactness fix.
3. Count unique ChunkTree IDs, repeated reads and pack-channel evictions in
   Save. Try bounded, operation-local reuse of already verified immutable nodes,
   physical read ordering for a bounded batch, and reading an entry's header
   and compressed bytes together instead of separate tiny reads. Preserve
   header, length and hash checks for every newly read object and bounded memory.
   Do not increase shared-pool
   parallelism blindly: a previous decode experiment lost on that tradeoff.
4. Capture the native palette directly into the canonical compact model,
   rather than expanding 4,096 references and rebuilding a TreeSet. Preserve
   canonical palette order, local indexing, block-entity NBT and identical IDs.
   Compare homogeneous and high-entropy sections plus block-entity-heavy data.
   Stream/spool the exact frozen capture only if retained-memory measurements
   show that it is necessary; the current fixture's index arrays alone do not.
5. Establish cold storage explicitly, beyond the current prewarm window, and
   repeat exact restore/crash/multi-client/idle coverage. Separate UI interaction
   time, enqueue time, server time and durable completion in future Save metrics.
   Do not use the current warm reopen run as proof of cold-path readiness.

These are proposed changes, not implemented fixes. Each should be a separate
reviewable slice with correctness and performance evidence. Do not change
storage identity, remove barriers, change ref CAS or relax a gate for speed.

## Rust assessment

Rust is a possible implementation language for bounded, Minecraft-free CPU
kernels: canonical binary encoding/decoding, palette-index/delta processing,
batch compression and hash validation. Passing primitive buffers once per
batch would keep the Java/native boundary small. Minecraft objects, mixins,
lighting, tickets, gameplay-thread ownership and the recovery workflow should
remain in the existing Java/Fabric adapters and domain orchestration.

Rust does not remove storage latency. In the reproduced 2.197-s tree phase,
observed reads alone account for about 2.055 s. Eliminating all remaining
computation would save at most about 6.5% of that phase in this recording.
The main new tick spikes are forced file writes, not codec CPU. No Rust speedup
or end-to-end estimate was measured, and no native dependency was added.

The existing LZ4 library already provides Java and JNI implementations.
Before writing another compressor, benchmark its supported backends against
the currently selected `safeInstance`, including malformed-input rejection,
cross-platform fallback and whole-operation timing. Keep canonical bytes and
object IDs identical; valid compressed bytes need not be identical.
[LZ4Factory documentation](https://lz4.github.io/lz4-java/1.8.0/docs/net/jpountz/lz4/LZ4Factory.html).

On the current Java 21 target, FFM is a preview API. JNI avoids imposing preview
launch options. JNI array access can copy or pin; critical array regions cannot
contain long-running work or blocking I/O. Use bounded transfers or owned direct
buffers, not a native call for every block.
[Java 21 FFM](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/foreign/package-summary.html),
[JNI array contracts](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/functions.html#getprimitivearraycritical-releaseprimitivearraycritical).

An optional Rust kernel needs explicit length/ownership checks, checked error
results, panic containment, platform builds and a Java fallback. A Rust panic
crossing a non-unwinding ABI can abort the whole process; `catch_unwind` does
not catch aborting panics. Reduced Java heap alone is not a memory improvement:
measure process resident memory and native allocations too.
[Rust FFI and unwinding](https://doc.rust-lang.org/nomicon/ffi.html#ffi-and-unwinding).

A prototype should stay read-only first: run both implementations on identical
bounded fixtures, compare canonical payloads/IDs/exact decoded state, and measure
native-call overhead, allocation, RSS, throughput and whole-operation latency.
Only a repeated material gain without correctness, cold-path or memory regressions
would justify installing it in the write/recovery path. Rewriting the entire mod
before those measurements has no evidence-based performance case.

## Current verification

The final phased GameTest compiled and completed exact small Save/Restore work;
its unchanged complete-tick gate failed at 126,965,500 ns. The large Save-only
GameTest passed all 31 Saves and its existing gate. All five focused
scheduler/session unit tests and GameTest compilation passed in
`diagnostic-checks.log`.
This investigation did not rerun the full unit, UI and process-crash matrices;
their earlier results are in the linked regression report. No production fix
is claimed and the outstanding release gates remain outstanding.

## Reproduce the diagnostic recording

Set JAVA_HOME to Java 21 and run Gradle tasks sequentially. Create an output
directory and a JFR configuration using the JDK's `jfr configure` command:

```powershell
New-Item -ItemType Directory -Force build/reports/lumi-investigation | Out-Null
& "$env:JAVA_HOME/bin/jfr.exe" configure --output build/reports/lumi-investigation/investigation.jfc gc=detailed allocation-profiling=high method-profiling=high locking-threshold=1ms file-threshold=1ms "jdk.ThreadPark#threshold=1ms" "+lumi.ServerTick#enabled=true" "+lumi.ServerTick#threshold=0ms" "+lumi.ServerTick#stackTrace=false"
```

Save this Gradle init script as
`build/reports/lumi-investigation/profile.init.gradle`. It records the actual
client/integrated-server JVM rather than the Gradle daemon:

```groovy
gradle.projectsEvaluated {
    rootProject.tasks.named('runClientGameTest') {
        def name = System.getProperty('lumi.investigation.name', 'profile')
        def output = new File(rootProject.projectDir, 'build/reports/lumi-investigation')
        jvmArgs "-XX:StartFlightRecording=name=lumi,settings=${new File(output, 'investigation.jfc').absolutePath},filename=${new File(output, name + '.jfr').absolutePath},dumponexit=true,maxsize=512m"
        jvmArgs "-Xlog:gc*,safepoint:file=${name}-gc.log:time,uptime,level,tags"
    }
}
```

Run the small workload with these quoted PowerShell arguments:

```powershell
./gradlew --init-script build/reports/lumi-investigation/profile.init.gradle "-Dlumi.investigation.name=small" "-Dlumi.benchmark.trace=true" "-Dlumi.benchmark.enabled=true" "-Dlumi.benchmark.baseSize=64" "-Dlumi.benchmark.changeSize=32" "-Dlumi.benchmark.layers=2" "-Dlumi.benchmark.commits=3" "-Dlumi.benchmark.restoreSamples=4" runClientGameTest --no-daemon
```

For the large Save-only workload, replace the size/count arguments with
`-Dlumi.benchmark.profile=player-scale-30`, `-Dlumi.benchmark.heap=4G` and
`-Dlumi.benchmark.saveOnly=true`, and use a distinct recording name.
Copy the flat GC log from `build/run/clientGameTest/` before starting another
native run. The directory is recreated by the harness.

Use `jfr summary recording.jfr` and
`jfr print --events lumi.ServerTick,jdk.FileForce,jdk.FileRead,jdk.ThreadPark,jdk.GCPhasePause recording.jfr`
to inspect the events. Correlate UTC event times with the JSONL phase boundaries.
Exclude startup, fixture generation and UI interaction when answering a question
about a particular server phase. Disabled tracing creates no tick events;
the production mod jar contains no GameTest instrumentation.
