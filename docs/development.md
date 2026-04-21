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
- material delta summarization on large diffs

Enable verbose runtime tracing for debugging:

- per workspace: `Settings -> Debug -> Debug logging`
- global JVM flag: `-Dlumi.debug=true`

## Repository layout

The codebase currently follows these top-level areas:

- `src/main/java/io/github/luma/domain`
  Product-facing models and services for projects, versions, variants, recovery, diff, preview, and integrity.
- `src/main/java/io/github/luma/storage`
  Save-file layout plus repositories for metadata, patches, snapshots, variants, and recovery.
- `src/main/java/io/github/luma/minecraft`
  Minecraft-specific capture, command, and world-application code.
- `src/main/java/io/github/luma/integration`
  Integration contracts and current availability/fallback status plumbing.
- `src/client/java/io/github/luma/ui`
  `Screen + Controller + ViewState` client UI implementation with router-driven navigation.

For the current architecture, responsibility boundaries, and runtime invariants, see [architecture.md](architecture.md).

## UI architecture

The current menu flow is centered around:

- `DashboardScreen`
- `CreateProjectScreen`
- `ProjectScreen`
- `RecoveryScreen`
- `CompareScreen`
- `SettingsScreen`

Controllers own service access and loading logic. Screens keep transient UI state. `LumaScreen` is the shared non-pausing base class for in-world menus. `WorkspaceHudCoordinator` drives the always-on HUD overlay and action-bar progress independently of screen lifetime.

Current UX assumptions:

- pressing `U` opens the current dimension workspace directly
- pressing `H` hides or shows the current compare overlay without clearing the diff data
- the dashboard is now secondary navigation from the workspace header
- the workspace screen is source-control-first: commit composer, commit graph, details/actions
- version actions such as restore, compare, and branch checkout live in the detail pane, not on graph nodes
- narrow screens use one scroll column instead of the two-pane workspace layout
- repeated rows are unframed inside section cards to avoid card-in-card clipping and wasted padding

## History architecture

Current runtime history behavior:

- `HistoryCaptureManager` records tracked block changes inside project bounds, including TNT ignition, explosions, falling-block start and landing changes, and selected mob block mutations, while still excluding Lumi's own restore applications.
- Changes are aggregated into a recovery draft and journaled while the session is active.
- `ProjectService` bootstraps a shared `WorldOriginInfo` manifest and a metadata-backed `WORLD_ROOT` version for new dimension workspaces. The manifest is schema v2 and includes a conservative Lumi creation marker plus datapack and generator fingerprints.
- `VersionService` stores new versions as patch-first history, supports amend-on-head, isolates in-progress operation drafts from live capture, and inserts checkpoint snapshots by policy.
- `RestoreService` prefers direct same-variant patch replay, including `WORLD_ROOT` ancestor restores, exposes a lightweight restore plan summary for `Initial` confirmation, falls back to tracked baseline chunks or checkpoint snapshot plus patch chain when direct replay is not valid, and resets the active branch head to the restored version on success without deleting detached versions.
- `VariantService` keeps one head pointer per variant.
- `DiffService` reconstructs version-to-version changes from patch history.

The current history pipeline is intentionally split into:

- async preparation, compression, and decoding work away from the server tick
- bounded chunk-batch application on the server tick through `WorldOperationManager`
- operation snapshots that surface progress to the UI instead of pretending a long task finished immediately
- optional debug tracing for capture, save, restore, recovery, compare, HUD, and background operations

Current world-apply runtime types:

- `ChunkBatch`
- `SectionBatch`
- `EntityBatch`
- `LocalQueue`
- `GlobalDispatcher`
- `BatchState`
- `BatchProcessor`
- `HistoryStore`

## Build and packaging notes

- Lumi is shipped as one distributable mod jar.
- Support libraries used by the mod are included through Loom jar-in-jar configuration.
- Fabric API remains an external required mod.

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
- Keep the mod usable through menus first. Commands are fallback tools.
- Preserve the singleplayer-first assumption unless a change explicitly expands runtime scope.
- When touching storage, prefer forward-only adjustments with simple legacy handling for the current local format.
- Apply OOP and SOLID consistently. Favor small, focused collaborators with explicit responsibilities over utility-heavy procedural code.
- Keep business rules in domain services and models, Minecraft-specific side effects in adapter layers, and file I/O inside repositories.
- Treat documentation as part of the implementation. If a change alters data flow, storage, or user-visible behavior, update the docs before the work is considered done.
