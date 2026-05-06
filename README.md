# Lumi

<p align="center">
  <img alt="Lumi banner" src="lumi-banner.png" />
</p>

<p align="center">
  <strong>Singleplayer-first build history for saving, comparing, branching, restoring, and recovering Minecraft projects.</strong>
</p>

<p align="center">
  <img alt="Minecraft 1.21.11" src="https://img.shields.io/badge/Minecraft-1.21.11-5E7C16?style=for-the-badge" />
  <img alt="Loader Fabric" src="https://img.shields.io/badge/Loader-Fabric-DBD0B4?style=for-the-badge" />
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-1F6FEB?style=for-the-badge" />
  <img alt="Environment Singleplayer First" src="https://img.shields.io/badge/Environment-Singleplayer%20First-2EA043?style=for-the-badge" />
  <img alt="License GPL 3.0" src="https://img.shields.io/badge/License-GPL%203.0-2EA043?style=for-the-badge" />
</p>

Lumi is a Fabric mod for Minecraft `1.21.11`.

It gives builders a project-oriented safety history for the current dimension or build area.

You can save builds, see changes, restore earlier states, try alternate branches, export/import portable history packages, review imported branches before combining them back into your build, partially restore selected regions, and recover pending edits after a crash.

Lumi's UI operations are intended for the local world owner. On dedicated servers, mutating Lumi actions require operator-level permissions; in singleplayer integrated worlds, builder edits are captured for history and live undo/redo immediately.

The normal UI uses builder terms:

- `Build History`
- `Save build`
- `Branches`
- `See changes`
- `Restore`
- `Import / Export`
- `Recovered work`

## What It Solves

Use Lumi if you want to:

- try a redesign without losing the stable version
- check what changed since the last save
- go back to an older state without copying full save folders
- keep separate branches for alternate build directions
- recover work after a crash or bad edit

## Core Model

| Term | Meaning | Stored in |
| --- | --- | --- |
| `Project` | Tracked area in one dimension | `project.json` |
| `Version` | Saved history node with message, stats, preview, and payload refs | `versions/*.json` |
| `Variant` | Named branch-like head pointer | `variants.json` |
| `History tombstones` | Soft-deleted save and branch ids hidden from normal history | `history-tombstones.json` |
| `Compare` | Diff between two saved states, or between a saved state and the live game state, loaded in the background for large histories | `DiffService` |
| `Restore` | Apply a chosen version back into the map and move the active head to it; quick rollback restores the active head directly | `RestoreService` |
| `Partial restore` | Apply a bounded restore from an older save as a new save on the active branch, either inside a selection or everywhere except it | `RestoreService` |
| `Import / Export` | Portable branch or project history packages for review and combine workflows | `HistoryShareService`, `ProjectArchiveService` |
| `Recovery` | Crash-safe draft storage | `recovery/draft.*` |

## Current Features

