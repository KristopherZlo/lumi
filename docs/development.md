# Development

## Environment

- Minecraft target: `1.21.11`
- Loader: Fabric
- Java target: `21`
- Build tool: Gradle with Fabric Loom

If the local shell defaults to an older JDK, point `JAVA_HOME` at a JDK 21 installation before running Gradle. Gradle will fail before compilation on Java 11.

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

- undo-only item drops attached to live undo/redo without persisting into recovery drafts or saved versions
- history rename, save soft-delete, branch soft-delete, tombstone filtering, and local branch merge behavior
- auto-checkpoint command classification for large `/fill` and `/clone` commands
- runtime Lumi region selection state and selection-backed partial restore form filling
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
- entity-preserving amend, imported-branch merge projection, version diff projection, and entity-only operation progress
- lightweight home, variants, and share controller loading plus operation-state normalization
- fast world-apply section packet masks, direct-section eligibility fallback reasons, and apply metric summaries
- shipped language files containing every English UI key while preserving format and code tokens

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

The command creates an archived temporary bounded project in an empty air volume above the player's chunk and drives the real save, undo/redo, amend, branch, compare, export, partial-restore, full-restore, gameplay interaction, integrity, and cleanup services through the server tick loop. Its gameplay phase covers adjacent block fallout, bulk block placement, block entities, redstone updates, fluid placement, multi-block doors, oriented block states, crop/farmland states, openable blocks, item entities, a builder-relevant entity spawn, a water bridge placed through `ServerPlayer.gameMode.useItemOn`, preview fulfillment after saving that bridge, and a controlled TNT interaction with undo/redo. It then verifies that restoring the initial save rolls pending gameplay actions back to air while removing spawned entities. It reports phase progress in chat, records pass/fail checks without stopping at the first failed assertion, and writes a detailed log under `<world>/lumi/test-logs/`.

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
- prepared world operations log fast-apply metrics under `world-op` debug tracing, including native sections/cells, rewrite sections/cells, direct/fallback sections, section packets, block-entity packets, light checks, and fallback reasons. Restore, recovery, merge, and undo/redo labels use the high-throughput budget with explicit native-cell and rewrite-section caps.
- client overlay diagnostics log overlay-key state, compare/recent coordinator skip reasons, render callback health, selected surface counts, fill-pass face/vertex/alpha/render-type details, and render failures under `overlay-input`, `overlay-render`, `compare-overlay`, and `recent-overlay`
- compare and recent-action overlay geometry is drawn as immediate `END_MAIN` world-render quads/lines so fill buffers are flushed in the same callback instead of relying on the shared world `MultiBufferSource`

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
For file-level routing before touching code, see [../modules.md](../modules.md).

## UI architecture

The current menu flow is centered around `ScreenRouter`, `LumaScreen`, and focused owo-ui route classes such as `ProjectScreen`, `SaveScreen`, `SaveDetailsScreen`, `CompareScreen`, `VariantsScreen`, and `ShareScreen`. Every in-game menu is code-driven owo-ui using `BaseOwoScreen`, `OwoUIAdapter`, `FlowLayout`, `ScrollContainer`, `Sizing`, `Insets`, and `Surface`.

Controllers own service access and loading logic. Screens keep transient UI state and route lifecycle, while larger routes can delegate repeated layout sections to screen-section builders such as `CompareScreenSections`, `ProjectScreenSections`, focused Save details partial-restore components, and `ShareMergeReviewSection`. `WorkspaceHudCoordinator` drives the optional top-right HUD overlay and action-bar feedback independently of screen lifetime, using a slower idle refresh cadence and a short cadence while an operation is active. `ActionBarMessagePresenter` keeps operation and quick feedback text short, colored, and low-noise; it only renders the compact ASCII progress bar for larger active operations. `OnboardingScreen` is a non-pausing modal wizard gated by client-only config so it appears once per installation before the first normal workspace screen, unless recovery must be shown first. `OnboardingTour` owns the shared page and hold state so the flow can continue as a modal overlay inside `ProjectScreen` after the open-workspace shortcut. Shortcut pages read the live remapped `KeyMapping` values, render bundled pixel key sprites from `src/main/resources/assets/lumi/textures/gui/dark_buttons`, and require a 0.8-second hold before advancing; the top-right close control is the explicit escape hatch, and Escape is consumed while the tour is open.
Escape is handled by `LumaScreen` through the current route's `onClose()` behavior, so nested screens return to their parent route the same way their Back button does.
`ProjectHomeScreenController`, `SaveDetailsScreen`, `VariantsScreenController`, and `ShareScreenController` are lightweight summary loaders. They avoid diff, material, cleanup, diagnostics, heavy archive validation, and merge-preview work on open and poll fresh operation snapshots every 10 client ticks so conflicting mutation actions unlock without reopening the screen. Save details also polls for async preview metadata so newly rendered PNGs appear in place. Import / Export lists lightweight zip summaries from the game-root `lumi-projects` folder, while combine previews are requested only by explicit review actions and cached by imported package and target branch while the screen is open.
Save and save-details screens now use dedicated narrow view-state records rather than the old shared project tab state. The old tab view builders are removed instead of being kept as hidden UI scaffolds.
User-visible strings are shipped through Minecraft language files under `src/main/resources/assets/lumi/lang`. Keep new UI keys in `en_us.json`, update each shipped locale, and run `LanguageFilesTest` or `.\gradlew.bat test` so missing keys and broken `%s`/backtick tokens are caught before packaging. UI textures live under `src/main/resources/assets/lumi/textures`; onboarding key icons use lowercase filenames that mirror the bundled dark button sprite sheets.

