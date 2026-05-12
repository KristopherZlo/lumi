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

The test client starts with `-Dlumi.debug=true`, `-Dlumi.startupProfile=true`, and `-Dlumi.loadLog=true` so runtime behavior, startup timings, and load spans are available immediately.

See [test-client.md](test-client.md) for details.

Run the automated test suite:

```powershell
.\gradlew.bat test
```

Run the unit coverage ratchet:

```powershell
.\gradlew.bat verifyCoverageRatchet
```

`verifyCoverageRatchet` generates the JaCoCo XML/HTML report and fails when line or branch coverage drops below `config/coverage-baseline.properties`. Refresh the baseline with `.\gradlew.bat updateCoverageBaseline` only after reviewing the coverage report.

This now includes regression checks for:

- undo-only item drops attached to live undo/redo without persisting into recovery drafts or saved versions
- delayed block events, scheduled ticks, and moving piston block entities preserving the builder action id for live undo/redo grouping, stale delayed callbacks being suppressed once that action is replayed by undo/redo, and old causal fallout being unable to promote itself above newer builder actions
- history rename, save soft-delete, branch soft-delete, tombstone filtering, and local branch merge behavior
- auto-checkpoint command classification for large `/fill` and `/clone` commands
- admin-only capture source permission gates and AutoCloseable context guard cleanup
- runtime Lumi region selection state, one-time selection-tool teaching, and selection-backed partial restore form filling
- compare/recent overlay spatial selection, cached section mesh behavior, and small/large/extreme overlay geometry paths
- textured preview fluid coordinate translation
- recent-action preview color selection for the next undo/redo target, shortcut catalog coverage, and clean active-head save-details compare routing
- container block-entity payload undo
- bounded connected-fluid replay tick scheduling
- commit graph layout on large histories
- detached commit visibility after a restore-style reset
- recovery draft isolation while save/amend operations run
- WorldEdit optional edit-session context wiring
- zip archive import/export for project history, with previews optional, recovery drafts excluded, bounded import, storage-id validation, and symlink rejection
- variant-scoped history package export/import, imported review package deletion, cached imported-variant merge planning, conflict zones, per-zone resolutions, and imported payload safety warnings
- conservative cleanup flow for orphaned snapshots/previews/cache and interrupted operation-draft recovery, including root containment and symlink-directory pruning safety
- material delta summarization on large diffs
- project tracking index membership checks, chunk-addressable patch selective reads, patch entity old/new chunk indexing, and snapshot chunk-list scans
- entity diff merge, entity policy filtering, safe projectile entity snapshot normalization, recovery/patch round-trips, and entity-only restore batches
- delayed entity spawn snapshots preserving spawn baselines and original live undo action ordering after later edits
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

Run the alpha release gate wrapper:

```powershell
.\scripts\run-alpha-release-check.ps1
```

The wrapper runs the unit coverage ratchet, server and client GameTests, structure-fixture mode, external-tool mode, crash-safety mode, runtime load comparison, and the crash harness unless explicitly skipped with script switches. The crash harness reports each failpoint as `current/total`; use `-CrashHarnessFailpoints` for a bounded local rerun of specific failpoints after a failure.

Run the idle startup-only client GameTests:

```powershell
.\scripts\run-baseline-idle-client.ps1
.\scripts\run-idle-client.ps1
```

CI can run the production client GameTest task. The task enables XVFB automatically on Linux CI and runs without XVFB on Windows local hosts:

```powershell
.\scripts\run-test-client.ps1 -GradleTasks runProductionClientGameTest
```

Run the integrated-world runtime regression suite from a local singleplayer save with cheats enabled:

```mcfunction
/lumi testing smoke
/lumi testing singleplayer
/lumi testing crash-safety
/lumi testing external-tools
```

The smoke command creates an archived temporary bounded project in an empty air volume above the player's chunk and drives the real bootstrap storage, pre-open checkpoint manifest and opt-in backup budget, snapshot content refs, section-indexed patch reads, save, undo/redo, amend, branch, compare, export, partial-restore, full-restore, integrity, and cleanup services through the server tick loop. The full command continues into gameplay interaction, performance, large-history, and bulk-apply diagnostics. Its gameplay phase covers adjacent block fallout, bulk block placement, block entities, deferred redstone and piston fallout, a closed redstone loop smoke mechanism, fluid placement, multi-block doors, oriented block states, crop/farmland states, openable blocks, item entities, non-player entity spawn and state/position updates, quick rollback of a saved entity update, a saved entity update followed by full restore, a water bridge placed through `ServerPlayer.gameMode.useItemOn`, preview fulfillment after saving that bridge, and a controlled TNT interaction with undo/redo. It then verifies that restoring the initial save rolls pending gameplay actions back to air while removing spawned entities. After the normal workflow budget checks, it runs a storage-backed large-history diagnostic that captures about 262k changed cells into a real main-branch save, captures a divergent 65k-cell branch save, restores the main save, restores the branch head, and verifies both restored block sets and active branch metadata. It then runs bulk apply diagnostics for dense rewrite-friendly 250k-cell batches, same-sized block-entity fallback batches, and a sparse direct-section sample with about 250k changed cells. The diagnostics preflight high-altitude target cells for air, skip unsafe scenarios instead of overwriting existing blocks, and write save/restore/apply durations plus fast-apply counters into the same test log. Structure-fixture diagnostics run in the focused `structures` mode and the alpha release wrapper; they include generated observer/sticky-piston rollback fixtures, including a closed observer pair on a vertical sticky piston, and assert that undo pulls observers home without duplicates, stray piston heads, or moving-piston placeholders. The commands report phase progress in chat, record pass/fail checks without stopping at the first failed assertion, and write a detailed log under `<world>/lumi/test-logs/`. The undo/redo action-scope performance budget allows the bounded mechanism/water replay fixture while still failing broad world replay.

