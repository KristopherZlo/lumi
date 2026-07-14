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
2. Follow the quick tour: make 5 block edits, preview pending work with [ALT], save with [ALT]+[S], then hold [ALT]+[I] to finish in the hotkey guide and read the wooden-sword selection tip.
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
Save or discard current unsaved work before switching branches. A rejected switch keeps that draft classified as current work instead of presenting it as interrupted recovery work.

### Reliability and performance

Save, restore, branch, zone, recovery, and quick-rollback operations are measured against a two-second singleplayer target. Heavy decoding and file preparation stay off the server tick; history apply may synchronously load at most one missing chunk per tick so a small restore does not wait through the old 20-tick preload fallback. Large or storage-bound operations can exceed the target and are reported as performance failures instead of silently weakening exactness.

Restore, quick rollback, undo, and redo read the final live world state, perform at most one repair pass, then read it again and fail if any requested state still differs. Undo respawns living entities from the first dying tick onward so the client returns them to a normal living state. Current causal fallout becomes the next undo target by action order rather than wall-clock time, so a completed TNT chain is restored before later placements that the chain already consumed. Authorized causal fallout can reopen capture after its direct draft becomes empty and extend tracking beyond the old active region, but its secondary source cannot create a project and an already-undone action cannot reopen history. Replayed live actions preserve causal ownership for restored primed TNT, so fallout that continues after redo remains part of the same undoable action. Live undo/redo is an in-memory convenience layer; a full restore or branch switch clears its project-local stack, while durable save, restore, branch, zone, and recovery history remains the source of truth across restarts.

Each project exposes a history protection state: `Protected` while its guarantees are intact, `Saving` or `Restoring` during an active operation, and durable `Degraded` after a reliability failure. The degraded marker records the first-class reason instead of allowing a baseline, dirty-ledger, payload, reconciliation, or restore-verification failure to look like a successful history operation.

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

Run the integrated singleplayer player-flow regression suite:

```powershell
.\gradlew.bat runClientGameTest -Dlumi.singleplayerTest.mode=player-flow --no-daemon
```

The player-flow TNT checks begin from an empty post-save draft, place and ignite one TNT through normal player APIs, let its fallout settle, and require one live undo to restore every witness block. A save-during-fuse regression publishes while powered TNT is primed, then requires later blast damage to retain its original action id and rebase onto the new HEAD. Fuse-time undo, powered undo, and ten-block chain scenarios use live undo, which invalidates their deferred causal contexts and prevents later scheduled fallout from reopening an already-undone action. The same flow strikes deterministic fixtures with an unowned creeper and lightning, verifies one `world incident` action for each, and restores each fixture with one undo. Quick-rollback load checks are bounded by reconciled dirty sections because safety history can intentionally cover persistent changes that are absent from the player draft.

The same runtime flow creates fluid without a player context, verifies that it adds neither a player draft nor an undo action, rolls it back through the dirty scope, then repeats the mutation and verifies that a normal save publishes it.

Compare vanilla and Lumi world startup against the same normal-world seed:

```powershell
.\scripts\compare-idle-startup-load.ps1 -Runs 3
```

Both client GameTests verify the integrated server seed, then teleport the player to three distant locations and wait for each new chunk area to render. The comparison summary reports average and maximum teleport load time and render-wait ticks.

Axiom custom payloads open one version-independent `AXIOM` source scope by namespace. Direct section capture stays enabled under that scope, so bulk capture remains an optimization rather than the only record of an edit.

Run the alpha gate:

```powershell
.\scripts\run-alpha-release-check.ps1
```

The runtime smoke behavior contract is tracked in [SMOKE_BEHAVIOR.md](SMOKE_BEHAVIOR.md). Developer workflow details are in [docs/development.md](docs/development.md).

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

Capture writes working drafts while the player builds. Startup metadata bootstrap waits at least 10 seconds after join and for a five-second quiet chunk-loading window before running on a low-priority background thread; a 30-second deadline prevents recovery preparation from being postponed indefinitely. First-touch baseline chunk writes default to one low-priority writer to reduce client CPU and disk contention. Bootstrap also quarantines unreferenced whole-dimension baseline files and trims empty polluted checkpoint frames to their real patch lineage, keeping old regression artifacts out of reset and restore scope without deleting their baseline data. Settled-state reconciliation handles at most four dirty chunks per normal tick, captures each section palette once, and omits unused entity snapshots; explicit final drains still reconcile all pending chunks. Save turns a draft into patch metadata plus compressed chunk frames. Snapshot saves copy the planned chunk set in one server-thread handoff instead of waiting for a separate server tick per chunk, and a full snapshot supplies its entity checkpoint without capturing the same chunks twice; compression and persistence remain background work. Restore prepares file I/O, LZ4, block-state decode, and planning off the server tick thread, then applies prepared batches on the server thread with tick budgets. Direct restore to a metadata-only world root keeps sparse patch replay usable for legacy test histories with missing first-touch baseline chunks, logs the skipped exact-root replay positions, and only uses baseline-backed positions for the extra exact-root reconciliation.

Hard rules:

