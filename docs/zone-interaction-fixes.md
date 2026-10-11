# Zone and selection interaction fixes

## Plan and safety boundaries

1. Validate an Enter target before changing existing actor membership. Correlate
   the client wait with the request ID and handle terminal refusal without
   reopening the menu.
2. While the wooden sword is held, route Undo/Redo only to selection history.
   An empty local history produces feedback rather than a world mutation.
3. Give zone See Changes an explicit server-validated scope. Keep preview
   preparation bounded and preserve the normal workspace preview path.
4. Enable zone Save/Amend from fresh exact zone pending statistics. Explain
   clean or still-calculating state and keep the server's validation authoritative.
5. Assign coincident shell geometry to one visible zone before rendering,
   preferring the active zone, then the entered zone, then a stable zone order.
   Retain each zone's metadata and exclusive geometry.

Each slice includes regression tests and a checkpoint commit. The final checks
include the full unit suite, build, existing performance gates, and native
client UI workflows with negative interaction coverage. Restore publication,
mutation freezes, immutable commit trees, zone membership rules, and file
formats retain their existing contracts. No new dependencies are required.

## Implementation and verification

### Enter target validation

The service loads the requested zone before removing the actor from any other
zone. A deleted or unreadable target now rejects the request without changing
the existing focus or its revision. The existing one-active-zone rule is
retained for a successful Enter. Regression tests cover a deleted target,
an unreadable target, and successful switching while preserving other actors.

This is a validation-order fix, not a new multi-file transaction guarantee:
the existing per-file atomic zone persistence remains in use.

### Correlated Enter UI

`ZoneEntryController` owns one pending request, its target, and its context.
Matching terminal failures release the wait and expose the server message;
unrelated events do not. Successful navigation still waits for an authoritative
snapshot with the target active. Disconnect or a workspace/dimension change
also releases the wait. The screen only coordinates the controller and widgets.
Regression tests cover refusal and retry, unrelated events, delayed activation,
context loss, and local send failure.

### Sword Undo/Redo

The dispatcher distinguishes a held selection tool from an exhausted local
history. Holding a wooden sword in either hand routes Undo/Redo exclusively
to the scoped selection history and uses the existing no-undo/no-redo messages
when empty. Without the tool, the same chord keeps its world-action behavior.
Tests exhaust real selection history in both directions and verify that putting
the sword away restores world Undo without consuming the local history.

The native UI workflow also exercises exhausted sword history using registered
hotkeys while real pending world edits exist. A test-only read-only operation
holds the coordinator long enough to produce an actual server refusal for
Enter; the same screen must then allow a successful retry. The hold always
releases in cleanup and introduces no production coordinator behavior.
The workflow creates pending workspace edits before opening a clean zone, so
disabled zone actions are checked while the workspace itself remains dirty.
Both native UI scenarios passed with these checks on 2026-10-11.

Statistics failures keep Save disabled and expose the failure in its tooltip.
Reopening the page or Save form retries once; screen ticks do not retry failures.

### Entity-only zone changes

Exact block counts alone cannot gate a zone Save: armor stands and other
entity-only builder edits must remain savable. Pending statistics now also
carry a bounded set of zones containing builder-dirty entity columns. Column
attribution runs off-thread through the existing ZoneScope contract and does
not add entity blob capture or decoding to the statistics tick path. Block
counts retain their existing meaning; an entity-dirty column is a conservative
pending signal, not an exact entity-difference count. A regression test verifies
entity-only scope attribution, including the existing whole-column behavior.
The statistics wire response adds this set; client and server must use the
same Lumi build.

The client associates statistics with the zone revisions present when the
request began. Resizing, adding, or deleting zones triggers a replacement even
if the builder generation did not change. Save eligibility uses only fresh
zone statistics and preserves the entity-pending signal. Existing workspace
counter display can still retain its last number during recalculation.

### Zone Save form

The Save controller revalidates zone eligibility before sending either Save
or a confirmed Amend. The form updates availability when statistics arrive,
even if the pending-key count stays unchanged. Submission uses the latest
observed zone revision. A clean zone and pending calculation have distinct
messages; entity-only pending work remains savable. Amend retains its existing
requirement for a saved zone version. Workspace Save keeps its existing gate.
The zone dashboard uses the same availability model and refreshes its controls
from the current page snapshot. Changes outside the zone cannot enable its
Save, Amend, or See Changes controls.

### Zone preview protocol