- automatic dimension projects
- builder-first Build History UI built around `Save build`, `See changes`, recent saves, and `Branches`, with maintenance tools kept in the sidebar `More` route
- one-time interactive onboarding wizard that explains safe save, undo/redo, restore, branch, compare, recovery, and import/export workflows, plus dismissible contextual hints on the main tabs and workflows; the tour shows remapped shortcuts as pixel key icons, continues over the Lumi workspace after the open shortcut, and can be replayed from `More` or `/lumi-onboarding`
- localized UI resources for English, Russian, French, Spanish, German, and Finnish
- lightweight save, branch, import/export, settings, storage cleanup, and advanced navigation with a persistent left workspace menu and live background-operation refresh while screens stay open
- patch-first history with checkpoint snapshots
- remappable quick-save chord, default `Left Alt+S`, that opens a standalone save-name dialog without entering Build History
- remappable quick rollback key, default `R`, that restores the active branch head without opening a screen; the next undo returns to the pre-restore state without moving the saved branch head, redo reapplies the rollback, and holding the Lumi action button with `R` runs an explicit hard return-before-restore
- dedicated save screen with optional `Replace latest save`
- save details screen with isometric preview, restore, see-changes, rename, soft-delete, and branch actions
- See Changes screen for saved states, branches, and the current build, with background-loaded diffs and manual raw-reference compare available under `More`
- live undo and redo for the last tracked builder actions with default `Left Alt+Z` / `Left Alt+Y` bindings through the remappable Lumi action button; changing the action button changes these chords too. While either chord is active, Lumi suppresses vanilla use/attack input so the shortcut does not also press levers, buttons, or other interactable blocks. WorldEdit and FAWE actions route those chords through the tools' native undo/redo commands, while captured Axiom capability actions replay through Lumi so tool-assisted breaks and placements use the state Lumi recorded. Axiom simple place/break buffers, including bulldozer and fast-place style hand edits, are split into block-scoped undo actions instead of one broad batch.
- non-player entity edits from explicit player and supported builder-tool actions are saved with their persistent NBT position and state, including mobs, item entities, display entities, paintings, and armor stands
- short-lived secondary fallout near the latest tracked action is folded into that same undo/redo step when it settles right after the edit; undo/redo drains already-dirty stabilization chunks first so poured fluid, contact-created source blocks, falling-block deltas, redstone block updates, and piston fallout can join before the action is selected, and this passive fallout does not discard an available redo
- item drops produced by explosions, fluid, falling blocks, or nearby block-update fallout are captured only for the matching undo/redo action; undo removes those dropped item entities and redo respawns them without storing them in recovery drafts or saved versions
- undo/redo replays stored block states with side-effect-suppressed placement flags; durable redstone/mechanism state transitions send scoped neighbor updates so toggled controls settle connected circuitry, while ordinary replay still avoids placement physics and piston event cascades
- redstone and mechanism state is saved and replayed as final settled block state, including lever `powered`, button `powered`, wire `power`, lamp `lit`, openable `open`, repeater/comparator state, piston base `extended`, settled `piston_head`, and moved blocks. Apply preparation completes missing settled piston head/removal companions from an explicit piston base, overwrites normalized transient air at the expected head position when needed, but never creates a piston base from a head-only record. Only short-lived `moving_piston` animation state is normalized away
- hard restore that moves the active branch head
- region-scoped partial restore from save details as a primary save action, written back as a new `PARTIAL_RESTORE` save, with `Only selected area` and `Everything except selection` modes using optional wooden-sword selected bounds or manual XYZ bounds. The applied partial restore is also recorded as a live undo/redo action.
- runtime-only wooden-sword region selection with `corners` and `extend` modes, long loaded-chunk targeting, Lumi action button + scroll mode switching, Lumi action button + right click deselect, and an in-world highlighted cuboid overlay
- history editing: rename saves, soft-delete safe saves, soft-delete inactive branches, and merge another local branch into the current branch as a new `MERGE` save
- soft-deleted save files remain accessible from the More screen's deleted saves section
- recovery drafts with WAL compaction, restore return points, and a direct recovery screen prompt only when a project opens with interrupted persisted draft work from a previous session
- optional `AUTO_CHECKPOINT` saves before large vanilla `/fill` or `/clone` commands, WorldEdit sessions, and Axiom block-buffer edits when a pending draft exists; the setting is off by default
- client-rendered textured isometric preview images auto-framed from changed blocks with safe context padding
- material delta summaries in See Changes and integrity checks under focused support screens
- zip import/export from the workspace sidebar, including branch-scoped packages in the game-root `lumi-projects` folder with optional previews
- imported review projects with deletion, cached combine review, and same-area overlays for shared branches
- optional WorldEdit edit-session capture when WorldEdit is present, without a hard runtime dependency
- conservative external builder-tool capture for WorldEdit, FAWE-style chunk placement, Axiom block buffers, Axion, AutoBuild, SimpleBuilding, Effortless Building, Litematica/Tweakeroo placement paths, and known tool stacks that reach Minecraft block or entity mutation paths
- stable external capability reporting: WorldEdit session selection/clipboard/schematic support uses public `LocalSession`/clipboard format APIs, FAWE inherits those claims only when the same compatible APIs are present, and Axiom remains limited to its custom region API plus fallback capture
- conservative cleanup for orphaned snapshots, previews, cache files, and stale operation drafts
- capture of player edits plus non-player entity spawn/remove/update with full persistent NBT position/state payloads, and supported explosion edits including TNT damage tied back to the action that primed it
- temporary action-button preview for the latest 10 undo actions, or redo actions while the Lumi action button plus redo is held, with translucent exposed sides for small edits and merged volume blobs for dense edits when compare highlight is not active

## How It Works

### Capture

