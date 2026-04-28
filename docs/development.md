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
- project tracking index membership checks, chunk-addressable patch selective reads, and snapshot chunk-list scans
- entity diff merge, entity policy filtering, recovery/patch round-trips, and entity-only restore batches
- lightweight home, variants, and share controller loading plus operation-state normalization

Run server GameTests:

```powershell
.\scripts\run-test-client.ps1 -GradleTasks runGameTest
```

Run client GameTests:

```powershell
.\scripts\run-test-client.ps1 -GradleTasks runClientGameTest
```

Run the idle startup-only client GameTests:

```powershell
.\scripts\run-baseline-idle-client.ps1
.\scripts\run-idle-client.ps1
```

CI can run the headless production client GameTest task:

```powershell
.\scripts\run-test-client.ps1 -GradleTasks runProductionClientGameTest
```

Run the integrated-world runtime regression suite from a local singleplayer save with cheats enabled:

```mcfunction
/lumi testing singleplayer
```

The command creates an archived temporary bounded project in an empty air volume above the player's chunk and drives the real save, undo/redo, amend, branch, compare, export, partial-restore, full-restore, gameplay interaction, integrity, and cleanup services through the server tick loop. Its gameplay phase covers adjacent block fallout, bulk block placement, block entities, redstone updates, fluid placement, multi-block doors, oriented block states, crop/farmland states, openable blocks, item entities, and a builder-relevant entity spawn, then verifies that tracked block/entity actions reached the live recovery draft. It reports phase progress in chat, records pass/fail checks without stopping at the first failed assertion, and writes a detailed log under `<world>/lumi/test-logs/`.

`runClientGameTest` starts a consistent singleplayer world and invokes the same runtime suite automatically before taking its smoke screenshot. `runBaselineClientGameTest` starts a separate consistent singleplayer world with the small `lumi-baseline-gametest` action mod and explicitly removes Lumi's dev source-set from the launch config before startup. The baseline action mod runs the same broad gameplay surface without Lumi history capture, so load comparisons include player block break, adjacent fallout, bulk block placement, block entities, redstone, fluids, multi-block and stateful blocks, crops, and entity lifecycle work rather than a client startup-only sample.

Compare runtime load between a no-Lumi baseline launch and a Lumi launch:

```powershell
.\scripts\compare-runtime-load.ps1 `
  -BaselineCommand "powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-baseline-client.ps1" `
  -LumiCommand "powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-test-client.ps1 -GradleTasks runClientGameTest" `
  -Runs 3 `
  -RequireBaselineActionRun `
  -RequireLumiActionRun `
  -FailOnRegression
```

The harness writes raw logs plus `summary.json` and `summary.md` under `build/runtime-load/<timestamp>/`. It compares wall-clock time, `Can't keep up!` tick-delay reports, long server tick warnings, WARN/ERROR counts, Lumi WARN counts, render pipeline failures, baseline gameplay checks, and Lumi singleplayer action-suite results. By default the baseline run appends new content from `build/run/baselineClientGameTest/logs/latest.log`, and the Lumi run appends new content from `run/test-client/logs/latest.log` and `build/run/clientGameTest/logs/latest.log`; pass `-LumiExtraLogs` or `-BaselineExtraLogs` to attach additional game logs. `-RequireBaselineActionRun` and `-RequireLumiActionRun` fail the comparison when the expected gameplay suite did not run or reported failed checks. The baseline command should launch the same Minecraft/Fabric stack without the Lumi mod so the comparison measures Lumi's overhead rather than unrelated modpack or world-generation cost.

For startup-only overhead, use the idle wrapper. It launches the same singleplayer world shape, waits for chunk rendering plus a short idle window, and does not run the full Lumi project/history workflow:

```powershell
.\scripts\compare-idle-startup-load.ps1 -Runs 3
```