owo-lib is the only menu toolkit in this branch. Lumi declares it as a Fabric dependency for Minecraft `1.21.11`.

Current UX assumptions:

- pressing `U` opens the current dimension workspace directly
- pressing the Lumi action button plus `S` opens the standalone Quick save dialog while no client screen is open; the default chord is `Left Alt+S`, both keys are remappable in Minecraft Controls, and it saves through the current dimension workspace without opening Build History
- pressing the Lumi action button plus `Z` starts undo for the latest tracked action in the current dimension workspace while no client screen is open; the default action button is `Left Alt`, and remapping it changes this chord too
- pressing the Lumi action button plus `Y` starts redo for the latest tracked action in the current dimension workspace while no client screen is open; if undo and redo are pressed in the same tick, undo wins and redo must be pressed again. WorldEdit and FAWE actors route through native `/undo` and `/redo`, with Lumi capture suppressed during the command and the pending draft adjusted afterward; Axiom actors or `axiom-*` action ids replay through Lumi so capability edits such as bulldozer, fast place, replace, infinite range, tinker, and angel place undo the exact states Lumi captured instead of calling Axiom's own undo stack.
- nearby short-lived secondary fallout can join the latest tracked undo/redo action instead of disappearing from the live action stack
- undo/redo drains already-dirty whole-dimension stabilization chunks before selecting an action, so reconciled fluid, contact-created source blocks, and falling-block deltas can join the latest nearby undo/redo action when they settle inside the same time/radius window without discarding redo that came from the last undo
- undo-only item drops from explosion, fluid, falling-block, and related block-update fallout are removed on undo and respawned on redo, while durable drafts and saved versions keep only the block/entity history they should persist
- undo/redo applies the selected stored states with client-visible but side-effect-suppressed block update flags, so restored blocks do not trigger immediate redstone neighbor updates or placement physics during the replay itself
- runtime-only redstone state flips and piston animation states do not become live undo/redo actions
- pressing `H` hides or shows the current compare overlay without clearing the diff data
- opening See Changes with a resolved pair or pressing `Compare` enables the world highlight immediately for that diff
- comparing against `current` refreshes the active world highlight automatically every few client ticks while the overlay data is present, except very large active overlays which keep their initial snapshot to avoid client stalls
- holding the Lumi action button shows the compare highlight through blocks while held, with `Left Alt` as the default remappable control
- compare overlays and small recent-action previews build their render selection from exposed changed blocks, so dense fills still have visible surfaces even when the camera is nearest to internal changed blocks; very large compare diffs collapse to merged low-alpha volume blobs by change type
- holding the same remappable action button while compare highlight is inactive shows the latest 10 undo actions with a fading temporary overlay; small actions render translucent exposed sides with thicker outlines, while dense actions collapse into merged low-alpha volume blobs prepared off the client tick. Holding the action button plus redo previews redo actions.
- the dashboard is a project picker outside the focused workspace menu
- the workspace home screen is Build History: a compact owo-ui window with `Save build` as the only primary action, one-click `See changes`, recent saves, and `Branches`; maintenance tools stay in the sidebar `More` route
- settings include a HUD section that can hide the persistent top-right Lumi panel without disabling action-bar operation progress, and settings persist immediately on valid field changes
- Import / Export and Settings are first-level workspace sidebar routes, while `More` keeps storage cleanup, manual compare, the interactive history graph, and raw references in one place
- save composition, save details, branch management, import/export combine review, cleanup, diagnostics, and More tools now have dedicated surfaces instead of sharing one overloaded project page
- save composition no longer renders quick name suggestion buttons; manual naming stays unchanged
- the wooden-sword Lumi region selector is client runtime state scoped to project and dimension, with loaded-chunk raycast targeting, `corners` and `extend` modes, Lumi action button + scroll mode switching, Lumi action button + right click clear, and a world-render bounds overlay. Save details can copy that selection into partial restore bounds.
- partial restore is exposed as a primary Save details action. The form accepts either the current Lumi selection or manually edited bounds, supports `Only selected area` and `Everything except selection`, then requires a preview before apply. Successful partial restores are recorded into the runtime undo/redo stack as one action after the `PARTIAL_RESTORE` version is written.

