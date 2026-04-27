# Development

## Environment

- Minecraft target: `1.21.11`
- Loader: Fabric
- Java target: `21`
- Build tool: Gradle with Fabric Loom

## Common tasks

Build the mod:

```powershell
.\gradlew.bat build
```

Run the normal client dev profile:

```powershell
.\gradlew.bat runClient
```

`runClient`, `runTestClient`, and the GameTest launch tasks now remove packaged `lumi-*.jar` and legacy `luma-*.jar` copies from `run/*/mods` before launch. This keeps Loom's dev runtime on the compiled source sets and avoids duplicate self-mod loads after the `luma -> lumi` rename.

Run the dedicated test client profile:

```powershell
.\scripts\run-test-client.ps1
```

See [test-client.md](test-client.md) for details.

Run the automated test suite:

```powershell
.\gradlew.bat test
```

This now includes regression checks for:

- compare overlay nearest-entry selection
- commit graph layout on large histories
- detached commit visibility after a restore-style reset
- recovery draft isolation while save/amend operations run
- WorldEdit optional edit-session context wiring
- zip archive import/export for project history, with previews optional and recovery drafts excluded
- variant-scoped history package export/import, imported review package deletion, cached imported-variant merge planning, conflict zones, and per-zone resolutions
- conservative cleanup flow for orphaned snapshots/previews/cache and stale operation drafts
- material delta summarization on large diffs
- lightweight home, variants, and share controller loading plus operation-state normalization

Run server GameTests:

```powershell
.\scripts\run-test-client.ps1 -GradleTasks runGameTest
```

Run client GameTests:

```powershell
.\scripts\run-test-client.ps1 -GradleTasks runClientGameTest
```

CI can run the headless production client GameTest task:

```powershell
.\scripts\run-test-client.ps1 -GradleTasks runProductionClientGameTest
```

Enable verbose runtime tracing for debugging:

- per workspace: `Settings -> Debug -> Debug logging`
- global JVM flag: `-Dlumi.debug=true`
- accepted capture sessions keep the first 32 per-mutation traces behind debug logging, while info level stays focused on buffer checkpoints, queued/completed maintenance work, and reconcile summaries
- whole-dimension stabilization now logs dirty-chunk reconcile summaries before draft flush/save/freeze, so startup diagnostics and reconcile summaries should be inspected together when ambient fallout looks suspicious

## Repository layout

The codebase currently follows these top-level areas:

- `src/main/java/io/github/luma/domain`
  Product-facing models and services for projects, versions, variants, recovery, diff, preview, and integrity.
- `src/main/java/io/github/luma/storage`
  Save-file layout plus repositories for metadata, patches, snapshots, variants, and recovery.
- `src/main/java/io/github/luma/minecraft`
  Minecraft-specific capture, diagnostic command, and world-application code.
- `src/main/java/io/github/luma/integration`
  Optional integration contracts, typed capability reporting, WorldEdit edit-session tracking, and fallback status plumbing for external builder tools.
- `src/client/java/io/github/luma/ui`
  `Screen + Controller + ViewState` client UI implementation with router-driven navigation.

For the current architecture, responsibility boundaries, and runtime invariants, see [architecture.md](architecture.md).

## UI architecture

The current menu flow is centered around:

- `DashboardScreen`
- `CreateProjectScreen`
- `ProjectScreen`
- `SaveScreen`
- `SaveDetailsScreen`
- `VariantsScreen`
- `ShareScreen`
- `RecoveryScreen`
- `CompareScreen`
- `SettingsScreen`
- `MoreScreen`
- `CleanupScreen`
- `DiagnosticsScreen`
- `AdvancedScreen`

Controllers own service access and loading logic. Screens keep transient UI state. `LumaScreen` is the shared non-pausing base class for in-world menus. `WorkspaceHudCoordinator` drives the always-on HUD overlay and action-bar progress independently of screen lifetime.
`ProjectHomeScreenController`, `VariantsScreenController`, and `ShareScreenController` are lightweight summary loaders. They avoid diff, material, cleanup, diagnostics, archive scan, and merge-preview work on open and poll fresh operation snapshots every 10 client ticks so conflicting mutation actions unlock without reopening the screen. Import / Export combine previews are requested only by explicit review actions, then cached by imported package and target idea while the screen is open.
Save and save-details screens now use dedicated narrow view-state records rather than the old shared project tab state. The old tab view builders are removed instead of being kept as hidden UI scaffolds.