`runClientGameTest` starts consistent singleplayer worlds for three Lumi client GameTests. Before the world-backed cases wait for render readiness, the harness teleports the spawned player onto a small barrier pad in the current chunk so the client camera does not spend the run falling through empty space. Test-client and client GameTest launchers also rewrite their run-directory sound categories to `0.0` before startup, which keeps local regression runs silent. The screen smoke test opens safe non-storage owo screens, clicks validation/back/cancel buttons, writes screenshots, and exercises storage-backed route section buttons through live client component fixtures so missing buttons, inactive-state regressions, failed action callbacks, and render crashes fail the task without forcing the client test harness to wait on integrated-server storage from an open screen. The overlay smoke test activates small compare/recent overlays through the live renderer, while also preparing large cached compare/recent meshes in client context so tiled coarse overlay geometry is covered without waiting on expensive screenshot capture. The runtime suite GameTest invokes the shorter `/lumi testing smoke` workflow automatically before taking its final smoke screenshot; the full destructive runtime path remains available through `LUMI_SINGLEPLAYER_TEST_MODE=full`, and focused structure fixtures remain available through `LUMI_SINGLEPLAYER_TEST_MODE=structure-fixtures` and the alpha release wrapper. `LUMI_SINGLEPLAYER_TEST_MODE=backup-stress` runs the client under `-Xmx1G` and replaces the runtime suite with a pre-open backup stress flow: create a save, remove Lumi's fresh-world marker, write 100k blocks across 400 chunks, measure exit, reopen through the alpha checkpoint screen with a 1024 MiB backup budget, create a Lumi world workspace, write another 100k blocks as a builder action, commit that change set to Lumi history, measure exit again, restore the raw pre-Lumi backup offline, reopen, and verify all 100k positions. Generated structure fixtures remain strict rollback checks, while saved `.nbt` structure fixtures are dynamic redstone diagnostics that verify player interaction and undo/redo operation flow but log exact snapshot drift from moving entities, falling blocks, carts, items, and redstone phase instead of failing on vanilla post-operation ticking. `runBaselineClientGameTest` starts a separate consistent singleplayer world with the small `lumi-baseline-gametest` action mod and explicitly removes Lumi's dev source-set from the launch config before startup. The baseline action mod runs the same broad gameplay surface without Lumi history capture, so load comparisons include player block break, adjacent fallout, bulk block placement, block entities, redstone, a closed redstone loop, fluids, multi-block and stateful blocks, crops, and entity lifecycle/state work rather than a client startup-only sample.

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
- separate load log: `-Dlumi.loadLog=true`, writing `logs/lumi-load.log` under the game run directory by default
- lighting/shadow diagnostics: `-Dlumi.lightLog=true`, writing `logs/lumi-light.log` by default. This is also enabled when `lumi.loadLog` is enabled. It records `light-refresh` scheduling, async check preparation, dirty chunk bounds, per-tick `checkBlock` drain counts, pre-barrier and post-barrier unsaved chunk marks, light-engine barrier waits, publish ticks, bounded server-stop light drains, and completion summaries for rejoin-only shadow bugs.
- restore/rollback block apply diagnostics: `-Dlumi.blockApplyLog=true`, writing `logs/lumi-block-apply.log` by default. This is also enabled when `lumi.loadLog` is enabled. It records restore/undo/redo/quick rollback preparation, preload ticks, chunk-level set/delete target counts, native/rewrite/direct apply steps, block-entity/entity steps, tick stop reasons, fallback summaries, and aggregate timings without logging every block.
- load log tuning flags: `-Dlumi.loadLog.slowMs=10`, `-Dlumi.loadLog.summarySeconds=30`, `-Dlumi.loadLog.top=12`, and `-Dlumi.loadLog.path=<path>`
- inspect `type="summary"` rows first to find the highest cumulative `area`/`name` pairs, then inspect nearby `type="span"` rows for slow individual calls and `type="operation-metrics"` rows for restore/undo/apply counters
- accepted capture sessions keep the first 32 per-mutation traces behind debug logging, while info level stays focused on buffer checkpoints, queued/completed maintenance work, and reconcile summaries
- whole-dimension stabilization now logs dirty-chunk reconcile summaries before draft flush/save/freeze, so startup diagnostics and reconcile summaries should be inspected together when ambient fallout looks suspicious. Delayed redstone and piston carriers restore their copied action context before they mutate the world, and stabilization writes their final settled chunk deltas into that same live undo/redo action when the action id is still authorized and the dirty chunk has stayed unchanged for the short tick-settle window. If that chunk still contains `moving_piston`, reconciliation requeues it instead of normalizing the animation cell into final air. Deferred piston and redstone sources can seed the session chunk baseline and pre-change cell corrections before their first block write when they are already inside an active session region and still carry a causal action id; fluid and falling-block sources can also seed that baseline inside the active region after their delayed callback has lost the action id. Fluid-driven neighbor callbacks remain fluid fallout so water-broken blocks undo with the water action. That delayed mechanism context is propagation-bounded, and secondary redstone/piston stabilization without a causal action id is skipped, so closed clocks and random ticks cannot keep appending world-settling noise to the working draft. Ambient fluids and falling blocks still cannot bootstrap capture or leave the active session region. During undo/redo replay, stale delayed piston callbacks with the replayed action id are suppressed instead of being held through exact-state guards on piston or observer cells. For piston, observer, moved-block, and live undo/redo diagnostics, enable debug tracing and inspect `history-action`, `capture-block`, `mechanism-replay`, and `mechanism-callback`; callback-level traces are global mechanism hooks and require `-Dlumi.debug=true`.
- prepared world operations keep their final fast-apply metrics available to runtime tests and also log them under `world-op` debug tracing, including prepare/preload/apply/light/total durations, max apply/preload tick times, native sections/cells, rewrite sections/cells, direct/fallback sections, section packets, block-entity packets, deferred light checks, apply ticks, work-per-tick counters, light-drain ticks/duration, and fallback reasons. The `world-op-apply` debug category adds per-apply-tick budgets, stop reasons, chunk batch composition, section/chunk path timings, fallback summaries, loaded-chunk no-op prune ratios, and deferred light drain counts for restore/undo/redo speed diagnosis. The separate `lumi-block-apply.log` and `lumi-light.log` files are the preferred first stop for large restore bottlenecks and rejoin-only shadows because they isolate block mutation timing from light refresh timing. Apply uses explicit `NORMAL`, `HISTORY_FAST`, `DIAGNOSTIC_TURBO`, and `MAXIMUM` profiles: ordinary work keeps conservative limits, bulk diagnostics keep larger sparse direct-section and light-drain caps, and restore/recovery/merge/undo/redo/light-refresh now use the foreground `MAXIMUM` profile with larger apply, sync chunk-load, block-entity, entity, redstone, light, and preload budgets.
- client overlay diagnostics log overlay-key state, compare/pending/recent coordinator skip reasons, render callback health, cached surface/volume counts, section mesh upload counts, render-distance skips, and render failures under `overlay-input`, `overlay-render`, `compare-overlay`, `pending-overlay`, and `recent-overlay`
- compare, pending-draft, recent-action, and region-selection overlay geometry is cached into section-scoped GPU meshes. Overlay states rebuild CPU geometry only when the diff, pending draft revision, held recent-action revision, or selected bounds change; compare nearest-entry selection is owned by `CompareOverlaySpatialIndex`, and GPU buffer lifecycle is owned by `OverlayMeshBuffer`. Frame rendering reuses uploaded buffers and lazily uploads newly visible sections instead of rebuilding all vertices every frame. Large compare overlay activation and HUD workspace refreshes run on background workers, and over-cap compare/pending blobs are tiled into bounded short section-aligned boxes with a small surface offset instead of one unbounded primitive. Compare x-ray remains an explicit hold mode. Plain Lumi action hold shows the pinned recent action preview with the selected undo target colored red and the selected redo target colored green, falling back to cumulative unsaved draft changes when there is no live action preview.