The separate zone preview response carries request, dimension, workspace,
head/ref revision, zone revision, and pending revision. It caps coordinates at
512 and carries no geometry on failure. This keeps the shared history snapshot
workspace-scoped. Codec and scope tests check round trips, negative coordinates,
invalid revisions, and response bounds. The protocol uses the existing command
and overlay paths.

The server prepares zone-filtered preview coordinates on its background
executor. The zone predicate is applied before the 512-point cap. It checks
zone revision around preparation and history/pending context before sending;
failed or superseded requests cannot publish geometry. Preview requests remain
read-only and do not enter the mutation queue or freeze a dimension.

The client stores the selected zone preview separately from workspace pending
points. Only a successful result matching the current scope and pending revision
can display geometry; waiting or failed requests never substitute workspace
coordinates. History context changes
or leaving the zone clear the selection. Workspace See Changes explicitly
selects the workspace path; Action refresh retains a valid chosen zone.

### Overlapping shell ownership

Each background query claims coincident oriented unit faces before rectangle
merging. Active focus wins, followed by the zone containing the player and then
stable UUID order. Fully overlapping zones retain their metadata but share one
visible shell; each zone's exclusive faces and focused-mode shell remain intact.
Oppositely oriented boundaries of adjacent zones retain their separate sides.
The existing 64-zone, 65,536-visible-cell, and distance budgets still apply.
Claimed faces are bounded by visible cells. Queries reuse immutable occupied
sets instead of copying whole zone volumes and do no new render-frame work.
The first zone reuses its exposed-face set; the shared ownership index is
allocated only when another zone is processed. The allocation gate covers both
single-zone and overlapping-zone queries.
Regression tests cover identical and partial overlaps, priority, and focus.
Paired performance workloads compare preparation time and allocation for one
and 64 coincident zones of 1,024 cells, with raw samples and percentiles.

The final paired component measurements on 2026-10-11 used Java 21.0.6, one
reported processor, ten warmup pairs, and nine alternating measured pairs.

| Zones | Independent p50/p95 (ms) | Owned p50/p95 (ms) | Independent/owned allocated bytes |
| --- | --- | --- | --- |
| 1 | 1.426 / 1.462 | 1.397 / 1.407 | 571,712 / 530,672 |
| 64 | 67.896 / 69.171 | 60.730 / 62.177 | 36,571,136 / 19,801,272 |

The single-zone timing difference is too small to claim a meaningful speedup;
allocation fell by 7.2%. For 64 identical zones, allocation fell by 45.9%,
median preparation time fell by 10.6%, and the response contains six rectangles
instead of 384 while preserving all zone metadata. These are component
measurements, not an FPS or server-tick improvement claim. Raw samples are in
`build/reports/lumi-regressions/zone-shell-{independent,owned}-{1,64}.csv`.

## Final verification

All final checks passed on 2026-10-11:

- Production, unit-test, and client/server GameTest compilation.
- All 923 unit tests and nine performance cases, with no failures or errors.
- The mod JAR build; server GameTests ran separately and all 35 required tests
  passed, including both redstone recovery matrices and WorldEdit/Axiom cases.
- Both native UI scenarios, including exhausted sword history, refused Enter
  and retry, and a clean zone while pending workspace changes exist elsewhere.
- The first-minute native client gate: real block/entity packets, Save,
  entity Undo/Redo, connected-client relighting, cross-chunk entity Restore,
  durability, runtime health, and exact checks after reopening the same world.

Verification ran sequentially with one Gradle worker and one reported JVM
processor. Heap limits were 768 MiB for Gradle, 512 MiB for JUnit, and 1 GiB
for native GameTests. JDK 21 was selected explicitly and Gradle toolchain
auto-detection was disabled. These are local verification limits, not changes
to Lumi's production settings or latency guarantees.

Reproduce the checks from a JDK 21 checkout:

```powershell
.\gradlew.bat --max-workers=1 test regressionPerformanceTest build --no-daemon
.\gradlew.bat --max-workers=1 "-Dlumi.gametest.suite=ui" runClientGameTest --no-daemon
.\gradlew.bat --max-workers=1 "-Dlumi.gametest.firstMinuteOnly=true" runClientGameTest --no-daemon
```

Final logs are under `build/reports/lumi-ui-audit/`: the
`final-unit-performance-build`, `native-ui-resume-v3`, `native-server-final`,
and `native-smoke-final` logs. Native world-state and operation reports are
under `build/reports/lumi-behavior/`.

The full process-crash matrix, repeated vanilla/Lumi idle A/B runs,
multi-client conflict/disconnect matrix, and large native history benchmarks
were not repeated for this interaction change. This report does not establish
complete release readiness or a universal Save/Restore speedup.