LDLib2 is the target UI toolkit for future migration. The published LDLib2 UI docs and source (`https://low-drag-mc.github.io/LowDragMC-Doc/ldlib2/ui/`, `https://github.com/Low-Drag-MC/LDLib2`) are currently NeoForge-oriented around Minecraft `1.21.1`, while Lumi targets Fabric `1.21.11`. Keep LDLib2 behind `UiToolkitRegistry` and reflection-style detection until a compatible Fabric runtime exists. Do not add a hard `fabric.mod.json` or Gradle runtime dependency that makes the Fabric build unlaunchable.
`LdLib2InterfaceBlueprint` is the migration contract for the home screen. It maps the simple builder flow to LDLib2 element roles (`UIElement`, `Label`, `Button`, `ScrollerView`, and `TabView`), pins the built-in GDP theme (`ldlib2:lss/gdp.lss`), and records compact flex hints for the LDLib2 tree. `LdLib2ReflectiveUi` and `LdLib2ProjectHomeScreenFactory` use that runtime shape to create a real LDLib2 `ModularUIScreen` when compatible LDLib2 classes are present. `ProjectWindowLayout` remains the compact owo-lib fallback only for environments where LDLib2 is absent.

Current UX assumptions:

- pressing `U` opens the current dimension workspace directly
- pressing `Alt+Z` starts undo for the latest tracked Lumi action in the current dimension workspace
- pressing `Alt+Y` starts redo for the latest tracked Lumi action in the current dimension workspace
- nearby short-lived secondary fallout can join the latest tracked undo/redo action instead of disappearing from the live action stack
- pressing `H` hides or shows the current compare overlay without clearing the diff data
- pressing `Compare` enables the world highlight immediately for the resolved diff
- comparing against `current` refreshes the active world highlight automatically every few client ticks while the overlay data is present
- holding the compare x-ray key shows the compare highlight through blocks while held, with `Left Alt` as the default remappable control
- holding `Alt` while compare highlight is inactive shows the latest 10 tracked Lumi actions with a fading temporary overlay
- the dashboard is now secondary navigation under `More` -> `Projects`
- the workspace home screen is Build History: a LDLib2-style window with `Save build` as the only primary action, one-click `See changes`, recent saves, `Ideas`, and `More`
- low-frequency tools such as import/export, settings, cleanup, diagnostics, technical graph, manual compare, legacy limited projects, and raw info live behind `More` or `Advanced`
- save composition, save details, idea management, import/export combine review, cleanup, diagnostics, and advanced tools now have dedicated screens instead of sharing one overloaded project page

## History architecture

Current runtime history behavior:

- `HistoryCaptureManager` still records explicit tracked block changes inside project bounds, including TNT ignition, explosions, piston movement, and selected mob block mutations, while still excluding Lumi's own restore applications.
- Authorized player-root actions are mirrored into `UndoRedoHistoryManager`, which keeps a bounded per-project action stack for live undo/redo and the recent-action overlay.
- Automatic dimension project bootstrap is limited to explicit builder-driven sources. Ambient fluid, fire, growth, block-update, and mob mutations cannot create a workspace on world load by themselves.
- Optional external builder tools use explicit mutation sources. WorldEdit sessions are observed through a guarded `EditSessionEvent` extent wrapper when WorldEdit is present; Axiom sessions are treated as builder-driven roots only when Lumi's guarded adapters or fallback capture path can observe the mutation.
- Client controllers and diagnostic commands now require an operator-level permission set. In singleplayer, the practical requirement is cheats enabled for the world owner.
- New live capture sessions are also limited to explicit builder-driven sources. Whole-dimension sessions now seed a causal chunk envelope from those root edits, then capture per-chunk session baselines lazily as compact chunk snapshot payloads only when a chunk inside that envelope first needs stabilization.
- First-touch whole-dimension tracking no longer samples the live world block-by-block. The server thread copies loaded chunk section palettes and real block-entity tags once, queues async baseline persistence, and returns to normal capture immediately.
- For whole-dimension workspaces, fluid spread and falling blocks no longer append directly into the draft. They only re-mark chunks inside that causal envelope as dirty, and `SessionStabilizationService` later rebuilds the final chunk diff by comparing compact chunk snapshots instead of walking the world through `level.getBlockState()`.
- Secondary explosion, piston, fire, growth, block-update, and mob sources are still gated by the active session envelope so one explicit edit does not pull unrelated far-away cave settling into the same draft.
- Changes are aggregated into an in-memory recovery draft immediately, then flushed asynchronously through the capture-maintenance executor and journaled while the session is active.
- `ProjectService` bootstraps a shared `WorldOriginInfo` manifest and a metadata-backed `WORLD_ROOT` version for new dimension workspaces. The manifest is schema v2 and includes a conservative Lumi creation marker plus datapack and generator fingerprints.
- `ProjectArchiveService` owns UI-driven zip import/export for stable project history. It delegates zip I/O to `ProjectArchiveRepository` and keeps the feature outside the save/restore tick path.
- `HistoryShareService` backs the `Import / Export` flow on top of the same archive format by exporting one idea lineage, importing it back as a review project, and deleting imported review projects after validating they belong to the same project lineage.
- `ShareScreenController` keeps history package import/export separate from Build History and Ideas, only asks `VariantMergeService` for a combine preview when the user explicitly reviews one imported package, and moves that preview work through a small background cache so the screen does not block on storage scans.
- `ProjectCleanupService` builds a conservative cleanup policy from current version metadata and active operation state, then delegates file deletion to `ProjectCleanupRepository`.
- `VersionService` stores new versions as patch-first history, supports amend-on-head, isolates in-progress operation drafts from live capture, and inserts checkpoint snapshots by policy.
- Preview generation now queues lightweight request files on the server side and fulfills them later through the client-side `PreviewCaptureCoordinator`.
- `RestoreService` prefers direct same-idea patch replay, including `WORLD_ROOT` ancestor restores, exposes a lightweight restore plan summary for `Initial` confirmation, falls back to tracked baseline chunks or checkpoint snapshot plus patch chain when direct replay is not valid, and resets the active idea head to the restored save on success without deleting detached saves.
- `RestoreService` also supports same-lineage selected-area restore from save details. It filters pending draft and direct patch changes to manual bounds, applies prepared batches through the operation model, then writes a new `PARTIAL_RESTORE` version on the active idea while preserving pending draft changes outside the selected region.
- `VariantService` keeps one head pointer per variant.
- `VariantMergeService` turns an imported review project back into local history by finding a shared saved ancestor, grouping overlapping conflicts into chunk-connected review zones, and delegating merged version persistence to `VersionService`.
- `DiffService` reconstructs version-to-version changes from patch history.

