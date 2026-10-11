# Reliability regression checks

Repository maintenance uses a shared coordinator for each normalized dimension
repository path. A publication lease covers reuse of immutable objects until
their durable origin or commit is written. Starting a publication permanently
invalidates any earlier GC snapshot. GC does not start a reachability pass while
a publication is active.

The maintenance monitor covers only one loose-file deletion or one pack/index
deletion pair. Reachability scanning, compression, publication and compaction do
not hold it. Ordinary world capture and server ticks do not acquire a publication
lease; the background data-persistence path owns that lease. Ref, commit,
tombstone and origin metadata publication also invalidates a pending sweep.

`RepositoryMaintenanceTest` checks cross-instance identity, dimension isolation,
nested publication lifetimes, stale snapshot rejection and exclusion between
pack/index removal and a new publication. Storage format, object identity,
retention cutoff and Restore durability barriers are unchanged.

Save holds the lease through object reuse, durable commit and ref publication. Origin
persistence holds it through both the object batch and origin-shard publication.
A superseded GC stops deleting and reports any removals already completed; a
later scheduled pass recomputes reachability. The 24-hour retention cutoff still
applies. Compaction retains its existing publish-before-delete protocol.
Merge preparation, import, block-only Restore composition and Initial history
creation hold the same lease until their durable commit is available. They keep
the original object hashes, parent links, validation and publication order.

## Failed origin capture

A capture exception removes only the unfinished origin reservation and is
rethrown. A successful retry still captures the pre-mutation origin and keeps
vanilla publication blocked until both origin and working-index writes are
durable. Tests cover a section I/O exception and a rejected entity capture.

## Preview completion

Unsupported integrated capture, synchronous worker rejection, asynchronous build
failure and queue eviction all end the commit's loading state. Eviction also
cancels the pending build. Dedicated-server previews retain the existing missing
preview placeholder; no world-state transport or rendering behavior is added.
`IsometricPreviewCoordinatorTest` exercises those paths with controlled builds.

## Idle automatic-version polling

The runtime reads a volatile immutable settings snapshot for its automatic-version
check. Settings publication and idempotent workspace switches refresh that
snapshot. Startup reads current metadata, including any completed recovery,
before ticks begin. Authority, scope and expected-ref validation still use the
existing fresh repository reads. Live external edits to workspace files are not
a settings-publication API.

## Shared server work budget

The server session supplies one 30-ms deadline to all loaded dimensions and
rotates which dimension starts each tick. Mutation work and Restore intent
prewarm share that deadline; per-dimension operation limits still apply. All
dimensions retain their housekeeping and terminal processing when the work
budget is exhausted. Freeze ownership, queued activation validation and durable
release conditions remain per dimension. Controlled-clock tests cover the
aggregate allowance, rotation and failure isolation. Real complete server ticks
remain subject to the existing 50-ms GameTest gate, including vanilla work.

## Typed danger confirmations

Zone deletion shows the exact zone name as its input hint. Merge keeps the two
commit previews and requires the exact displayed source-branch name before its
button or Enter action can submit. The responsive panel reserves a separate
input row above the footer. The client GameTest driver verifies that Merge is
disabled before typing and enabled after an exact match. The UI workflow then
changes a common stone block to emerald on the source and gold on the destination,
submits Merge through the typed confirmation, and checks that emerald wins the
conflict while the unchanged deepslate block survives.
This fixture uses vanilla tick freeze during its Merge phase so ambient movement
cannot dirty the workspace while the confirmation is open; it resumes simulation
in `finally`. Lumi's operation processing and dimension freeze still run. The
production clean-workspace Merge requirement is unchanged.

## Opt-in performance checks

Run `gradlew regressionPerformanceTest --no-daemon` in a quiet environment. This
task always measures again; ordinary `test` excludes the `performance` tag. Raw
samples, p50/p95 batch times, calling-thread allocation counts and JFR recordings
are written to `build/reports/lumi-regressions/`. Idle polling compares 1,000 fresh
metadata reads with 1,000 cached polls in nine paired samples after warmup. Its
JFR control must observe actual repository reads; cached polling must perform
none and have a lower median. Durable first-touch batches use 32 and 256 air
sections, one warmup and seven measured fresh repositories. They include new
section-payload construction, mutation registration, object publication, origin
registration and working-index durability, then reread
every origin and check exact payloads and chunk publication eligibility.

Component timings do not replace real Minecraft Save/Restore, full server-tick,
idle A/B, multi-client or crash measurements. Allocation counts cover the measured
thread, not retained world heap. No absolute component-time gate is inferred from
one machine. The existing GameTest performance gates remain unchanged.