1. A mixin, guarded external-tool adapter, known-tool stack fallback, direct section fallback, or entity lifecycle/update hook catches a block or entity change.
2. `HistoryCaptureManager` finds matching projects.
3. Explicit builder-driven sources can bootstrap a dimension project on demand, but ambient world-settling sources do not.
4. `WorldMutationCapturePolicy` classifies block mutations as direct captures, deferred stabilization work, or rejected transient state. Direct player/tool state toggles are captured immediately, while redstone block updates and piston fallout mark the active session dirty for final-state reconciliation.
5. `EntityMutationCapturePolicy` captures explicit player and known-tool non-player entity changes, but rejects non-history sources before Lumi asks Minecraft to serialize entity NBT, so transient falling-block or ambient mob internals cannot crash capture.
6. Whole-dimension sessions keep a causal chunk envelope rooted in explicit builder edits. The root chunk defines a one-chunk halo envelope, and Lumi captures per-chunk baselines lazily when a chunk inside that envelope first needs stabilization.
7. Ambient fallout such as fluid spread, falling blocks, redstone block updates, and piston movement no longer appends directly into the live draft. It only re-marks chunks inside that causal envelope as dirty.
8. Related item drops from explosions, water flow, falling blocks, and block-update fallout are attached to the latest nearby undo/redo action only; they are deliberately excluded from recovery drafts and saved versions.
9. `TrackedChangeBuffer` merges explicit and targeted realtime block changes by position and entity changes by UUID immediately.
10. First-touch whole-dimension baseline capture copies compact chunk section payloads on the server thread and writes the compressed baseline file later on a dedicated low-priority capture-maintenance executor.
11. Before draft snapshots, idle flushes, save, amend, undo/redo selection, or freeze persist or consume anything, Lumi reconciles dirty envelope chunks on the server thread against the current world and stores the final stabilized diff on top of the live pending chunk buffer. Property-only redstone deltas such as `lit`, `powered`, and `power` are stored as ordinary block-state history, so mechanisms restore to their saved final state without storing tick-by-tick pulse history. Replay sends scoped neighbor updates only for those durable redstone/mechanism state transitions. Settled piston replay is base-driven: extended bases can add their expected head, retracting bases can clear their old head, normalized transient air at the expected head is replaced by the settled head, and orphan heads are not used to synthesize new piston bases.
12. Recovery draft data flushes on an interval, but the WAL append and compaction run asynchronously on that same capture-maintenance executor.

### Save

1. `VersionService` consumes the active draft.
2. Patch payloads are prepared off-thread.
3. Metadata is written after payload files exist.
4. Amend-on-head preserves block and entity diffs from the replaced head.
5. Preview generation queues a lightweight request in project storage.
6. The client later fulfills that request with a textured isometric off-screen render and updates the version metadata.

### History editing and merge

1. `HistoryEditService` owns save rename, save soft-delete, and branch soft-delete rules.
2. Soft delete writes tombstones only; version manifests, patches, snapshots, previews, and baseline files stay on disk.
3. Deleting a branch head save moves that branch metadata back to the parent before hiding the save, while root, ambiguous multi-head, and non-leaf deletes are blocked.
4. Local branch merge compares the source branch against the current active branch, applies the resolved changes through `WorldOperationManager`, and writes a new `MERGE` save on the active branch. The source branch is unchanged.

### Restore

1. Active capture is frozen first.
2. Lumi shows confirmation for initial/root restores with a lightweight restore-plan summary.
3. Lumi tries direct patch replay first, including same-lineage rollback/replay and divergent branch transitions through a shared saved ancestor.
4. `WORLD_ROOT` fallback uses tracked baseline chunks when direct replay is not valid.
5. Snapshot fallback is used for normal versions when direct replay is not valid.
6. Tick-thread apply uses bounded chunk batches with pre-decoded block states, dense safe section rewrites or native section loops when available, direct loaded-section commits for sparse changes, and prepared entity batches.
7. Restore replay completes paired block halves such as beds, doors, and tall plants before apply.
8. A full restore moves the active branch head to the restored version after apply completes; when a Lumi selection exists, the confirmation also offers `Only selected area` and `Everything except selection`.
9. Partial restore writes a new save on the active branch and records the applied change in the live undo/redo stack. Direct history uses patch replay; cross-lineage targets fall back to a finite snapshot/baseline target-state plan before apply. The form can consume the current Lumi wooden-sword selection and marks that request as `LUMI_REGION`.

## Runtime Rules