## Repository layout

The codebase currently follows these top-level areas:

- `src/main/java/io/github/luma/domain`
  Product-facing models and services for projects, versions, variants, recovery, diff, preview, and integrity.
- `src/main/java/io/github/luma/storage`
  Save-file layout plus repositories for metadata, patches, snapshots, variants, and recovery.
- `src/main/java/io/github/luma/minecraft`
  Minecraft-specific capture, diagnostic command, and world-application code.
- `src/main/java/io/github/luma/integration`
  Optional integration contracts, typed capability reporting, stable WorldEdit session bridges, WorldEdit edit-session tracking, and fallback status plumbing for external builder tools.
- `src/client/java/io/github/luma/ui`
  owo-ui screens, controllers, overlays, and view-state records with router-driven navigation.

For the current architecture, responsibility boundaries, and runtime invariants, see [architecture.md](architecture.md).
For file-level routing before touching code, see [../modules.md](../modules.md).

## UI architecture

The current menu flow is centered around `ScreenRouter`, `LumaScreen`, and focused owo-ui route classes such as `ProjectScreen`, `SaveScreen`, `SaveDetailsScreen`, `CompareScreen`, `VariantsScreen`, and `ShareScreen`. Every in-game menu is code-driven owo-ui using `BaseOwoScreen`, `OwoUIAdapter`, `FlowLayout`, `ScrollContainer`, `Sizing`, `Insets`, and `Surface`.

Controllers own service access and loading logic. Screens keep transient UI state and route lifecycle, while larger routes can delegate repeated layout sections to screen-section builders such as `CompareScreenSections`, `ProjectScreenSections`, focused Save details partial-restore components, and `ShareMergeReviewSection`. `WorkspaceHudCoordinator` drives the optional top-right HUD overlay and action-bar feedback independently of screen lifetime, refreshing workspace snapshots off the client tick, using a slower idle refresh cadence and a short cadence while an operation is active. `ActionBarMessagePresenter` keeps operation and quick feedback text short, colored, and low-noise; operation percentages are text-only because `WorldOperationBossBarManager` owns the native Minecraft bossbar for real progress. `OnboardingScreen` is a non-pausing modal wizard gated by client-only config so it appears once per installation before the first normal workspace screen, unless recovery must be shown first. `OnboardingTour` owns the shared 9-step guided flow and hold state: welcome, open, workspace spotlights for `Save build` and `See changes`, an in-world edit, controlled Undo/Redo, shortcut table, and finish. `OnboardingSpotlightOverlay` dims the workspace and leaves a live cut-out around the taught component, while `ClientOnboardingFlowCoordinator` owns the no-screen break-block step, waits for the synthetic Undo/Redo operation to finish, keeps the final world state visible for one second, and reopens the tour afterward. `HotkeyInfoScreen` renders `LumiShortcutCatalog` so the `Left Alt+I` help route and onboarding copy stay aligned with registered key roles. `ClientContextualHelpService` stores dismissed in-use hint ids in the same client config, while `ContextualHelpPresenter` keeps screens responsible only for where a hint appears and when to rebuild after dismissal. `LumiRegionSelectionTeachingController` uses that same dismissed-hint state for the one-time wooden-sword actionbar hint. Shortcut pages read the live remapped `KeyMapping` values, render bundled pixel key sprites from `src/main/resources/assets/lumi/textures/gui/dark_buttons`, and require a hold before advancing; unbound required shortcuts show `Open Controls` and `Skip`, `LumiShortcutSuppressingScreen` prevents normal in-world Lumi shortcut execution while modal tour cards are open, `ClientOnboardingFlowCoordinator` suppresses the same shortcuts while a no-screen onboarding preview is active, the top-right close control is the explicit escape hatch, and Escape is consumed while the tour is open.
Escape is handled by `LumaScreen` through the current route's `onClose()` behavior, so nested screens return to their parent route the same way their Back button does.
`ProjectHomeScreenController`, `SaveDetailsScreen`, `VariantsScreenController`, and `ShareScreenController` are lightweight summary loaders. They avoid diff, material, cleanup, diagnostics, heavy archive validation, and merge-preview work on open and poll fresh operation snapshots every 10 client ticks so conflicting mutation actions unlock without reopening the screen. Save details uses stored version `ChangeStats` for its change summary instead of rebuilding per-block diffs; full added/removed/changed and material summaries are loaded only through the explicit See Changes route. Save details also polls for async preview metadata so newly rendered PNGs appear in place. Import / Export lists lightweight zip summaries from the game-root `lumi-projects` folder, while combine previews are requested only by explicit review actions and cached by imported package and target branch while the screen is open.
Save and save-details screens now use dedicated narrow view-state records rather than the old shared project tab state. The old tab view builders are removed instead of being kept as hidden UI scaffolds.
User-visible strings are shipped through Minecraft language files under `src/main/resources/assets/lumi/lang`. Keep new UI keys in `en_us.json`, update each shipped locale, and run `LanguageFilesTest` or `.\gradlew.bat test` so missing keys and broken `%s`/backtick tokens are caught before packaging. Contextual hints use paired `luma.contextual_hint.<id>.title` and `.body` keys. UI textures live under `src/main/resources/assets/lumi/textures`; onboarding key icons use lowercase filenames that mirror the bundled dark button sprite sheets.