- One world operation runs per world at a time.
- Operation labels map once to a typed workload kind that owns the apply budget, final verification, and mutation-barrier policy.
- Long operations publish progress and terminal success/failure UI feedback.
- JSON parsing, LZ4 decompression, and block-state decoding stay off the tick-thread apply path.
- Replaced or cleared compare requests interrupt their worker task; long diff passes stop cooperatively instead of consuming CPU after the UI no longer needs them.
- Cancelled compare/preview mesh preparation interrupts the submitted worker, and stale pending overlay meshes are closed before they can retain CPU-side or GPU-side buffers.
- Bounds arithmetic rejects reversed ranges and cannot silently overflow; a single work-zone selection edit is capped at 65,536 section cells to prevent an accidental unbounded client allocation.
- Recovery drafts are serialized once per flush; zone save reuses that durable draft instead of rewriting the full draft before isolating its operation draft, and the operational journal retains only its newest 512 entries. Recovery base/WAL reads, appends, compaction, and deletion share one project-path lock, so an async append cannot be mistaken for a truncated crash tail. Project dirty block sections and entity chunks are coalesced in the compact `recovery/dirty-scope.bin` sidecar instead of storing per-tick payloads.
- If a crash lands after the branch head is published but before dirty-scope cleanup, startup retains every dirty section, rebases the ledger to the published head, and discards only the now-stale isolated operation draft covered by that ledger. The next reconciliation removes already-saved positions while preserving later live differences.
- Repeated unchanged-draft checks reuse a cached content fingerprint until the tracked block or entity set changes.
- Empty entity-causal lookups reuse one inactive frame, and normal mob ticks only expire a matched context instead of sweeping the whole registry.
- Snapshot sections are encoded once per chunk and reused for content deduplication, fingerprints, and the compressed snapshot frame; existing content blobs skip redundant LZ4 compression.
- Saving a version extends a fresh history index in memory instead of reopening and parsing every older manifest; stale indices still fall back to a full safe rebuild.
- Pending, work-zone, and selection overlays reuse the capture catalog instead of reparsing every project on client tick or render hot paths, and hot drafts are rejected before final reconciliation or deep snapshot copying.
- Preview pixel cropping, PNG encoding, request cleanup, and version metadata updates run on the low-priority preview worker instead of the render/client thread.
- Compare overlays build geometry in the cancellable background worker above 2,048 changed blocks, reject stale worker results after show/refresh/clear transitions, and skip rebuilding when refreshed content is unchanged.
- World-apply no-op pruning reuses its first live-state scan instead of decoding and comparing every target block twice.
- Shutdown fully drains dirty-chunk stabilization, and restore verification retains original targets even when no-op pruning skips their initial write while scanning those targets incrementally within the current tick deadline.
- Snapshot and patch storage no longer inserts fixed sleeps between chunks; background thread priority and cooperative cancellation control contention without slowing completed work.
- Restore, recovery, merge, and rollback replay must not capture themselves as new user edits.
- Restore, recovery, merge, and quick rollback verify final target state before reporting success. A mismatch gets one repair pass followed by a fresh read-back; a remaining mismatch fails the operation. Saved history and recovery are the return path.
- Full restore unions the patch lineage, recovery draft, and project dirty scope. Dirty sections are reconstructed from the requested target state and the ledger is cleared only after apply verification succeeds.
- Quick rollback isolates the same dirty scope under the world-operation barrier; an area rollback consumes fully covered sections and leaves partial/outside sections pending.
- Zone and partial restore reconcile only ledger sections intersecting their hard spatial scope, never apply outside it, and mark their verified result as new pending safety history against the unchanged branch head.
- An active work zone treats a dirty block section inside one of its 16x16x16 cells as saveable even when no player-attributed draft exists; dirty sections outside the zone remain pending. Entity chunks remain project-level because a chunk column has no zone-cell Y boundary.
- Save/amend and history-apply operations hold a world mutation barrier from queueing through completion. Vanilla simulation is frozen while player movement, break/use/interact, containers, creative slots, world-editing commands, direct block/entity writes, and tool-driven section writes are rejected; Lumi's capture-suppressed apply remains allowed. A world that was already tick-frozen stays frozen when the operation releases its lease.
- Existing workspaces mark every confirmed persistent block mutation, including actionless ambient fallout, in the project dirty-section sidecar after capturing any missing pre-mutation chunk baseline. Action attribution still controls player undo: live-state stabilization rereads only positions observed under that action instead of absorbing unrelated differences from the same section. Ambient fallout still cannot create a workspace, and internal Lumi apply plus unowned generation/deserialization sections are excluded. Axiom buffer edits preflight baselines and bulk-mark unique sections instead of creating dirty payloads per block.
- Save reconciles only selected dirty sections and entity chunks against the authoritative branch head, merges the result into the ordinary patch v9 draft, and publishes the manifest before rebasing or clearing the ledger. Work-zone saves consume their section cells while leaving the remaining scope pending.
- Every new patch, snapshot, and entity checkpoint is reopened and validated before its version manifest is published; a missing, corrupt, or mismatched payload fails the save while the operation draft and dirty ledger remain recoverable.
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

Runtime logs are written under the normal Minecraft `logs/` directory or the world-local `lumi/test-logs/` directory for test profiles, including multiplayer work-zone smoke behavior logs.

## License

GPL-3.0-only. See [LICENSE](LICENSE).
