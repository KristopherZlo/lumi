# Lumi

<p align="center">
  <img alt="Lumi banner" src="lumi-banner.png" />
</p>

<p align="center">
  <strong>Save the build. Try the idea. Undo the mistake.</strong>
</p>

<p align="center">
  <img alt="Minecraft 1.21.11" src="https://img.shields.io/badge/Minecraft-1.21.11-5E7C16?style=for-the-badge" />
  <img alt="Fabric" src="https://img.shields.io/badge/Loader-Fabric-DBD0B4?style=for-the-badge" />
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-1F6FEB?style=for-the-badge" />
  <img alt="GPL 3.0" src="https://img.shields.io/badge/License-GPL%203.0-2EA043?style=for-the-badge" />
</p>

Lumi is project history for Minecraft builders.

It runs inside your world: save named moments of a build, compare what changed, branch risky ideas, restore older states, and recover interrupted work without copying the whole world folder every time.

Status: alpha. Keep normal world backups.

## Product North Star

Lumi is an invisible, reliable versioning safety net for Minecraft builders. It should let a builder save a good moment, try another idea, compare the result, and return safely without understanding Git or fearing world loss.

Lumi records the minimum state required for exact recovery, not every raw block event or game tick. Save must match the visible world; restore must leave blocks, block entities, and entities exactly at the requested state; a crash must not destroy previously valid history; and removing Lumi must leave a normal usable Minecraft world.

When Lumi has no useful work to perform, idle play and chunk loading should be statistically indistinguishable from vanilla. Heavy preparation stays off the server tick, long work is incremental and observable, and memory is bounded by the current work batch rather than the whole operation. Reliability is never traded for speed: remove unnecessary work instead of weakening guarantees.

The core scenario is deliberately narrow: **save a good build, try a different idea, compare it, and return safely**. Lumi is not a universal Git implementation, a multiplayer collaboration platform, or a permanent archive of every tick. Features and complexity that do not strengthen this scenario do not belong in the product.

Release readiness requires repeated vanilla/Lumi idle measurements, an exact save/compare/restore workflow over 100,000 changes with bounded tick time and memory, injected crash coverage for every persistence phase, real integrated coverage for supported builder tools, enforcement of one operation per world, and a load-regression gate that blocks the release.

## For Players

### Download