owo-lib is the only menu toolkit in this branch. Lumi declares it as a Fabric dependency for Minecraft `1.21.11`.

Current UX assumptions:

- pressing `U` opens the current dimension workspace directly
- pressing the Lumi action button plus `S` opens the standalone Quick save dialog while no client screen is open; the default chord is `Left Alt+S`, both keys are remappable in Minecraft Controls, and it saves through the current dimension workspace without opening Build History
- pressing the Lumi action button plus `I` opens the hotkey information table while no client screen is open; the default chord is `Left Alt+I`, and the table reflects current remapped controls
- pressing the Lumi action button plus `Z` starts undo for the latest tracked action in the current dimension workspace while no client screen is open, and also from the pause screen or detected Axiom tool screens so a builder can leave an Axiom tool and immediately undo the captured edit. The default action button is `Left Alt`, and remapping it changes this chord too. While the chord is active in the world, `LumiShortcutInteractionGate` suppresses vanilla use/attack input so the keypress cannot also click a lever, button, block, or item target.
- pressing the Lumi action button plus `Y` starts redo for the latest tracked action in the current dimension workspace under the same screen policy as undo; if undo and redo are pressed in the same tick, undo wins and redo must be pressed again. The same in-world interaction suppression applies to redo. WorldEdit and FAWE actors route through native `/undo` and `/redo`, with Lumi capture suppressed during the command and the pending draft adjusted afterward. Axiom actors and `axiom-*` action ids replay through Lumi by default so Lumi applies the same action it selected from its own history stack.
- pressing `R` quick-rolls unsaved work back to the active branch head while no client screen is open; the Lumi action button no longer turns `R` into a separate return-before-restore shortcut
- nearby short-lived secondary fallout can join the latest tracked undo/redo action instead of disappearing from the live action stack
- undo/redo drains already-dirty stabilization chunks before selecting an action, force-loading those pending chunks first so the same action works after the player walks away from the edit area. Reconciled fluid, contact-created source blocks, falling-block deltas, redstone block updates, and piston fallout can then join live undo/redo after the tick-settle window. Dirty chunks that still contain `moving_piston` remain pending, so undo/redo waits instead of selecting a snapshot taken mid-animation. If a project still has pending dirty chunks after that drain, undo/redo reports that redstone or piston fallout is still settling instead of selecting a partial action; client shortcut requests keep their queued intent and retry on later ticks instead of surfacing a generic operation failure. Block events, scheduled ticks, and moving piston block entities carry the action id that created them, so redstone and piston fallout can attach to the exact triggering action instead of depending only on the nearby time/radius window. Water-driven neighbor fallout keeps the fluid source instead of becoming an unactioned block update, so undo restores blocks broken by water spread. When repeated mechanism toggles dirty the same chunk before that settle window drains, the latest causal context replaces the older pending owner, which keeps the selected undo action aligned with the settled piston state. Mechanism reconciliation also records tracked cells that returned to the baseline during the action, which keeps sticky-piston pull/retract undo from dropping the pulled block. Mechanism carriers do not propagate that id indefinitely; once it expires, further piston/block-update pulses are treated as ambient and skipped by stabilization.
- undo-only item drops from explosion, fluid, falling-block, and related block-update fallout are removed on undo and respawned on redo, while durable drafts and saved versions keep only the block/entity history they should persist
- undo/redo applies the selected stored states with client-visible but side-effect-suppressed block update flags. Redstone power/source transitions then drain scoped neighbor updates after the stored state has been written, so lever and button rollback settles adjacent circuitry without enabling broad placement physics or piston event cascades during replay. The bounded exact replay guard only holds derived volatile redstone/mechanism states, not player input controls, and it is released when a new explicit player/tool mutation begins.
- redstone and mechanism state is durable final block state: lever/button `powered`, wire `power`, lamp `lit`, openable `open`, repeater/comparator properties, piston base `extended`, settled `piston_head`, and moved blocks are saved. Stabilization requeues chunks that still expose `moving_piston`, and the apply preparers complete settled piston base/head companions before replay, recover a retracted base from a transient moving-piston base target, replace normalized transient air at the expected head when an extended base requires it, clear an old head when a known extended base retracts, and deliberately never infer a new piston base from a head-only placement; only short-lived `moving_piston` animation state is normalized away
- pressing `H` hides or shows the current compare overlay without clearing the diff data
- opening See Changes with a resolved pair or pressing a highlight preset starts background diff loading; the world highlight enables once that diff is ready, with large overlay geometry prepared in the background before activation
- comparing against `current` refreshes the active world highlight automatically every few client ticks while that highlight is visible, except active overlays above the 50,000-block detailed-render cap which keep their initial snapshot to avoid client stalls
- holding the Lumi action button shows the compare highlight through blocks while held, with `Left Alt` as the default remappable control
- compare overlays and recent-action previews build their cached section meshes from exposed changed blocks, so dense fills still have visible surfaces even when the camera is nearest to internal changed blocks; compare overlays above 50,000 changed blocks silently collapse to bounded tiled low-alpha volume blobs by change type and render through blocks without requiring the action-button x-ray hold. Overlay rendering caps the number of visible sections drawn each frame and prioritizes the nearest sections, while lazy uploads stay bounded separately.
- holding the same remappable action button while compare highlight is inactive shows the latest live undo and redo actions with a fading temporary overlay. That preview is chosen when the hold starts and stays pinned until release so new block edits made while holding the key do not replace it. The next undo target is red, the next redo target is green, and older recent actions remain orange. The cumulative pending draft overlay remains the fallback when no live action preview is visible. Actions render translucent exposed sides with thicker outlines at small and giant sizes, with preparation off the client tick and section-lazy upload during rendering.
- the dashboard is a project picker outside the focused workspace menu
- the workspace home screen is Build History: a compact owo-ui window with `Save build` as the only primary action, one-click `See changes`, and recent saves. Branch management stays in the sidebar, while the recent-save branch picker performs a real branch switch through `VariantService`.
- settings include a HUD section that can hide the persistent top-right Lumi panel without disabling action-bar operation progress, and settings persist immediately on valid field changes
- Import / Export and Settings are first-level workspace sidebar routes, while `More` keeps storage cleanup, manual highlight, the interactive history graph, and raw references in one place
- save composition, save details, branch management, import/export combine review, cleanup, diagnostics, and More tools now have dedicated surfaces instead of sharing one overloaded project page
- save composition no longer renders quick name suggestion buttons; manual naming stays unchanged
- the wooden-sword Lumi region selector is client runtime state scoped to project and dimension, with a one-time actionbar teaching hint, loaded-chunk raycast targeting, `corners` and `extend` modes, Lumi action button + scroll mode switching, Lumi action button + right click clear, and a world-render bounds overlay that is visible only while the wooden sword is held. Save details can copy that selection into partial restore bounds even after the overlay is hidden.
- partial restore is exposed from Save details `More`. The form accepts either the current Lumi selection or manually edited bounds, supports `Only selected area` and `Everything except selection`, then requires a preview before apply. Successful partial restores are recorded into the runtime undo/redo stack as one action after the `PARTIAL_RESTORE` version is written.

