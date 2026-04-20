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

## UI architecture

The current menu flow is centered around:

- `DashboardScreen`
- `CreateProjectScreen`
- `ProjectScreen`
- `RecoveryScreen`
- `CompareScreen`
- `SettingsScreen`

Controllers own service access and loading logic. Screens keep transient UI state. Project tabs are split into separate builder classes under `ui/tab`.

## History architecture

Current runtime history behavior:

- `HistoryCaptureManager` records block changes inside project bounds.
- Changes are aggregated into a recovery draft and journaled while the session is active.
- `VersionService` stores new versions as patch-first history and inserts checkpoint snapshots by policy.
- `RestoreService` reconstructs the target state from checkpoint snapshot plus patch chain.
- `VariantService` keeps one head pointer per variant.
- `DiffService` reconstructs version-to-version changes from patch history.

## Build and packaging notes

- Luma is shipped as one distributable mod jar.
- Support libraries used by the mod are included through Loom jar-in-jar configuration.
- Fabric API remains an external required mod.

## Storage references

Project data is stored per world under:

```text
<world>/luma/projects/<project>.mbp/
```

See [storage-format.md](storage-format.md) for the exact folder and file layout.

## Commit policy

The repository keeps a strict implementation policy:

- initialize git before implementation work
- commit every 100-300 lines or earlier for a coherent vertical slice
- avoid mixing unrelated build, storage, UI, integration, and migration changes when they can stand alone

The current repo also ships that policy in [commit-policy.md](commit-policy.md).

## Coding conventions

- Keep the product wording builder-friendly. Prefer `project`, `version`, `variant`, `compare`, `restore`, and `recovery`.
- Keep the mod usable through menus first. Commands are fallback tools.
- Preserve the singleplayer-first assumption unless a change explicitly expands runtime scope.
- When touching storage, prefer forward-only adjustments with simple legacy handling for the current local format.

