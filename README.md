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
| `Compare` | Diff between two saved states, or between a saved state and the live game state | `DiffService` |
| `Restore` | Apply a chosen version back into the map and move the active head to it | `RestoreService` |
| `Partial restore` | Apply a bounded region from an older save as a new save on the active branch | `RestoreService` |
| `Import / Export` | Portable branch or project history packages for review and combine workflows | `HistoryShareService`, `ProjectArchiveService` |
| `Recovery` | Crash-safe draft storage | `recovery/draft.*` |

## Current Features

- automatic dimension projects
- builder-first Build History UI built around `Save build`, `See changes`, recent saves, `Branches`, and `More`
- lightweight save, branch, import/export, cleanup, diagnostics, and advanced navigation with a persistent left workspace menu and live background-operation refresh while screens stay open
- patch-first history with checkpoint snapshots
- remappable quick-save chord, default `Left Alt+S`, that opens a standalone save-name dialog without entering Build History
- dedicated save screen with optional `Replace latest save`
- save details screen with isometric preview, restore, see-changes, and branch actions
- See Changes screen for saved states, branches, and the current build, with manual references hidden under Advanced
- live undo and redo for the last tracked builder actions with default `Left Alt+Z` / `Left Alt+Y` bindings through the remappable Lumi overlay key; changing the overlay key changes these chords too
- short-lived secondary fallout near the latest tracked action is folded into that same undo/redo step when it settles right after the edit; undo/redo drains already-dirty stabilization chunks first so poured fluid, contact-created source blocks, and falling-block deltas from whole-dimension sessions can join before the action is selected
- undo/redo replays stored block states without immediate redstone neighbor updates or placement physics, so restored TNT beside powered redstone is visible but not auto-primed by the replay
- runtime-only redstone state flips and piston animation blocks are ignored so active mechanisms do not pollute pending history or the recent action overlay
- hard restore that moves the active branch head
- region-scoped partial restore from save details, written back as a new `PARTIAL_RESTORE` save
- recovery drafts with WAL compaction
- client-rendered textured isometric preview images auto-framed from changed blocks with safe context padding
- material delta summaries and integrity checks under focused details/diagnostic screens
- zip import/export under `More`, including branch-scoped packages in the game-root `lumi-projects` folder with optional previews
- imported review projects with deletion, cached combine review, and same-area overlays for shared branches
- optional WorldEdit edit-session capture when WorldEdit is present, without a hard runtime dependency
- conservative external builder-tool capture for WorldEdit, FAWE-style chunk placement, Axiom block buffers, Axion, AutoBuild, SimpleBuilding, Effortless Building, Litematica/Tweakeroo placement paths, and known tool stacks that reach Minecraft block or entity mutation paths
- conservative cleanup for orphaned snapshots, previews, cache files, and stale operation drafts
- capture of player edits plus builder-relevant entity spawn/remove/update and supported explosion edits, including TNT damage tied back to the action that primed it
- temporary overlay-key preview for the latest 10 undo actions, or redo actions while the overlay key plus redo is held, with translucent exposed sides and thicker outlines when compare highlight is not active

## How It Works

### Capture

1. A mixin, guarded external-tool adapter, known-tool stack fallback, direct section fallback, or entity lifecycle/update hook catches a block or entity change.
2. `HistoryCaptureManager` finds matching projects.
3. Explicit builder-driven sources can bootstrap a dimension project on demand, but ambient world-settling sources do not.
4. `WorldMutationCapturePolicy` drops piston animation sources, transient piston blocks, and runtime-only redstone state flips before they can enter drafts or live undo/redo.
5. `EntityMutationCapturePolicy` rejects non-entity-history sources before Lumi asks Minecraft to serialize entity NBT, so transient falling-block or mob internals cannot crash capture.
6. Whole-dimension sessions keep a causal chunk envelope rooted in explicit builder edits. The root chunk defines a one-chunk halo envelope, and Lumi captures per-chunk baselines lazily when a chunk inside that envelope first needs stabilization.
7. Ambient fallout such as fluid spread and falling blocks no longer append directly into the live draft for whole-dimension workspaces. They only re-mark chunks inside that causal envelope as dirty.
8. `TrackedChangeBuffer` merges explicit and targeted realtime block changes by position and entity changes by UUID immediately.
9. First-touch whole-dimension baseline capture copies compact chunk section payloads on the server thread and writes the compressed baseline file later on a dedicated low-priority capture-maintenance executor.
10. Before draft snapshots, idle flushes, save, amend, undo/redo selection, or freeze persist or consume anything, Lumi reconciles dirty envelope chunks on the server thread against the current world and stores the final stabilized diff on top of the live pending chunk buffer.
11. Recovery draft data flushes on an interval, but the WAL append and compaction run asynchronously on that same capture-maintenance executor.

### Save