## History architecture

Current runtime history behavior:

- `HistoryCaptureManager` still records explicit tracked block changes inside project bounds, including TNT ignition, explosions, and selected mob block mutations, while still excluding Lumi's own restore applications. Durable working-draft buffers, session states, dirty flags, recovery draft persistence, and live-draft flush fingerprints are owned through `WorkingDraftSessionManager`; project metadata loading and membership matching are owned by `TrackedProjectCatalog`; accepted-mutation traces and progress summaries are owned by the working-draft diagnostics registry, so the mixin-facing facade does not directly manage every lifecycle, catalog, or diagnostics map.
- Primed TNT keeps the builder action context that created it until the delayed `Level.explode`/`ServerLevel.explode` call, so the later block damage is recorded into the same draft and live undo action instead of being treated as unrelated ambient explosion noise.
- `WorldMutationCapturePolicy` captures direct player/tool state changes, defers redstone block-update and piston fallout to chunk stabilization, and rejects only unsupported or transient states. `PersistentBlockStatePolicy` preserves settled redstone and piston state, including `piston_head`, and normalizes only `moving_piston` out of new snapshot and restore apply paths. During internal restore, undo, and redo apply, the piston mixin suppresses vanilla extension checks so stored piston bases, heads, and moved blocks remain the only source of replayed piston state.
- Authorized player-root actions are mirrored by `LiveUndoRedoActionRecorder` into `UndoRedoHistoryManager`, which keeps a bounded per-project in-memory action stack for live undo/redo and the recent-action overlay. This stack is separate from the durable working draft: save/amend does not clear it, restart does not restore it, and full restore/branch switching clears it because those actions describe an older world state. Integrated singleplayer worlds and dedicated servers use the same admin/operator permission gate before explicit actions can enter capture or live undo/redo.
- Automatic dimension project bootstrap is limited to explicit builder-driven sources. Ambient fluid, fire, block-update, falling-block, and mob mutations cannot create a workspace or enter an existing session on world load by themselves. Ambient growth without a causal action id also cannot enter an already-active draft, so random vine/crop/amethyst ticks do not keep increasing pending changes while the player stands still. Bonemeal growth uses a causal secondary frame when it runs under a player/tool action, and captured action-scoped growth is stored with hidden block-change visibility so it remains replayable but does not affect builder-facing summaries, overlays, or preview framing.
- Optional external builder tools use explicit mutation sources where available. WorldEdit sessions are observed through a guarded `EditSessionEvent` extent wrapper when WorldEdit is present; the wrapper records old/new block transitions directly because some WorldEdit bulk paths do not surface through Minecraft `Level#setBlock`, and it serializes block-entity NBT only when the old or new state can hold a block entity. `WorldEditSessionBridge` may also expose stable public-API session capabilities for selection, clipboard availability, and clipboard/schematic format reporting. FAWE can inherit only those WorldEdit-compatible capabilities when the same API classes are present; otherwise it stays on fallback capture. Lumi also recognizes WorldEdit, FAWE, Axiom, Axion, AutoBuild, SimpleBuilding, Effortless Building, Litematica, and Tweakeroo stack frames at block and entity mutation boundaries, with a guard so lower-level block fallbacks do not duplicate higher-level records. Dedicated servers only allow external-tool capture, bootstrap, and auto-checkpoints when the source is tied to an already authorized player context or an integration passes an explicit access grant; unknown external actors remain untrusted. Axiom can also override an active player mutation source when Axiom-assisted break tools reach Minecraft's normal player block paths, so the edit keeps one Axiom action id instead of fragmenting into many player actions. Axiom block-buffer packet applies are still captured before Axiom mutates chunk sections directly with the same lazy block-entity rule, but packet capture now batches old-state sampling, no-op filtering, mutation context, and live undo recording so large Axiom fills do not pay the full per-block action-stack path. Captured Axiom actions replay through Lumi by default. The `-Dlumi.experimentalAxiomNativeUndoRedo=true` JVM flag exists only for targeted native-dispatch diagnostics because Axiom's selection/tool history can move independently from Lumi's selected action. With `-Dlumi.debug=true`, new known-tool action ids are logged under `external-tool-detect`.
- Lower block fallbacks also honor an already-active vanilla Lumi source frame. If a piston or redstone mechanism writes through `LevelChunk#setBlockState` or `LevelChunkSection#setBlockState` instead of `Level#setBlock`, the mutation can enter dirty-chunk stabilization and join the triggering undo/redo action as final settled mechanism state while it still carries a causal action id. Delayed block events and scheduled ticks restore the saved causal context before those fallbacks run and consume a bounded propagation depth to avoid recording self-sustaining clocks as ongoing builder edits. Moving piston block entity carriers preserve the existing piston action id without increasing that depth, so multi-step piston doors keep their final moved-block fallout attached to the originating action. Late redstone/piston callbacks in an already-pending dirty mechanism chunk may reuse that chunk's latest causal action context. Fluid and falling-block callbacks may continue actionlessly only inside the active session region, and random-growth sources still require their own causal action. Reconciled live undo/redo payloads are computed from the draft replacement transition, so closing a mechanism that returns blocks to the chunk baseline still records the moved blocks needed for undo/redo instead of leaving a lever-only action.
- Non-direct partial restore must keep reconstruction work off the tick thread. Use `PartialRestoreTargetStatePlanner` for cross-lineage target-state planning so snapshot reads, baseline reads, LZ4 decompression, and patch decoding finish before `WorldOperationManager` receives prepared apply batches. `OUTSIDE_SELECTED_AREA` must stay finite by using project bounds or tracked whole-dimension chunks.
- Entity capture is centralized through `HistoryCaptureManager.recordEntityChange`. Generic server-side hooks capture non-player entity spawn, removal, focused transform updates, tags, custom names, visibility, glowing state, and full NBT loads when the operation is player-rooted or comes from a known external builder stack. Player entity attack/interact packets enter the same mutation scope as block use, so killing a restored entity creates the next undo/redo action instead of falling through to older block history. `EntityMutationCapturePolicy` allows explicit player and known-tool entity history for all non-player entity types, rejects sources that cannot record entity history before NBT serialization, and rejects `SYSTEM` source changes so chunk-load entity data and ordinary mob movement do not become history. Minecart movement and dispenser-style projectile spawns caused by mechanism ticks can join an active action as `BLOCK_UPDATE` fallout, but they still cannot bootstrap capture by themselves. Undo/redo entity updates restore an existing same-type entity in place when possible instead of discarding it before replay, so movement updates do not lose minecarts during UUID reuse. Unknown-stack external fallback inspection remains scoped to builder-facing persistent entity types so ordinary mob ticks do not pay stack-detection costs.
- Player-rooted item entity edits are normal durable entity changes. Item entities produced by explosion, fluid, falling-block, and nearby block-update fallout are captured as undo-only related entities. They are deliberately excluded from recovery drafts and version payloads, so correcting an edit clears the dropped items without turning transient drops into durable project history. Entity snapshots keep the full saved entity NBT, including tick fields such as item age, motion, and pickup delay, so respawn/update replay does not partially reset entity state.
- Client controllers, diagnostic commands, capture, live undo/redo, and automatic checkpoints require the admin/operator-level permission set in both integrated singleplayer and dedicated-server worlds.
- New live capture sessions are also limited to explicit builder-driven sources. Whole-dimension sessions seed a causal chunk envelope from those root edits, then keep secondary capture bounded to that active region or chunks currently loaded for a player. Deferred block stabilization still requires the originating action id, while nearby mechanism entity fallout can join the latest matching live undo action without bootstrapping durable capture by itself. Per-chunk session baselines are captured lazily as compact chunk snapshot payloads only when a chunk inside that active session region first needs stabilization.
- First-touch whole-dimension tracking no longer samples the live world block-by-block. The server thread copies loaded chunk section palettes, real block-entity tags, and entity snapshots once, queues async baseline persistence, and returns to normal capture immediately. Entity-triggered first touches apply the known old/new entity payload as a baseline override so a spawn, removal, or update is not duplicated into both the baseline snapshot and the patch diff.
- For whole-dimension workspaces, action-scoped fluid spread and falling blocks no longer append directly into the draft. They only re-mark chunks inside the active session region as dirty, and `SessionStabilizationService` later rebuilds the final chunk diff by comparing compact chunk snapshots instead of walking the world through `level.getBlockState()`. Captured and deferred mutations retain per-position baseline corrections from their pre-change state, including deferred mechanism cells in the same root chunk as the input action, and secondary deferred sources can capture a clean session chunk baseline before their first block write when an active session already owns the region. Delayed fluid and falling-block ticks can reuse the pending dirty chunk action context after their scheduled callback loses the current stack frame; if no action context remains but the tick is still inside the active region, the reconciled fluid/falling-block delta uses the wider time-limited related live undo grouping window. This keeps redstone, target, dispenser, water-break, and piston fallout from turning lazy baseline snapshots into the apparent original state. Replay restores derived redstone property states exactly; it notifies neighbors for signal-source block changes and for restored player input signal changes, and a short bounded exact-state guard suppresses stale replay callbacks for derived volatile states while excluding player controls plus active piston/observer mechanism participants and yielding to new explicit edits. Fluid-related replay placements also get a short post-replay fluid suppression envelope so scheduled water/lava ticks queued before undo/redo/restore cannot immediately refill blocks that history just restored. Live undo/redo asks that same stabilization path to drain currently dirty chunks before it selects the next action; if a dirty diff lands on a block already tracked by an explicit builder edit, the live undo action records only the transition from that explicit current state to the settled fallout state, not from the chunk baseline.
- Save, amend, recovery, restore, branch-switch, and undo/redo completion paths that need the working draft marshal snapshot/freeze/consume/discard/adjust work onto the Minecraft server thread before touching loaded chunks or mutable capture state. Save/amend queue their world operation before consuming the live draft or writing `operation-draft.bin.lz4`, so large draft isolation is reported as operation progress instead of blocking the initiating screen. Save/amend still consume the live working draft before reading any persisted fallback, so active async WAL flushes are drained before recovery storage is inspected. Save/amend consume only the working draft and leave the volatile undo/redo stack intact; if a new draft appears while the async save runs, the draft base is rebased from the consumed head to the newly saved version. Full and partial restore queue their operation before lineage loads, capture freeze, journal writes, and decode planning. Branch creation only writes metadata for an existing save/head and intentionally does not freeze or consume the active draft. Branch switching restores through an explicit target branch so a branch created from a main-line save stays active after the restore operation completes.
- Current-run working drafts are marked separately from interrupted persisted drafts. Opening the workspace during the same session shows pending changes normally, while reopening after an interrupted previous session routes to Recovery.
- Soft-deleted saves stay hidden from normal history but remain inspectable in More -> Deleted saves.
- Secondary explosion, fire, block-update, falling-block, fluid, and mob sources are gated by the active session region, so one explicit edit does not pull unrelated cave settling or water into a new draft. Direct growth capture additionally requires a causal action id, and deferred redstone/piston stabilization also requires one. Redstone and piston can fall back to the latest pending dirty-chunk action context for late mechanism callbacks; fluid and falling-block stabilization may proceed without that context only inside an existing active session region, and random growth cannot. Related live undo/redo entity fallout is bounded by the nearby action join policy. That region includes the causal envelope and chunks currently loaded for a player.
- Block and entity changes are aggregated into an in-memory working draft immediately, then flushed asynchronously through a dedicated draft-flush executor and journaled while the session is active. Baseline chunk persistence uses a separate bounded writer pool, so large baseline backlogs do not delay recovery WAL writes, save-time drains are not serialized behind one chunk writer, and idle/recovery/discard/rebase working-draft lifecycle paths wait for the recovery draft to be current without draining baseline maintenance on the server tick. The default pool uses half the available processors capped at four writers, with `-Dlumi.capture.baselineThreads=N` available for diagnostics up to eight writers. Stabilization skips unchanged draft flushes after comparing the working-draft fingerprint, keeping repeated dirty-chunk reconciliation from rewriting the same recovery file every few seconds; the flush interval does not throttle the reconciliation itself.
- Shutdown freeze reuses the last matching asynchronously persisted recovery draft when the working-draft fingerprint is already durable, so exiting the world does not rewrite large unchanged drafts after the idle flush has completed.
- `ProjectService` bootstraps a shared `WorldOriginInfo` manifest and a metadata-backed `WORLD_ROOT` version for new dimension workspaces. The manifest is schema v2 and includes a conservative Lumi creation marker plus datapack and generator fingerprints.
- Fresh worlds created while Lumi is installed write `<world>/lumi/created-with-lumi.marker` before server-side world-origin bootstrap. Existing pre-Lumi worlds without a completed checkpoint show a client alpha gate before opening, write a manifest-only checkpoint by default before world entry, and store the acknowledgement at `<world>/lumi/pre-mod-backup/alpha-backup-warning-acknowledged.txt` after the checkpoint succeeds. A positive `lumi.preModBackup.maxMiB` budget enables opt-in compressed chunk payload capture with visible Minecraft experience-bar progress. The vanilla Edit World restore action delegates to `WorldInitialBackupRestoreService`, writes backed-up raw chunks into region files when payloads exist, and keeps Lumi project commits on disk.
- `ProjectArchiveService` owns UI-driven zip import/export for stable project history. It delegates zip I/O to `ProjectArchiveRepository` and keeps the feature outside the save/restore tick path.
- `HistoryShareService` backs the `Import / Export` flow on top of the same archive format by exporting one branch lineage to `lumi-projects`, importing it back as a review project, listing available package zips, and deleting imported review projects after validating they belong to the same project lineage.
- `ShareScreenController` keeps history package import/export separate from Build History and Branches, only asks `VariantMergeService` for a combine preview when the user explicitly reviews one imported package, and moves that preview work through a small background cache so the screen does not block on storage scans.
- `ProjectCleanupService` builds a conservative cleanup policy from current version metadata and active operation state, then delegates file deletion to `ProjectCleanupRepository`.
- `VersionService` stores new versions as patch-first history, supports amend-on-head without dropping entity diffs, isolates in-progress operation drafts from live capture, and delegates checkpoint snapshot policy and chunk collection to `VersionSnapshotPlanner`.
- `AutoCheckpointService` saves an existing pending draft as `AUTO_CHECKPOINT` before large vanilla `/fill` or `/clone` commands and before WorldEdit/Axiom external action ids only when the workspace setting is enabled and the actor has the same `LumaAccessControl` grant used by other mutating Lumi features. Tool-triggered checkpoints require an explicit external access grant. The setting is off by default. It does nothing when no draft exists, deduplicates by external action id, and logs skipped checkpoints while another Lumi world operation is active.
- `HistoryEditService` owns rename, save soft-delete, branch soft-delete, branch-head movement for safe deleted heads, and tombstone persistence through `HistoryTombstoneRepository`.
- Preview generation now queues lightweight request files on the server side and fulfills them later through the client-side `PreviewCaptureCoordinator`, which backs off after empty scans to avoid idle storage polling. UI preview textures are invalidated when the backing PNG timestamp or size changes.
- `RestoreService` prefers direct linear patch replay for ancestor/descendant restores and `WORLD_ROOT` ancestor restores, exposes a lightweight restore plan summary for `Initial` confirmation, includes pending recovery-draft chunks in that summary even when the selected save is already the active head, appends exact `INITIAL` snapshot state only for changed block positions produced by pending-draft rollback or direct patch replay, appends exact `WORLD_ROOT` baseline state only for changed positions in touched chunks with tracked baselines, carries that exact-root marker through apply progress for runtime-budget verification, falls back to tracked baseline chunks or checkpoint snapshot plus patch chain when direct replay is not valid, and resets the active branch head to the restored save on success without deleting detached saves. Divergent branch-head restores use the target snapshot/patch-chain path instead of sparse reverse/forward replay so derived redstone and fluid fallout is reset to the target branch state. Exact root snapshot/baseline reads stay sparse, and entity target-state reads use patch entity-id and old/new chunk indexes when available, so direct root and boundary-crossing entity restores do not scan full snapshots or full patch payloads on the common path. Full restores write `recovery/last-restore-return.json` so quick return can restore the pre-restore version; dirty drafts are checkpointed first so the return target is exact. Full restore and full quick rollback append authoritative placed-entity replacement batches for the affected chunks so extra restored/display entities are removed and duplicate UUID spawns become idempotent. Full restore completion clears stale live undo/redo stacks because old builder actions no longer describe the restored world. `QuickRollbackService` freezes the current dirty draft, verifies it belongs to the active head, applies only the inverse draft through the `quick-rollback` history-fast prepared apply path, discards the draft, and records a fresh live undo/redo action so normal undo/redo can toggle the rollback without moving the saved branch head. Explicit return-before-restore remains a UI-triggered hard restore path, not a keyboard chord. Restores from a save on another branch plan from the current live branch and change active-branch metadata only after apply completion. Persisted block/entity changes and snapshot entity payloads are read by repositories, prepared by Minecraft-layer batch preparers, completed for paired blocks such as beds, doors, tall plants, and settled piston base/head companions, and then applied through the operation model; repositories do not assemble tick-runtime batches.
- `RestoreService` also supports same-lineage selected-area restore from save details, including a branch restoring a bounded area from the save it was branched from. It filters pending draft and direct patch block/entity changes to manual bounds, reads only intersecting chunk-addressable patch frames when possible, uses old/new entity chunk metadata to include move-out and move-in records, applies prepared batches through the operation model, then writes a new `PARTIAL_RESTORE` version on the active branch while preserving pending draft changes outside the selected region.
- `VariantService` keeps one head pointer per variant.
- `VersionLineageService` owns reachable-version filtering, common ancestor lookup, ancestor checks, shared imported ancestor validation, and ancestor-to-head path resolution for restore, diff, and merge workflows.
- `VariantMergeService` turns imported review projects and local branches back into local history by finding a shared saved ancestor, grouping overlapping conflicts into chunk-connected review zones through `MergeConflictZoneBuilder`, carrying non-conflicting entity changes, rejecting unresolved entity conflicts explicitly, and delegating merged version persistence to `VersionService` with `VersionKind.MERGE`. Start requests stay caller-thread-light; merge planning, safety scanning, conflict resolution, and block/entity batch preparation execute inside the world-operation executor.
- `DiffService` reconstructs version-to-version block and entity changes from patch history through the shared lineage path helpers. It first compares section fingerprint sequences from patch metadata and reads only sections whose indexed patch sequence differs; missing or legacy indexes fall back to full patch reads.