Download alpha builds from [GitHub Releases](https://github.com/KristopherZlo/lumi/releases). Use a release that lists your Minecraft version.

Current target:

- Minecraft `1.21.11`
- Fabric Loader `0.19.2` or newer compatible Fabric loader
- Java `21`
- Fabric API `0.141.3+1.21.11`

Lumi also declares owo-lib and cloth-config as Fabric dependencies. Install the dependency versions requested by the release if your launcher does not resolve them automatically.

### Install

1. Install Fabric for Minecraft `1.21.11`.
2. Put the Lumi jar and required dependency jars in your `mods` folder.
3. Start Minecraft and open a singleplayer world.
4. Keep a normal world backup before relying on an alpha build.

For dedicated servers, install Lumi on both the server and every client that uses Lumi screens or overlays. Dedicated server actions require operator-level permission; `/lumi save <message>` saves tracked work from the server side. Survival-mode access is disabled by default in Lumi settings; when it is enabled, the player still needs operator-level permission.

### What Lumi Does

- Tracks a whole dimension or a selected build area as a project.
- Saves named build versions with change counts and restore data.
- Compares saved versions, branches, and current unsaved work.
- Provides a Compare workspace page for picking two saves from branch histories before showing the overlay.
- Shows changed blocks with an in-world overlay.
- Renders large pending-change overlays as merged section meshes while preserving per-block square outlines.
- Restores a whole save, a selected area, or everything outside a selected area.
- Lets you branch risky ideas and merge local branches back into the active branch.
- Shows each branch history from its current head through reachable parent saves and restorable forward descendants.
- Imports and exports project history packages.
- Keeps recovery drafts for interrupted work.
- Lets you mark active work zones, save a zone separately, and keep unrelated pending work.
- Lets work zones grow from causal tree growth and supported external-tool edits, hide boundary boxes, delete zone metadata without deleting commits, and optionally show zone commits in global history with zone color markers.
- Initializes the current world workspace after you enter a world, then captures normal Minecraft edits, player-caused mob and explosion fallout, plus supported WorldEdit, FAWE, and Axiom mutation paths, including player-owned Axiom infinite reach, fast place, and bulldozer block actions, on a best-effort basis.

### Quick Start

1. Enter a world and let Lumi initialize the current workspace.
2. Follow the quick tour: make 5 block edits, hold [ALT] until the pending-work highlight appears, save with [ALT]+[S], then hold [ALT]+[I] to finish in the hotkey guide and read the wooden-sword selection tip.
3. Press [U] to open Build History and inspect the created save card.
4. Use the Compare tab to pick two saves for the overlay, or use save cards for restore, branches, and older checkpoints when an idea goes wrong.

### Default Controls

| Key | Action |
| --- | --- |
| [U] | Open Build History, or Zones when an active zone is selected |
| [ALT]+[S] by default | Open Save build, or Save zone when an active zone is selected |
| [ALT]+[1] ... [0] by default | Switch to the branch bound to that key when there is no unsaved work; `main` defaults to [1], then branches use the first free key from [1]...[0] |
| [ALT] by default | Hold to preview pending work and enable action-key modifiers |
| [ALT]+[Z] by default | Undo the current wooden-sword selection edit, otherwise undo the latest tracked world action |
| [ALT]+[Y] by default | Redo the current wooden-sword selection edit, otherwise redo the latest undone world action |
| [R] | Quick rollback of unsaved work |
| [H] | Toggle compare overlay |
| [ALT]+[I] by default | Show Lumi hotkeys |
| Wooden sword | Select partial-restore regions and active-zone cells; the action key with mouse controls resizes, switches mode, or clears; [ALT]+[Z]/[Y] undo/redo selection by default, [CTRL] adds/removes active-zone cells |

Wooden sword hints appear under the crosshair at the GUI Scale 2 visual size; only [LMB], [MMB], and [RMB] use mouse icons, while keyboard inputs render as [KEY] text.

All keybinds are remappable in Minecraft controls.
Lumi keybinds are ignored while the Minecraft pause menu is open.
Save or discard current unsaved work before switching branches. Branch switching enters the operation queue before draft reconciliation, so the UI never waits on the integrated server thread; preparation rejects a non-empty draft without changing the active branch and keeps it classified as current work instead of interrupted recovery work.

### Reliability and performance

Save, restore, branch, zone, recovery, and quick-rollback operations are measured against a two-second singleplayer target. Heavy decoding and file preparation stay off the server tick; history apply may synchronously load at most one missing chunk per tick so a small restore does not wait through the old 20-tick preload fallback. Runtime sync budgets exclude the two explicit bounded-project fixture initialization phases while still reporting their observed wall time; operation-duration, scope, idle, and all subsequent tick budgets remain enforced. Large or storage-bound operations can exceed the target and are reported as performance failures instead of silently weakening exactness.

Restore, quick rollback, undo, and redo read the final live world state, perform at most one repair pass, then read it again and fail if any requested state still differs. Undo respawns living entities from the first dying tick onward so the client returns them to a normal living state. Current causal fallout becomes the next undo target by action order rather than wall-clock time, so a completed TNT chain is restored before later placements that the chain already consumed. Undo/redo waits and retries while causal block updates are still settling, then selects the stable action instead of failing from a changing history revision. Authorized causal fallout can reopen capture after its direct draft becomes empty and extend tracking beyond the old active region, but its secondary source cannot create a project and an already-undone action cannot reopen history. Replayed live actions preserve causal ownership for restored primed TNT, so fallout that continues after redo remains part of the same undoable action. Once an undo or redo has applied its exact draft adjustment, Lumi rebuilds the transient stabilization session from that adjusted draft so a stale pre-apply envelope cannot reintroduce the action. Entity movement capture treats `snapTo` as one outer transition so nested rotation callbacks cannot replace the true pre-move position, and replay explicitly restores the saved pose after applying entity NBT. The client shortcut starts its server-bound selection asynchronously so it cannot deadlock the render thread against the integrated server. Live undo/redo is an in-memory convenience layer; a full restore or branch switch clears its project-local stack, while durable save, restore, branch, zone, and recovery history remains the source of truth across restarts.

Each project exposes a history protection state: `Protected` while its guarantees are intact, `Saving` or `Restoring` during an active operation, and durable `Degraded` after a reliability failure. The degraded marker records the first-class reason instead of allowing a baseline, dirty-ledger, payload, reconciliation, or restore-verification failure to look like a successful history operation; expected request-validation failures do not degrade history.

### Privacy and Diagnostics

Lumi has diagnostic telemetry for crashes, failed operations, rejected actions, and severe performance problems. It is technical-only and can be turned off in Lumi settings.

Telemetry does not send raw logs, screen views, clicks, world names, project names, coordinates, seeds, exception messages, raw file paths, raw NBT, or block/entity payloads.

The default endpoint is the Lumi project telemetry receiver. The receiver stores allowlisted diagnostic fields only, keeps raw events for 90 days, and exposes diagnostics through a private authenticated dashboard.

## For Developers

### Build and Test

Build the mod:

```powershell
.\gradlew.bat build --no-daemon
```

Run the development client:

```powershell
.\gradlew.bat runClient --no-daemon
```

Run unit tests:

```powershell
.\gradlew.bat test --no-daemon
```

Run telemetry backend tests:

```powershell
cd telemetry-backend
npm test
```

### Telemetry Backend

The self-hosted telemetry receiver lives in `telemetry-backend`. It is a small Node/Postgres ingest service used with Grafana OSS for investigation:

- public ingest at `POST /v1/events/batch`
- strict JSON schema, event type allowlists, key allowlists, body size limits, and per-client rate limiting
- no IP storage
- private Grafana access with sign-up and anonymous access disabled
- a read-only Grafana database user
- default dashboard panels for installs, failures, event types, versions, and sanitized recent events
- 90-day retention for stored raw events

Keep deployment-specific hosts, credentials, hashes, and connection strings out of public docs.

Run the local test-client profile:

```powershell
.\scripts\run-test-client.ps1
```

The test-client game directory is `run/test-client`. Put ad-hoc client mods in `run/test-client/mods`; `installTestClientMods` copies the pinned test mods there without clearing your manual jars or the Fabric remap cache. The local `runTestClient` profile also leaves `run/test-client/options.txt` alone, so Minecraft settings such as sound are not reset on every launch. Run `.\gradlew.bat purgeTestClientRuntimeRemapCache` only when the test-client remap cache is stale.

Run the server GameTest smoke suite:

```powershell
.\gradlew.bat runGameTest --no-daemon
```

This task clears `build/run/gameTest/world` before launch so server GameTests do not reuse stale local projects.

Run the current client GameTests:

```powershell
.\gradlew.bat runClientGameTest --no-daemon
```

This suite exercises the core builder save/compare/restore/branch journey, current screen action wiring, and the pending/compare overlays, including the held-Alt view. Repeat only the core stability gate with `.\gradlew.bat runClientGameTest -PlumiClientGameTestSuite=core --no-daemon`; `screens` and `overlays` are the other valid focused values. Server GameTests retain focused regressions for falling-block entity capture and random crop ticks. Legacy mode-driven journeys that called domain services directly are intentionally excluded because they did not exercise user-accessible workflows.

Compare vanilla and Lumi world startup against the same normal-world seed:

```powershell
.\scripts\compare-idle-startup-load.ps1 -Runs 3
```

The gate alternates three baseline/Lumi process pairs to reduce launch-order bias. Both client GameTests verify the integrated server seed, sample 100 steady idle ticks with server-thread CPU time, then teleport the player to three distant locations and wait for each new chunk area to render. The blocking comparison uses paired median wall, idle CPU/wall, and teleport deltas, while still rejecting failed runs, long-tick regressions, and render failures. A known Fabric Client GameTest startup stall is retried once only when the integrated server never started; a started test is never retried or hidden.

Axiom custom payloads open one version-independent `AXIOM` source scope by namespace. Ordinary world generation bypasses direct-section capture, while direct section capture stays enabled under an `AXIOM` scope, an active world-operation barrier, or explicitly enabled external-tool stack detection. Loaded section ownership is stored directly on each section, so bulk capture remains an optimization rather than the only record of an edit. Each stable chunk section array is registered once; individual section access still validates replacements without rescanning the array on every `getSections()` call.

Run the alpha gate:

```powershell
.\scripts\run-alpha-release-check.ps1
```

The wrapper stops and returns a non-zero exit code on the first failing child check.

The automated behavior coverage contract is tracked in [SMOKE_BEHAVIOR.md](SMOKE_BEHAVIOR.md). Developer workflow details are in [docs/development.md](docs/development.md).

### Stack

- Minecraft `1.21.11`
- Fabric Loader `0.19.2`
- Fabric API `0.141.3+1.21.11`
- Java `21`
- owo-lib `0.13.0+1.21.11`
- cloth-config `21.11.153`
- lz4-java `1.8.1`
- JUnit 5 and Fabric GameTest

### Code Map

Start with [modules.md](modules.md) before opening broad source trees.

| Area | Responsibility |
| --- | --- |
| `src/main/java/io/github/luma/domain/model` | Value objects, persisted records, summaries, runtime state |
| `src/main/java/io/github/luma/domain/service` | Product workflows and business rules |
| `src/main/java/io/github/luma/minecraft/capture` | Capture hooks, causal context, working drafts |
| `src/main/java/io/github/luma/minecraft/world` | Prepared restore application and tick-time mutation |
| `src/main/java/io/github/luma/storage` | Paths, JSON manifests, binary payloads, archives, atomic writes |
| `src/main/java/io/github/luma/integration` | Optional builder-tool adapters |
| `src/client/java/io/github/luma` | UI controllers, screens, previews, overlays |
| `src/main/java/io/github/luma/mixin` | Thin server-side Minecraft hook entrypoints |
| `src/client/java/io/github/luma/mixin` | Thin client-side Minecraft hook entrypoints |
| `src/test`, `src/gametest` | Unit tests, runtime tests, GameTests |

Layer rules:

- Domain services own product rules.
- Minecraft APIs stay in the Minecraft adapter layer.
- Repositories own storage layout and serialization.
- UI controllers coordinate services; they do not own domain logic.

### Storage Model

World-level Lumi data lives under:

```text
<world>/lumi/
```

Project data lives under:

```text
<world>/lumi/projects/<project>.mbp/
```

Important records:

- `world-origin.json`: shared world origin and restore-safety baseline
- `project.json`: project metadata and settings
- `variants.json`: branch heads and branch switch binds
- `history-tombstones.json`: soft-deleted saves and branches
- `work-zones.json`: named work-zone metadata and active selections
- `player-spawns.json`: per-version player respawn points restored after full restore; full project exports include it, variant exports omit it until lineage-filtered export exists
- `versions/*.json`: saved version manifests
- `patches/*.meta.json`: patch metadata, indexes, and stats
- `patches/*.bin.lz4`: chunk-addressable block/entity deltas
- `snapshots/*.bin.lz4`: checkpoint anchors
- `entity-checkpoints/*.bin.lz4`: per-save entity snapshots used as the authoritative restore target
- `preview-requests/*.json`: queued client preview work
- `recovery/draft.bin.lz4`: compacted recovery draft
- `recovery/draft.wal.lz4`: append-only recovery draft log
- `recovery/expected-draft.marker`: clean pending-work marker
- `recovery/operation-draft.bin.lz4`: isolated in-progress save/amend draft
- `recovery/dirty-scope.bin`: compact durable block-section and entity-chunk safety ledger
- `recovery/history-protection.json`: durable degraded-history reason
- `payloads/baseline-chunks/`: durable first-touch baseline chunks
- `cache/`: disposable UI and diagnostic cache

Current writers create patch payload schema v9 and snapshot payload schema v8. Current readers intentionally support patch payload schema v9 and snapshot payload schemas v7-v8. See [docs/storage-format.md](docs/storage-format.md) for exact layout and compatibility rules.
Project settings include `showHiddenCommits` for showing live-zone commits in global history, `survivalModeEnabled` for the opt-in Survival-mode access gate, and `autoCheckpointLargeChangeThreshold`, which defaults to `131072` changed blocks for command-triggered auto checkpoints. Deleted-zone commits return to global history because deleting a zone only removes `work-zones.json` metadata. Restore safety checkpoints stay hidden from normal history and are exposed through the restore return-point flow instead.

### Runtime Model

Capture writes working drafts while the player builds. Startup metadata bootstrap waits at least 10 seconds after join and for a five-second quiet chunk-loading window, then uses the single world-operation queue so save and migration cannot overlap; active chunk loading postpones it instead of forcing a deadline. First-touch baseline chunk writes use one low-priority writer by default, bounded to eight through `lumi.capture.baselineThreads`; the separate priority lane lets a save or rollback promote its own queued baseline without competing with the maintenance backlog. Automatic migration leaves unreferenced baseline files untouched and trims empty polluted checkpoint frames to their real patch lineage. Settled-state reconciliation handles at most four dirty chunks per normal tick, captures each section palette once, and omits unused entity snapshots; explicit final drains still reconcile all pending chunks. Save turns a draft into patch metadata plus compressed chunk frames. Snapshot saves copy the planned chunk set in one server-thread handoff instead of waiting for a separate server tick per chunk, and a full snapshot supplies its entity checkpoint without capturing the same chunks twice; compression and persistence remain background work. Restore prepares file I/O, LZ4, block-state decode, and planning off the server tick thread, then applies prepared batches on the server thread with tick budgets. Direct restore to a metadata-only world root keeps sparse patch replay usable for legacy test histories with missing first-touch baseline chunks, logs the skipped exact-root replay positions, and only uses baseline-backed positions for the extra exact-root reconciliation.

Hard rules:

- One world operation runs per world at a time.
- Operation labels map once to a typed workload kind that owns the apply budget, final verification, and mutation-barrier policy.
- Long operations publish progress and terminal success/failure UI feedback.
- JSON parsing, LZ4 decompression, and block-state decoding stay off the tick-thread apply path.
- Replaced or cleared compare requests interrupt their worker task; long diff passes stop cooperatively instead of consuming CPU after the UI no longer needs them.
- Cancelled compare/preview mesh preparation interrupts the submitted worker, and stale pending overlay meshes are closed before they can retain CPU-side or GPU-side buffers.
- While the Lumi action key is held, pending-change previews read the current draft without a quiet-period delay and refresh at client-tick cadence; passive actionless world ticks are excluded from the safety ledger, and a dirty scope is published only after its baseline writes finish.
- Bounds arithmetic rejects reversed ranges and cannot silently overflow; a single work-zone selection edit is capped at 65,536 section cells to prevent an accidental unbounded client allocation.
- Recovery drafts are serialized once per flush; zone save reuses that durable draft instead of rewriting the full draft before isolating its operation draft, and the operational journal retains only its newest 512 entries. Recovery base/WAL reads, appends, compaction, and deletion share one project-path lock, so an async append cannot be mistaken for a truncated crash tail. Project dirty block sections and entity chunks are coalesced in the compact `recovery/dirty-scope.bin` sidecar instead of storing per-tick payloads.
- Recovery restore/discard and destructive storage cleanup reject requests while any world operation is still registered, including the short terminal-snapshot cleanup window, so they cannot consume drafts or delete payloads still owned by save/restore.
- Project dirty-scope reads on the server thread use the coalesced in-memory ledger and never wait behind its low-priority durable flush; save and restore workers still drain persistence before relying on the sidecar.
- If interruption lands after a branch head is published but before operation-draft deletion or dirty-scope cleanup, startup recognizes the branch-head advance, deletes the stale save/amend operation draft, and retains the dirty ledger for reconciliation against the published head. A pending partial-restore completion keeps ownership of its operation draft until restore metadata recovery finishes. The next reconciliation consumes already-saved no-op sections without publishing an empty version while preserving later live differences.
- Repeated unchanged-draft checks reuse a cached content fingerprint until the tracked block or entity set changes.
- Empty entity-causal lookups reuse one inactive frame, and normal mob ticks only expire a matched context instead of sweeping the whole registry.
- Snapshot sections are encoded once per chunk and reused for content deduplication, fingerprints, and the compressed snapshot frame; existing content blobs skip redundant LZ4 compression.
- Patch payload writers encode, compress, and persist one chunk frame at a time, so save progress advances per chunk and encoding memory does not retain every compressed frame in the operation.
- Saving a version extends a fresh history index in memory instead of reopening and parsing every older manifest; stale indices still fall back to a full safe rebuild.
- Pending, work-zone, and selection overlays reuse the capture catalog instead of reparsing every project on client tick or render hot paths, and hot drafts are rejected before final reconciliation or deep snapshot copying.
- Preview pixel cropping, PNG encoding, request cleanup, and version metadata updates run on the low-priority preview worker instead of the render/client thread.
- Compare overlays build geometry in the cancellable background worker above 2,048 changed blocks, reject stale worker results after show/refresh/clear transitions, and skip rebuilding when refreshed content is unchanged.
- World-apply no-op pruning reuses its first live-state scan instead of decoding and comparing every target block twice.
- Shutdown fully drains dirty-chunk stabilization, and restore verification retains original targets even when no-op pruning skips their initial write while scanning those targets incrementally within the current tick deadline. Mechanism reconciliation expands a complete 4,096-block section only for volatile callback-suppressed states; containers and ordinary signal sources keep a bounded changed-position plus six-neighbor scope.
- Snapshot and patch storage no longer inserts fixed sleeps between chunks; background thread priority and cooperative cancellation control contention without slowing completed work.
- Restore, recovery, merge, and rollback replay must not capture themselves as new user edits.
- Restore, recovery, merge, and quick rollback verify final target state before reporting success. A mismatch gets one repair pass followed by a fresh read-back; a remaining mismatch fails the operation. Saved history and recovery are the return path.
- Full restore unions the patch lineage, recovery draft, and project dirty scope. Dirty sections are reconstructed from the requested target state and the ledger is cleared only after apply verification succeeds.
- Quick rollback isolates the same dirty scope under the world-operation barrier; an area rollback consumes fully covered sections and leaves partial/outside sections pending.
- Zone and partial restore reconcile only ledger sections intersecting their hard spatial scope, never apply outside it, and mark their verified result as new pending safety history against the unchanged branch head.
- An active work zone treats a dirty block section inside one of its 16x16x16 cells as saveable even when no player-attributed draft exists; dirty sections outside the zone remain pending. Entity chunks remain project-level because a chunk column has no zone-cell Y boundary.
- Save/amend and history-apply operations hold a world mutation barrier from queueing through completion. Vanilla simulation is frozen while player movement, break/use/interact, containers, creative slots, world-editing commands, direct block/entity writes, and tool-driven section writes are rejected; Lumi's capture-suppressed apply remains allowed. A world that was already tick-frozen stays frozen when the operation releases its lease.
- Existing workspaces mark every confirmed persistent block mutation, including actionless ambient fallout, in the project dirty-section sidecar after capturing any missing pre-mutation chunk baseline. Action attribution still controls player undo: live-state stabilization rereads only positions observed under that action instead of absorbing unrelated differences from the same section. Unattributed `SYSTEM` fallbacks are accepted only on the server thread for already ticking gameplay chunks; ambient fallout still cannot create a workspace, and internal Lumi apply plus chunk generation/loading/deserialization are rejected before old-state or baseline reads. Nested chunk writes reuse the level mutation boundary instead of repeating capture interception. Axiom buffer edits preflight baselines and bulk-mark unique sections instead of creating dirty payloads per block. Baseline persistence uses a bounded background pool plus one drain-priority lane, so a save or rollback can promote its own queued baseline without waiting behind unrelated project backlog.
- Save reconciles only selected dirty sections and entity chunks against the authoritative branch head, processing one 4,096-block section at a time so the reconciliation working set stays bounded. It merges the result into the ordinary patch v9 draft and publishes the manifest before rebasing or clearing the ledger. Work-zone saves consume their section cells while leaving the remaining scope pending.
- Every new patch, snapshot, and entity checkpoint is reopened and validated before its version manifest is published; patch metadata and indexes must round-trip exactly, and a missing, corrupt, or mismatched payload fails the save while the operation draft and dirty ledger remain recoverable.
- Player-caused mob and explosion block fallout, plus persistent placed-entity fallout, is captured in recovery drafts and saved project history.
- In integrated singleplayer, an otherwise unowned creeper explosion or lightning strike opens one non-player `world incident` action. Its bounded fire, scheduled-tick, block, and supported entity fallout share one action id for live undo, while later ambient spread remains protected by the dirty scope.
- Save preview screenshots frame the recorded block-change envelope, including hidden causal fallout, so TNT/fire/fluid-heavy saves can still get previews even when builder-facing counters hide those secondary changes.
- Axiom infinite-reach set-block packets preserve their requested block transitions through synchronous callbacks before settled reconciliation. Baseline captures apply old-state overrides directly to copied palette containers, avoiding optional optimization-mod bookkeeping on detached chunk-section copies.
- If a placed block is synchronously consumed by vanilla callbacks before `Level#setBlock` returns, Lumi records the final settled state instead of persisting the short-lived intermediate block.
- Client modal overlays consume pointer input so underlying workspace actions cannot fire while a modal is open.
- Saved commits keep entity checkpoints for durable entities present at save time. Primed TNT is omitted from new checkpoints and ignored in authoritative replacement from older checkpoints, so a saved fuse cannot create an explosion during restore. Whole-dimension saves include chunks with currently loaded durable non-player entities, and player-spawned non-player entities make durable pending work so entity-only saves are possible. Pending summaries and save gating count entity-only drafts as pending work. Full restore treats target entity-checkpoint chunks as authoritative even when no block batch touched that chunk, while quick rollback limits authoritative replacement to the chunks changed by that rollback. Partial and zone restore invert live entity fallout inside the selected scope, so current item drops are removed instead of duplicated. Restore can skip selected entity types for a single run without changing the saved commit.
- Restore confirmation entity summaries count only entities inside the resolved restore scope for zones and selected/outside partial restores.

### Diagnostics

Useful JVM flags:

```text
-Dlumi.debug=true
-Dlumi.loadLog=true
-Dlumi.clientLoadLog=true
-Dlumi.lightLog=true
-Dlumi.blockApplyLog=true
-Dlumi.partialRestoreLog=true
-Dlumi.fluidUndoLog=true
-Dlumi.testerDiagnostics=true
-Dlumi.ui.targetGuiScale=2
-Dlumi.ui.iconButtonWidth=26
-Dlumi.ui.iconButtonHeight=18
-Dlumi.ui.iconDrawSize=12
```

The `lumi.ui.*` flags are dev-only tuning knobs for Lumi's in-game menus.
Without an override, Lumi selects target GUI scales `2`, `3`, `4`, and `6` for
HD, Full HD, QHD, and 4K framebuffers so those displays share a 640x360 virtual
layout. At target scale `3`, 24x24 pixel-art icons stay at their native 24x24
framebuffer size instead of being resampled to 36x36. `targetGuiScale=2`
forces a specific target for diagnostics, including Lumi button tooltips and
the Special Thanks player showcase, while the icon button flags tune the
button box and texture draw size.
Special Thanks entries may use `skinName` for Minecraft profile skin and cape
lookup, or an HTTPS `skinUrl` for a direct skin PNG while still using the
profile cape when a name is available. `skinAsset` may name a bundled texture
used when the direct download fails. Skin downloads start when the Special
Thanks page opens, are cached under `lumi-special-thanks/`, and are registered
on the Minecraft client thread.

`-Dlumi.loadLog=true` writes `logs/lumi-load.log`. TNT restore diagnostics use
`tnt-context/*` and `tnt-replay/*` events to show freeze decisions, deferred
explosive context, TNT activation callbacks, frozen primed TNT ticks, and TNT
explosion context.

`-Dlumi.fluidUndoLog=true` writes `logs/lumi-fluid-undo.log` with fluid ticks,
replay suppression, fluid-tail guards, and settled capture diagnostics. It is
separate from `lumi.loadLog`, so it can be enabled alone while the broader logs
stay off.

Runtime logs are written under the normal Minecraft `logs/` directory or the world-local `lumi/test-logs/` directory for focused test profiles.

## License

GPL-3.0-only. See [LICENSE](LICENSE).