- JSON parsing, LZ4 decompression, and block-state decoding stay off the tick-thread apply path.
- Recovery WAL writes, WAL compaction, and baseline chunk compression stay off the server-tick capture path.
- Snapshot capture copies compact loaded-chunk payloads, including entity snapshots, on the server thread, then writes them asynchronously through storage.
- Storage repositories read and write payloads; Minecraft-layer preparers build tick-ready apply batches.
- Large WorldEdit/Axiom edits avoid block-entity NBT serialization for ordinary blocks, and capture project matching uses a cached dimension/chunk index.
- `Only selected area` partial restore can seek directly to selected chunks in new patch payloads instead of decoding the whole patch file. `Everything except selection` plans the same restore path but filters out selected blocks after loading the relevant changes. If no direct patch path exists, Lumi reconstructs current and target states from snapshots, baseline chunks, and patches off-thread, then rejects the restore if required payloads are missing.
- Auto checkpoints save any existing pending draft before large external edits; if no draft exists, the current branch head is already the checkpoint and Lumi does nothing.
- Restore apply uses adaptive tick budgets, safe dense section rewrites, native or direct section writes with vanilla fallback, batched section packets, capped block-entity/entity tail work per tick, and progress for entity-only batches.
- One map operation is expected at a time per save.
- Progress is exposed through operation state. The in-world action bar uses short status text and only shows a compact ASCII progress bar for larger active operations.
- Lumi screens do not pause the game.
- Detached old versions stay on disk for safety and remain visible in Build History after a reset-style restore; tombstoned saves and branches stay on disk but are hidden from normal UI and lineage.

## Architecture

| Layer | Responsibility | Main types |
| --- | --- | --- |
| Bootstrap | Fabric wiring, diagnostic commands, ticking, flushes | `LumaMod` |
| Domain model | persisted records and runtime state | `BuildProject`, `ProjectVersion`, `TrackedChangeBuffer` |
| Domain service | product logic | `VersionService`, `RestoreService`, `HistoryEditService`, `HistoryShareService`, `VariantMergeService` |
| Minecraft adapter | game hooks and map mutation | `HistoryCaptureManager`, `WorldOperationManager`, `BlockChangeApplier`, `WorldMutationCapturePolicy` |
| Storage | file layout and payload I/O | `ProjectLayout`, repositories in `storage/repository` |
| Client UI | owo-ui screens, controllers, HUD, overlays, view state | `ScreenRouter`, `ProjectScreen`, `SaveScreen`, `ShareScreen`, `WorkspaceHudCoordinator` |

Rules:

- domain services own product logic
- Minecraft adapters touch game APIs
- repositories handle files and payloads
- UI controllers stay thin

## Storage

Project data lives under:

```text
<save>/lumi/projects/<project>.mbp/
```

Main files:

- `project.json`
- `variants.json`
- `versions/*.json`
- `patches/*.meta.json`
- `patches/*.bin.lz4`
- `snapshots/*.bin.lz4`
- `previews/*.png`
- `preview-requests/*.json`
- `recovery/draft.bin.lz4`
- `recovery/draft.wal.lz4`
- `recovery/journal.json`
- `recovery/last-restore-return.json`

Runtime test reports from `/lumi testing singleplayer` are written under:

```text
<save>/lumi/test-logs/
```

History archives and share packages are written under:

```text
<game>/lumi-projects/
```

See [docs/storage-format.md](docs/storage-format.md) for the full format.

## Target

- Minecraft `1.21.11`
- Fabric Loader `0.19.2`
- Java `21`
- Fabric API `0.141.3+1.21.11`

Main libraries:

- `cloth-config`
- `owo-lib`
- `lz4-java`

The client menus are implemented with owo-ui. Lumi depends on `owo-lib` for Fabric `1.21.11`.

Build output is one mod jar.

## Build

```powershell
.\gradlew.bat build
```

Run client:

```powershell
.\gradlew.bat runClient
```

Dev launch tasks automatically remove packaged `lumi-*.jar` and legacy `luma-*.jar` copies from the local `run/*/mods` folders so Loom only loads the compiled source-set output.

Run test client:

```powershell
.\scripts\run-test-client.ps1
```