The current history pipeline is intentionally split into:

- async preparation, compression, and decoding work away from the server tick
- bounded chunk preload and chunk-batch application on the server tick through `WorldOperationManager`, including adaptive block budgets, explicit preload chunk caps, fast-profile-only synchronous chunk reacquire after preload misses, fast-profile mixed rewrite/native/sparse tick work, profile-specific minimum sparse/direct time floors after adaptive downscale, and explicit block-entity/entity caps
- operation-scoped block-state palette decoding during preparation, so repeated palette tags are decoded once before tick-time apply starts
- large live undo/redo preparation builds `LumiSectionBuffer` batches directly after decode, avoiding an intermediate decoded-placement list before section-native classification
- Lumi-owned cursor-sliced section-native commits for dense prepared sections, chunk-level direct loaded-section commits for sparse batches, with vanilla fallback and batched section/client block-entity updates. Section packets include same-section neighbor cells for render invalidation, so unchanged adjacent faces refresh after low-level section writes. The sparse direct chunk path coalesces heightmap maintenance to one final update per changed chunk column after the direct section writes finish, and the fast accessor scans a column once for all present heightmap types so tall sparse deletes do not rescan the same heightmap column repeatedly. Proven-safe sparse deletes to air avoid block-entity removal and POI update calls only when both current and target states are non-BE and non-POI; they still write the section, queue light checks, update heightmaps, mark the chunk unsaved, and send section packets. Prepared world operations drain queued redstone neighbor updates and exact replay in the main action; queued light checks move into an automatic `light-refresh` follow-up action that inherits parent preload tickets, preloads dirty chunks, calls `checkBlock`, marks only loaded touched chunks unsaved while logging missing chunks, waits on dirty-chunk threaded light-engine barriers, marks those loaded dirty chunks unsaved again after the barrier, and leaves a short server-tick publication window before completion without calling `runLightUpdates()`. During server shutdown, an active `light-refresh` gets a bounded drain attempt before operation state is failed, cleared, and all chunk tickets are released. Direct non-operation calls still apply the same checks immediately. Sections with at least 64 prepared cells are considered dense enough for the native loop path, and full sections or sections with at least 256 prepared cells may use the atomic container rewrite path when preflight proves there are no block entities or POI states. Full-section rewrite builds a fresh replacement container from all target cells, while partial dense rewrite still copies the live container before patching changed cells.
- operation progress based on block placements, block-entity tail work, and entity operations, so entity-only restore, undo/redo, and recovery batches do not complete early
- operation snapshots that surface progress to the UI instead of pretending a long task finished immediately
- fast prepared apply operations expose a `PRELOADING` stage before mutation; it uses temporary chunk tickets, releases them on completion or failure, and records preload duration, ticks, loaded-before-apply chunks, newly loaded chunks, missed-at-apply fallback counts, and max preload tick time. If preload completes within the current server tick, apply may begin in that same tick instead of waiting for the next tick. After preload, only fast profiled apply ticks may synchronously reacquire a chunk that was part of the operation when `getChunkNow` still misses, and direct, native, and rewrite paths share that contract. Loaded history-fast chunks are also pruned once before apply so cells that already match live state do not consume tick-time replay budget; no-op exact replay is retained only for forced replay companions, cells adjacent to real updates, and chunk-edge cells that may depend on cross-chunk neighbors. Lightweight undo/redo completion runs on the server tick after replay so stack movement and pending-draft adjustment do not need a background-thread round trip.
- optional debug tracing for capture, save, restore, recovery, compare, HUD, and background operations
- debug apply metrics include rewrite sections/cells, native sections/cells, preload pipeline counters, deferred redstone updates, deferred light checks, section packets, and rewrite/native fallback reasons so slow dense actions can be traced to safety rejection, redstone or light maintenance, block entities, unloaded chunks, or sparse distribution.