Idle summaries are written under `build/runtime-load-idle/<timestamp>/`. Use this before optimizing startup cost, and use the full runtime comparison when validating history workflow cost.
World-origin metadata bootstrap is intentionally delayed until after the first player has entered the world and a short idle window has elapsed, so idle startup comparisons should not include Lumi storage bootstrap work unless a test explicitly opens a workspace immediately.
Pass `-StartupProfile` to the idle wrapper to enable `-Dlumi.startupProfile=true` for the Lumi run. This logs client initializer timings and aggregated `ChunkSectionOwnershipRegistry` counters without enabling noisy per-project debug tracing:

```powershell
.\scripts\compare-idle-startup-load.ps1 -Runs 1 -StartupProfile
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
  owo-ui screens, controllers, overlays, and view-state records with router-driven navigation.

For the current architecture, responsibility boundaries, and runtime invariants, see [architecture.md](architecture.md).

## UI architecture

The current menu flow is centered around `ScreenRouter`, `LumaScreen`, and focused owo-ui route classes such as `ProjectScreen`, `SaveScreen`, `SaveDetailsScreen`, `CompareScreen`, `VariantsScreen`, and `ShareScreen`. Every in-game menu is code-driven owo-ui using `BaseOwoScreen`, `OwoUIAdapter`, `FlowLayout`, `ScrollContainer`, `Sizing`, `Insets`, and `Surface`.

Controllers own service access and loading logic. Screens keep transient UI state and layout composition. `WorkspaceHudCoordinator` drives the optional top-right HUD overlay and action-bar progress independently of screen lifetime.
`ProjectHomeScreenController`, `VariantsScreenController`, and `ShareScreenController` are lightweight summary loaders. They avoid diff, material, cleanup, diagnostics, heavy archive validation, and merge-preview work on open and poll fresh operation snapshots every 10 client ticks so conflicting mutation actions unlock without reopening the screen. Import / Export lists lightweight zip summaries from the game-root `lumi-projects` folder, while combine previews are requested only by explicit review actions and cached by imported package and target branch while the screen is open.
Save and save-details screens now use dedicated narrow view-state records rather than the old shared project tab state. The old tab view builders are removed instead of being kept as hidden UI scaffolds.

owo-lib is the only menu toolkit in this branch. Lumi declares it as a Fabric dependency for Minecraft `1.21.11`.

Current UX assumptions:

- pressing `U` opens the current dimension workspace directly
- pressing the Lumi overlay key plus `Z` starts undo for the latest tracked Lumi action in the current dimension workspace; the default overlay key is `Left Alt`
- pressing the Lumi overlay key plus `Y` starts redo for the latest tracked Lumi action in the current dimension workspace
- nearby short-lived secondary fallout can join the latest tracked undo/redo action instead of disappearing from the live action stack
- reconciled fluid and falling-block deltas from whole-dimension session stabilization also join the latest nearby undo/redo action when they settle inside the same time/radius window
- runtime-only redstone state flips and piston animation states do not become live undo/redo actions
- pressing `H` hides or shows the current compare overlay without clearing the diff data
- pressing `Compare` enables the world highlight immediately for the resolved diff
- comparing against `current` refreshes the active world highlight automatically every few client ticks while the overlay data is present
- holding the compare x-ray / Lumi overlay key shows the compare highlight through blocks while held, with `Left Alt` as the default remappable control
- holding the same remappable overlay key while compare highlight is inactive shows the latest 10 undo actions with a fading temporary overlay; holding the overlay key plus redo previews redo actions
- the dashboard is now secondary navigation under `More` -> `Projects`
- the workspace home screen is Build History: a compact owo-ui window with `Save build` as the only primary action, one-click `See changes`, recent saves, `Branches`, and `More`
- settings include a HUD section that can hide the persistent top-right Lumi panel without disabling action-bar operation progress, and settings persist immediately on valid field changes
- low-frequency tools such as import/export, settings, cleanup, diagnostics, technical graph, manual compare, legacy limited projects, and raw info live behind `More` or `Advanced`
- save composition, save details, branch management, import/export combine review, cleanup, diagnostics, and advanced tools now have dedicated screens instead of sharing one overloaded project page

## History architecture

Current runtime history behavior:

- `HistoryCaptureManager` still records explicit tracked block changes inside project bounds, including TNT ignition, explosions, and selected mob block mutations, while still excluding Lumi's own restore applications.
- `WorldMutationCapturePolicy` drops piston-source movement, transient piston blocks, and same-block runtime property flips such as `powered`, `lit`, and `extended`; `PersistentBlockStatePolicy` also normalizes `piston_head` and `moving_piston` out of new snapshot and restore apply paths.
- Authorized player-root actions are mirrored into `UndoRedoHistoryManager`, which keeps a bounded per-project action stack for live undo/redo and the recent-action overlay. In integrated singleplayer worlds, explicit builder actions can enter that stack immediately even when the permission frame is not yet operator-shaped; dedicated servers still require the permission gate.
- Automatic dimension project bootstrap is limited to explicit builder-driven sources. Ambient fluid, fire, growth, block-update, and mob mutations cannot create a workspace on world load by themselves.
- Optional external builder tools use explicit mutation sources where available. WorldEdit sessions are observed through a guarded `EditSessionEvent` extent wrapper when WorldEdit is present; the wrapper records old/new block transitions directly because some WorldEdit bulk paths do not surface through Minecraft `Level#setBlock`, and it serializes block-entity NBT only when the old or new state can hold a block entity. Lumi also recognizes WorldEdit, FAWE, Axiom, Axion, AutoBuild, SimpleBuilding, Effortless Building, Litematica, and Tweakeroo stack frames at block and entity mutation boundaries, with a guard so lower-level block fallbacks do not duplicate higher-level records. Axiom block-buffer packet applies are still captured before Axiom mutates chunk sections directly with the same lazy block-entity rule.
- Entity capture is centralized through `HistoryCaptureManager.recordEntityChange`. Generic server-side hooks capture entity spawn, removal, focused transform updates, tags, custom names, visibility, glowing state, and full NBT loads when the operation is player-rooted or comes from a known external builder stack. `EntityMutationCapturePolicy` limits player-originated entity history and unknown-stack external fallback inspection to builder-relevant persistent entities, rejects sources that cannot record entity history before NBT serialization, and rejects `SYSTEM` source changes so chunk-load entity data and ordinary mob movement do not become history or pay builder-tool stack detection costs.
- Client controllers and diagnostic commands still require an operator-level permission set on dedicated servers. Integrated singleplayer capture is allowed to keep local build history and live undo responsive from the first edit.
- New live capture sessions are also limited to explicit builder-driven sources. Whole-dimension sessions now seed a causal chunk envelope from those root edits, then capture per-chunk session baselines lazily as compact chunk snapshot payloads only when a chunk inside that envelope first needs stabilization.
- First-touch whole-dimension tracking no longer samples the live world block-by-block. The server thread copies loaded chunk section palettes and real block-entity tags once, queues async baseline persistence, and returns to normal capture immediately.
- For whole-dimension workspaces, fluid spread and falling blocks no longer append directly into the draft. They only re-mark chunks inside that causal envelope as dirty, and `SessionStabilizationService` later rebuilds the final chunk diff by comparing compact chunk snapshots instead of walking the world through `level.getBlockState()`.
- Save, amend, recovery, restore, branch-switch, and undo/redo completion paths that need the live capture draft marshal snapshot/freeze/consume/discard/adjust work onto the Minecraft server thread before touching loaded chunks or mutable capture state.
- Secondary explosion, fire, growth, block-update, and mob sources are still gated by the active session envelope so one explicit edit does not pull unrelated far-away cave settling into the same draft.
- Block and entity changes are aggregated into an in-memory recovery draft immediately, then flushed asynchronously through the capture-maintenance executor and journaled while the session is active. Stabilization skips unchanged draft flushes after comparing the live buffer fingerprint, keeping repeated dirty-chunk reconciliation from rewriting the same recovery file every few seconds.
- Shutdown freeze reuses the last matching asynchronously persisted recovery draft when the live buffer fingerprint is already durable, so exiting the world does not rewrite large unchanged drafts after the idle flush has completed.
- `ProjectService` bootstraps a shared `WorldOriginInfo` manifest and a metadata-backed `WORLD_ROOT` version for new dimension workspaces. The manifest is schema v2 and includes a conservative Lumi creation marker plus datapack and generator fingerprints.
- `ProjectArchiveService` owns UI-driven zip import/export for stable project history. It delegates zip I/O to `ProjectArchiveRepository` and keeps the feature outside the save/restore tick path.
- `HistoryShareService` backs the `Import / Export` flow on top of the same archive format by exporting one branch lineage to `lumi-projects`, importing it back as a review project, listing available package zips, and deleting imported review projects after validating they belong to the same project lineage.
- `ShareScreenController` keeps history package import/export separate from Build History and Branches, only asks `VariantMergeService` for a combine preview when the user explicitly reviews one imported package, and moves that preview work through a small background cache so the screen does not block on storage scans.
- `ProjectCleanupService` builds a conservative cleanup policy from current version metadata and active operation state, then delegates file deletion to `ProjectCleanupRepository`.
- `VersionService` stores new versions as patch-first history, supports amend-on-head, isolates in-progress operation drafts from live capture, and inserts checkpoint snapshots by policy.
- Preview generation now queues lightweight request files on the server side and fulfills them later through the client-side `PreviewCaptureCoordinator`.
- `RestoreService` prefers direct same-lineage patch replay, including shared branch-base ancestors and `WORLD_ROOT` ancestor restores, exposes a lightweight restore plan summary for `Initial` confirmation, falls back to tracked baseline chunks or checkpoint snapshot plus patch chain when direct replay is not valid, and resets the active branch head to the restored save on success without deleting detached saves. Direct patch replay and pending-draft rollback carry entity batches as well as block placements.
- `RestoreService` also supports same-lineage selected-area restore from save details, including a branch restoring a bounded area from the save it was branched from. It filters pending draft and direct patch block/entity changes to manual bounds, reads only intersecting v6 patch chunk frames when possible, applies prepared batches through the operation model, then writes a new `PARTIAL_RESTORE` version on the active branch while preserving pending draft changes outside the selected region.
- `VariantService` keeps one head pointer per variant.
- `VariantMergeService` turns an imported review project back into local history by finding a shared saved ancestor, grouping overlapping conflicts into chunk-connected review zones, and delegating merged version persistence to `VersionService`.
- `DiffService` reconstructs version-to-version changes from patch history.

The current history pipeline is intentionally split into:

- async preparation, compression, and decoding work away from the server tick
- bounded chunk-batch application on the server tick through `WorldOperationManager`, including adaptive block budgets and explicit block-entity/entity caps
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
- owo-lib is declared as a required Fabric dependency; `owo-sentinel` is included as a last-resort dependency warning helper.
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

- Keep the product wording builder-friendly. Prefer user-facing `branch` wording over `idea` or raw internal variant terms, and keep technical ids behind Advanced surfaces.
- Keep the mod usable through menus first. Commands are read-only diagnostics/help only.
- Preserve the singleplayer-first assumption unless a change explicitly expands runtime scope.
- When touching storage, prefer forward-only adjustments with simple legacy handling for the current local format.
- Preview generation now has a split responsibility: the server can queue preview capture requests in storage, while the client render path fulfills them later.
- Apply OOP and SOLID consistently. Favor small, focused collaborators with explicit responsibilities over utility-heavy procedural code.
- Keep business rules in domain services and models, Minecraft-specific side effects in adapter layers, and file I/O inside repositories.
- Treat documentation as part of the implementation. If a change alters data flow, storage, or user-visible behavior, update the docs before the work is considered done.