## History architecture

Current runtime history behavior:

- `HistoryCaptureManager` still records explicit tracked block changes inside project bounds, including TNT ignition, explosions, and selected mob block mutations, while still excluding Lumi's own restore applications. Active buffers, session states, dirty flags, and live-draft flush fingerprints are owned by `CaptureSessionRegistry`; project metadata loading and membership matching are owned by `TrackedProjectCatalog`; accepted-mutation traces and progress summaries are owned by `CaptureDiagnosticsRegistry`, so the mixin-facing facade does not directly manage every lifecycle, catalog, or diagnostics map.
- Primed TNT keeps the builder action context that created it until the delayed `ServerLevel.explode` call, so the later block damage is recorded into the same draft and live undo action instead of being treated as unrelated ambient explosion noise.
- `WorldMutationCapturePolicy` drops piston-source movement, transient piston blocks, and same-block runtime property flips such as `powered`, `lit`, and `extended`; `PersistentBlockStatePolicy` also normalizes `piston_head` and `moving_piston` out of new snapshot and restore apply paths.
- Authorized player-root actions are mirrored into `UndoRedoHistoryManager`, which keeps a bounded per-project action stack for live undo/redo and the recent-action overlay. In integrated singleplayer worlds, explicit builder actions can enter that stack immediately even when the permission frame is not yet operator-shaped; dedicated servers still require the permission gate.
- Automatic dimension project bootstrap is limited to explicit builder-driven sources. Ambient fluid, fire, growth, block-update, and mob mutations cannot create a workspace on world load by themselves.
- Optional external builder tools use explicit mutation sources where available. WorldEdit sessions are observed through a guarded `EditSessionEvent` extent wrapper when WorldEdit is present; the wrapper records old/new block transitions directly because some WorldEdit bulk paths do not surface through Minecraft `Level#setBlock`, and it serializes block-entity NBT only when the old or new state can hold a block entity. Lumi also recognizes WorldEdit, FAWE, Axiom, Axion, AutoBuild, SimpleBuilding, Effortless Building, Litematica, and Tweakeroo stack frames at block and entity mutation boundaries, with a guard so lower-level block fallbacks do not duplicate higher-level records. Axiom can also override an active player mutation source when Axiom-assisted break tools reach Minecraft's normal player block paths, so the edit keeps one Axiom action id instead of fragmenting into many player actions. Axiom block-buffer packet applies are still captured before Axiom mutates chunk sections directly with the same lazy block-entity rule, and captured Axiom actions replay through Lumi undo/redo rather than Axiom's own undo stack. With `-Dlumi.debug=true`, new known-tool action ids are logged under `external-tool-detect`.
- Entity capture is centralized through `HistoryCaptureManager.recordEntityChange`. Generic server-side hooks capture entity spawn, removal, focused transform updates, tags, custom names, visibility, glowing state, and full NBT loads when the operation is player-rooted or comes from a known external builder stack. `EntityMutationCapturePolicy` limits player-originated entity history and unknown-stack external fallback inspection to builder-relevant persistent entities, rejects sources that cannot record entity history before NBT serialization, and rejects `SYSTEM` source changes so chunk-load entity data and ordinary mob movement do not become history or pay builder-tool stack detection costs.
- Item entities produced by explosion, fluid, falling-block, and nearby block-update fallout are captured as undo-only related entities. They are deliberately excluded from recovery drafts and version payloads, so correcting an edit clears the dropped items without turning transient drops into durable project history.
- Client controllers and diagnostic commands still require an operator-level permission set on dedicated servers. Integrated singleplayer capture is allowed to keep local build history and live undo responsive from the first edit.
- New live capture sessions are also limited to explicit builder-driven sources. Whole-dimension sessions now seed a causal chunk envelope from those root edits, then capture per-chunk session baselines lazily as compact chunk snapshot payloads only when a chunk inside that envelope first needs stabilization.
- First-touch whole-dimension tracking no longer samples the live world block-by-block. The server thread copies loaded chunk section palettes, real block-entity tags, and entity snapshots once, queues async baseline persistence, and returns to normal capture immediately. Entity-triggered first touches apply the known old/new entity payload as a baseline override so a spawn, removal, or update is not duplicated into both the baseline snapshot and the patch diff.
- For whole-dimension workspaces, fluid spread and falling blocks no longer append directly into the draft. They only re-mark chunks inside that causal envelope as dirty, and `SessionStabilizationService` later rebuilds the final chunk diff by comparing compact chunk snapshots instead of walking the world through `level.getBlockState()`. Live undo/redo asks that same stabilization path to drain currently dirty chunks before it selects the next action.
- Save, amend, recovery, restore, branch-switch, and undo/redo completion paths that need the live capture draft marshal snapshot/freeze/consume/discard/adjust work onto the Minecraft server thread before touching loaded chunks or mutable capture state. Branch creation only writes metadata for an existing save/head and intentionally does not freeze or consume the active draft. Branch switching restores through an explicit target branch so a branch created from a main-line save stays active after the restore operation completes.
- Current-run live drafts are marked separately from interrupted persisted drafts. Opening the workspace during the same session shows pending changes normally, while reopening after an interrupted previous session routes to Recovery.
- Soft-deleted saves stay hidden from normal history but remain inspectable in More -> Deleted saves.
- Secondary explosion, fire, growth, block-update, and mob sources are still gated by the active session envelope so one explicit edit does not pull unrelated far-away cave settling into the same draft.
- Block and entity changes are aggregated into an in-memory recovery draft immediately, then flushed asynchronously through a dedicated draft-flush executor and journaled while the session is active. Baseline chunk persistence uses a separate executor, so large baseline backlogs do not delay recovery WAL writes. Stabilization skips unchanged draft flushes after comparing the live buffer fingerprint, keeping repeated dirty-chunk reconciliation from rewriting the same recovery file every few seconds.
- Shutdown freeze reuses the last matching asynchronously persisted recovery draft when the live buffer fingerprint is already durable, so exiting the world does not rewrite large unchanged drafts after the idle flush has completed.
- `ProjectService` bootstraps a shared `WorldOriginInfo` manifest and a metadata-backed `WORLD_ROOT` version for new dimension workspaces. The manifest is schema v2 and includes a conservative Lumi creation marker plus datapack and generator fingerprints.
- `ProjectArchiveService` owns UI-driven zip import/export for stable project history. It delegates zip I/O to `ProjectArchiveRepository` and keeps the feature outside the save/restore tick path.
- `HistoryShareService` backs the `Import / Export` flow on top of the same archive format by exporting one branch lineage to `lumi-projects`, importing it back as a review project, listing available package zips, and deleting imported review projects after validating they belong to the same project lineage.
- `ShareScreenController` keeps history package import/export separate from Build History and Branches, only asks `VariantMergeService` for a combine preview when the user explicitly reviews one imported package, and moves that preview work through a small background cache so the screen does not block on storage scans.
- `ProjectCleanupService` builds a conservative cleanup policy from current version metadata and active operation state, then delegates file deletion to `ProjectCleanupRepository`.
- `VersionService` stores new versions as patch-first history, supports amend-on-head without dropping entity diffs, isolates in-progress operation drafts from live capture, and inserts checkpoint snapshots by policy.
- `AutoCheckpointService` saves an existing pending draft as `AUTO_CHECKPOINT` before large vanilla `/fill` or `/clone` commands and before WorldEdit/Axiom external action ids only when the workspace setting is enabled. The setting is off by default. It does nothing when no draft exists, deduplicates by external action id, and logs skipped checkpoints while another Lumi world operation is active.
- `HistoryEditService` owns rename, save soft-delete, branch soft-delete, branch-head movement for safe deleted heads, and tombstone persistence through `HistoryTombstoneRepository`.
- Preview generation now queues lightweight request files on the server side and fulfills them later through the client-side `PreviewCaptureCoordinator`, which backs off after empty scans to avoid idle storage polling. UI preview textures are invalidated when the backing PNG timestamp or size changes.
- `RestoreService` prefers direct same-lineage patch replay, including shared branch-base ancestors and `WORLD_ROOT` ancestor restores, exposes a lightweight restore plan summary for `Initial` confirmation, includes pending recovery-draft chunks in that summary even when the selected save is already the active head, appends the initial snapshot after pending-draft rollback for exact `INITIAL` restores, carries that exact-initial marker through apply progress for runtime-budget verification, falls back to tracked baseline chunks or checkpoint snapshot plus patch chain when direct replay is not valid, and resets the active branch head to the restored save on success without deleting detached saves. Restores from a save on another branch plan from the current live branch and change active-branch metadata only after apply completion. Persisted block/entity changes and snapshot entity payloads are read by repositories, prepared by Minecraft-layer batch preparers, completed for paired blocks such as beds and doors, and then applied through the operation model; repositories do not assemble tick-runtime batches.
- `RestoreService` also supports same-lineage selected-area restore from save details, including a branch restoring a bounded area from the save it was branched from. It filters pending draft and direct patch block/entity changes to manual bounds, reads only intersecting chunk-addressable patch frames when possible, applies prepared batches through the operation model, then writes a new `PARTIAL_RESTORE` version on the active branch while preserving pending draft changes outside the selected region.
- `VariantService` keeps one head pointer per variant.
- `VersionLineageService` owns reachable-version filtering, common ancestor lookup, ancestor checks, shared imported ancestor validation, and ancestor-to-head path resolution for restore, diff, and merge workflows.
- `VariantMergeService` turns imported review projects and local branches back into local history by finding a shared saved ancestor, grouping overlapping conflicts into chunk-connected review zones, carrying non-conflicting entity changes, rejecting unresolved entity conflicts explicitly, and delegating merged version persistence to `VersionService` with `VersionKind.MERGE`.
- `DiffService` reconstructs version-to-version block and entity changes from patch history through the shared lineage path helpers.