Current safe bulk-apply baseline from the 2026-04-30 singleplayer diagnostics run is dense rewrite `fill=1402 ms` and `delete=943 ms`, block-entity fallback `fill=944 ms` and `delete=950 ms`, and sparse direct `fill=8773 ms` and `delete=11650 ms`, with `failedChecks=0` and `nonAirAfterDelete=0`. Future speed work should report prepare, preload, apply, light finalize, verification, and total scenario timings so chunk loading or verification cost cannot be hidden outside the apply metric.

The 2026-05-01 safe-acceleration verification run after fast-profile preload, chunk reacquire, sparse delete pruning, and fast-profile budget floors reported dense rewrite `fill=1293 ms` and `delete=1046 ms`, block-entity fallback `fill=1150 ms` and `delete=998 ms`, and sparse direct `fill=6309 ms` and `delete=7252 ms`, with `failedChecks=0`, `nonAirAfterDelete=0`, `missedAtApply=0`, and empty sparse fallback reasons. Sparse direct apply CPU time in that run was `548 ms` for fill and `997 ms` for delete; the remaining wall time is bounded tick scheduling, preload, final light drain, and runtime verification.

Current world-apply runtime types:

- `ChunkBatch`
- `SectionBatch`
- `EntityBatch`, including spawn, remove, and full-NBT update lists
- `LocalQueue`
- `GlobalDispatcher`
- `BatchState`

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