The current history pipeline is intentionally split into:

- async preparation, compression, and decoding work away from the server tick
- bounded chunk-batch application on the server tick through `WorldOperationManager`
- operation snapshots that surface progress to the UI instead of pretending a long task finished immediately
- optional debug tracing for capture, save, restore, recovery, compare, HUD, and background operations

Current world-apply runtime types:

- `ChunkBatch`
- `SectionBatch`
- `EntityBatch`, including spawn, remove, and full-NBT update lists
- `LocalQueue`
- `GlobalDispatcher`
- `BatchState`
- `BatchProcessor`
- `HistoryStore`

## Build and packaging notes

- Lumi is shipped as one distributable mod jar.
- Support libraries used by the mod are included through Loom jar-in-jar configuration.
- The textured preview path now uses Lumi's own layered client mesh builder on top of the `1.21.11` render APIs instead of an external meshing runtime dependency.
- LDLib2 is not packaged until a compatible Fabric `1.21.11` artifact exists; the current client UI ships with the existing owo-lib fallback and a runtime status surface under diagnostics.
- Fabric API remains an external required mod.
- Packaging tasks prune stale legacy `luma-*` outputs from `build/libs` before writing the current `lumi-*` artifacts.

## Storage references

Project data is stored per world under:

```text
<world>/lumi/projects/<project>.mbp/
```

Shared world origin metadata lives next to the projects root:

```text
<world>/lumi/world-origin.json
```

See [storage-format.md](storage-format.md) for the exact folder and file layout.

## Commit policy

The repository keeps a strict implementation policy:

- initialize git before implementation work
- commit every 100-300 changed lines of code or earlier for a coherent vertical slice
- avoid mixing unrelated build, storage, UI, integration, and migration changes when they can stand alone
- update the affected documentation in the same change set whenever behavior, storage, or architecture changes

The current repo also ships that policy in [commit-policy.md](commit-policy.md).

## Coding conventions

- Keep the product wording builder-friendly. Prefer `project`, `version`, `variant`, `compare`, `restore`, and `recovery`.
- Keep the mod usable through menus first. Commands are read-only diagnostics/help only.
- Preserve the singleplayer-first assumption unless a change explicitly expands runtime scope.
- When touching storage, prefer forward-only adjustments with simple legacy handling for the current local format.
- Preview generation now has a split responsibility: the server can queue preview capture requests in storage, while the client render path fulfills them later.
- Apply OOP and SOLID consistently. Favor small, focused collaborators with explicit responsibilities over utility-heavy procedural code.
- Keep business rules in domain services and models, Minecraft-specific side effects in adapter layers, and file I/O inside repositories.
- Treat documentation as part of the implementation. If a change alters data flow, storage, or user-visible behavior, update the docs before the work is considered done.