The default test-client profile installs a small Fabric `1.21.11` builder-tool stack for local validation: Fabric API, WorldEdit, and a pinned `Axiom-5.4.1-for-MC1.21.11.jar` Modrinth file. The broader performance-mod stack is available with `.\scripts\run-test-client.ps1 -FullStack`. See [docs/test-client.md](docs/test-client.md) for the complete mod list.

Run tests:

```powershell
.\gradlew.bat test
```

Run the local in-world regression suite from a singleplayer save with cheats enabled:

```mcfunction
/lumi testing singleplayer
```

This creates and later archives a temporary bounded test project in an empty air volume above the player's current chunk. The run reports phase progress in chat, keeps a pass/fail report instead of stopping on the first failed check, verifies broad gameplay edits including a closed redstone loop, non-player entity position/state capture, quick rollback of a saved entity update, a `gameMode`-driven water bridge, and controlled TNT interaction can be undone/redone or restored back to the initial save, checks preview fulfillment after a gameplay save, checks a lightweight performance budget for scoped operations and synchronous tick work, and writes a detailed log under `<save>/lumi/test-logs/`.

Artifacts go to `build/libs/`. Packaging tasks also prune stale legacy `luma-*` artifacts so the folder only keeps the current `lumi-*` outputs.

## Quick Start

1. Open a local singleplayer save.
2. On dedicated servers, make sure the player has operator-level permissions. In local singleplayer, tracked builder actions start immediately.
3. Press `U`.
4. Lumi opens the current Build History directly when the dimension project is available.
5. Build in the tracked area.
6. Use the Lumi action button plus `Z` / `Y` to undo or redo the latest tracked action while no screen is open. The default action button is `Left Alt`, and changing it changes these chords too. Lumi suppresses vanilla use/attack while these chords are active, so the same input does not also interact with levers or blocks in front of the player. WorldEdit/FAWE actions use native tool undo/redo; captured Axiom capability actions replay through Lumi, and simple Axiom place/break buffers are split into block-scoped undo steps.
7. Hold the Lumi action button to show all pending unsaved changes since the active head, or hold it plus `Z`/`Y` to preview undo/redo actions when the compare overlay is not active. Pending overlays above the detailed cap collapse into bounded tiled orange volume blobs so the client does not build unbounded overlay geometry.
   Opening See Changes for a resolved diff enables the world highlight immediately; comparisons against `Current build` refresh automatically while you keep editing.
8. Press the Lumi action button plus `S` to open Quick save when you only need to name and save the current build. The default chord is `Left Alt+S`; both keys are listed under Minecraft `Controls` -> `Lumi`.
9. Use `Save build` when you want the full save screen with manual naming or replace-latest tools.
10. Open a save when you want details, restore, see changes, or create a branch from it.
11. Use `Branches` for alternate build directions, the sidebar for Import / Export and Settings, and `More` for storage cleanup, manual compare, the history graph, raw references, or the Deleted saves tab.

## Scope

Current scope:

- singleplayer / integrated-server first
- menu flow first, with commands limited to diagnostics/help and the explicit `/lumi testing singleplayer` runtime test suite
- combine currently works through imported review projects for the same project lineage, with background review, block-level same-area detection, and validation messages before Lumi writes a combined save
- partial restore is available from save details with manual bounds or a wooden-sword Lumi selection, including inside-selection and everything-except-selection modes
- compare overlay marks changed positions, not a full 3D preview; large compare diffs and large world-highlight preparation run asynchronously, cache exposed changed-block meshes by section, and reuse uploaded GPU buffers between frames, while extremely large comparisons collapse into bounded tiled volume blobs so the client does not build unbounded overlay geometry

## TODO

- [ ] Multiplayer support
- [ ] Additional test coverage
- [ ] Exploit discovery and hardening
- [x] Wiki website mockup
- [x] Mod landing page mockup
- [ ] Tutorial video

## Docs

- [Landing page mockup](site/landing/index.html)
- [Download archive mockup](site/download/index.html)
- [Changelog mockup](site/changelog/index.html)
- [Wiki mockup](site/wiki/index.html)
- [User guide](docs/user-guide.md)
- [Commands](docs/commands.md)
- [Development](docs/development.md)
- [Architecture](docs/architecture.md)
- [Module map](modules.md)
- [Maintenance guide](docs/maintenance-guide.md)
- [Storage format](docs/storage-format.md)
- [Commit policy](docs/commit-policy.md)
- [Test client profile](docs/test-client.md)

## License

Licensed under [GPL-3.0](LICENSE).