The current history pipeline is intentionally split into:

- async preparation, compression, and decoding work away from the server tick
- bounded chunk-batch application on the server tick through `WorldOperationManager`, including adaptive block budgets and explicit block-entity/entity caps
- operation-scoped block-state palette decoding during preparation, so repeated palette tags are decoded once before tick-time apply starts
- Lumi-owned cursor-sliced section-native commits for dense prepared sections, direct loaded-section commits for sparse batches, with vanilla fallback and batched section/client block-entity updates. Sections with at least 64 prepared cells are considered dense enough for the native loop path, and full sections or sections with at least 1024 prepared cells may use the atomic container rewrite path when preflight proves there are no block entities or POI states.
- operation progress based on block placements, block-entity tail work, and entity operations, so entity-only restore, undo/redo, and recovery batches do not complete early
- operation snapshots that surface progress to the UI instead of pretending a long task finished immediately
- optional debug tracing for capture, save, restore, recovery, compare, HUD, and background operations
- debug apply metrics include rewrite sections/cells, native sections/cells, light checks, section packets, and rewrite/native fallback reasons so slow dense actions can be traced to safety rejection, light maintenance, block entities, unloaded chunks, or sparse distribution.

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

Soft-deleted history is stored in `history-tombstones.json`. Tombstones hide saves and branches from normal UI and lineage without deleting the payload files.

## Commit policy

The repository keeps a strict implementation policy:

- initialize git before implementation work
- commit every 100-300 changed lines of code or earlier for a coherent vertical slice
- avoid mixing unrelated build, storage, UI, integration, and migration changes when they can stand alone
- update the affected documentation in the same change set whenever behavior, storage, or architecture changes

The current repo also ships that policy in [commit-policy.md](commit-policy.md).

## Coding conventions

- Keep the product wording builder-friendly. Prefer user-facing `branch` wording over `idea` or raw internal variant terms, and keep technical ids behind More/details surfaces.
- Keep the mod usable through menus first. Commands are read-only diagnostics/help only.
- Preserve the singleplayer-first assumption unless a change explicitly expands runtime scope.
- When touching storage, prefer forward-only adjustments with simple legacy handling for the current local format.
- Preview generation now has a split responsibility: the server can queue preview capture requests in storage, while the client render path fulfills them later.
- Apply OOP and SOLID consistently. Favor small, focused collaborators with explicit responsibilities over utility-heavy procedural code.
- Keep business rules in domain services and models, Minecraft-specific side effects in adapter layers, and file I/O inside repositories.
- Treat documentation as part of the implementation. If a change alters data flow, storage, or user-visible behavior, update the docs before the work is considered done.