1. `VersionService` consumes the active draft.
2. Patch payloads are prepared off-thread.
3. Metadata is written after payload files exist.
4. Amend-on-head preserves block and entity diffs from the replaced head.
5. Preview generation queues a lightweight request in project storage.
6. The client later fulfills that request with a textured isometric off-screen render and updates the version metadata.

### Restore

1. Active capture is frozen first.
2. Lumi shows confirmation for initial/root restores with a lightweight restore-plan summary.
3. Lumi tries direct same-lineage replay first, including rollback to `WORLD_ROOT`.
4. `WORLD_ROOT` fallback uses tracked baseline chunks when direct replay is not valid.
5. Snapshot fallback is used for normal versions when direct replay is not valid.
6. Tick-thread apply uses bounded chunk batches with pre-decoded block states and prepared entity batches.
7. Restore replay completes paired block halves such as beds, doors, and tall plants before apply.
8. A full restore moves the active branch head to the restored version after apply completes; a partial restore applies only selected bounds and writes a new save on the active branch.

## Runtime Rules

- JSON parsing, LZ4 decompression, and block-state decoding stay off the tick-thread apply path.
- Recovery WAL writes, WAL compaction, and baseline chunk compression stay off the server-tick capture path.
- Snapshot capture copies compact loaded-chunk payloads, including entity snapshots, on the server thread, then writes them asynchronously through storage.
- Storage repositories read and write payloads; Minecraft-layer preparers build tick-ready apply batches.
- Large WorldEdit/Axiom edits avoid block-entity NBT serialization for ordinary blocks, and capture project matching uses a cached dimension/chunk index.
- Partial restore can seek directly to selected chunks in new patch payloads instead of decoding the whole patch file.
- Restore apply uses adaptive tick budgets, caps block-entity/entity tail work per tick, and reports progress for entity-only batches.
- One map operation is expected at a time per save.
- Progress is exposed through operation state.
- Lumi screens do not pause the game.
- Detached old versions stay on disk for safety and remain visible in Build History after a reset-style restore.

## Architecture

| Layer | Responsibility | Main types |
| --- | --- | --- |
| Bootstrap | Fabric wiring, diagnostic commands, ticking, flushes | `LumaMod` |
| Domain model | persisted records and runtime state | `BuildProject`, `ProjectVersion`, `TrackedChangeBuffer` |
| Domain service | product logic | `VersionService`, `RestoreService`, `HistoryShareService`, `VariantMergeService` |
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

This creates and later archives a temporary bounded test project in an empty air volume above the player's current chunk. The run reports phase progress in chat, keeps a pass/fail report instead of stopping on the first failed check, verifies broad gameplay edits can be restored back to the initial save, checks a lightweight performance budget for scoped operations and synchronous tick work, and writes a detailed log under `<save>/lumi/test-logs/`.

Artifacts go to `build/libs/`. Packaging tasks also prune stale legacy `luma-*` artifacts so the folder only keeps the current `lumi-*` outputs.

## Quick Start

1. Open a local singleplayer save.
2. On dedicated servers, make sure the player has operator-level permissions. In local singleplayer, tracked builder actions start immediately.
3. Press `U`.
4. Lumi opens the current Build History directly when the dimension project is available.
5. Build in the tracked area.
6. Use the Lumi overlay key plus `Z` / `Y` to undo or redo the latest tracked Lumi action while no screen is open. The default overlay key is `Left Alt`, and changing that key changes these chords too.
7. Hold the Lumi overlay key to preview the latest 10 undo actions, or hold overlay key plus `Y` to preview redo actions, when the compare overlay is not active. The preview renders translucent exposed sides as well as thicker outlines.
   Seeing changes against `Current build` enables the world highlight immediately and refreshes it automatically while you keep editing.
8. Press the Lumi overlay key plus `S` to open Quick save when you only need to name and save the current build. The default chord is `Left Alt+S`; both keys are listed under Minecraft `Controls` -> `Lumi`.
9. Use `Save build` when you want the full save screen with suggestions or replace-latest tools.
10. Open a save when you want details, restore, see changes, or create a branch from it.
11. Use `Branches` for alternate build directions and `More` for import/export, settings, cleanup, diagnostics, and advanced tools.

## Scope

Current scope:

- singleplayer / integrated-server first
- menu flow first, with commands limited to diagnostics/help and the explicit `/lumi testing singleplayer` runtime test suite
- combine currently works through imported review projects for the same project lineage, with background review, block-level same-area detection, and validation messages before Lumi writes a combined save
- partial restore is available from save details with manual bounds
- compare overlay marks changed positions, not a full 3D preview

## Docs

- [User guide](docs/user-guide.md)
- [Commands](docs/commands.md)
- [Development](docs/development.md)
- [Architecture](docs/architecture.md)
- [Maintenance guide](docs/maintenance-guide.md)
- [Storage format](docs/storage-format.md)
- [Commit policy](docs/commit-policy.md)
- [Test client profile](docs/test-client.md)

## License

Licensed under [GPL-3.0](LICENSE).
