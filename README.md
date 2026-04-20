# Luma

Luma is a Fabric mod for Minecraft 1.21.11 that gives builders a project-oriented history workflow for a selected build area. Instead of exposing Git terminology in the UI, Luma works with `projects`, `versions`, `variants`, `compare`, `restore`, and `recovery`.

The current milestone is **singleplayer-first**. It is designed and validated for local integrated-server worlds, with the primary workflow available through `owo-lib` screens and commands kept as a fallback layer.

## Current feature set

- Create a project from in-world bounds through the dashboard menu or `/luma create`.
- Track block edits inside the project bounds and store history as patch-first versions with checkpoint snapshots.
- Save manual versions with messages from the History tab or `/luma save`.
- Restore a target version, with a safety checkpoint created from pending tracked edits when enabled.
- Recover interrupted tracked edits through the Recovery screen.
- Create and switch named variants with independent heads.
- Compare two versions or variant heads in the Compare screen, including a client-side changed-block overlay mode.
- Generate lightweight preview images for saved versions and inspect preview metadata in the Preview tab.
- Inspect material deltas, change summaries, integration availability, and project integrity status from the project UI.

## Limitations

- Local singleplayer is the supported runtime target for this milestone.
- The Integrations tab currently reports adapter availability and fallback capabilities, but deep WorldEdit/Axiom hooks are not implemented yet.
- Import/export, schematic workflows, merge/conflict resolution, archive deletion, and partial restore are not implemented yet.
- The visual compare overlay highlights changed block positions in the live world. It does not yet render a richer 3D diff preview.

## Installation

1. Install Minecraft `1.21.11` with the Fabric Loader.
2. Install Fabric API.
3. Place the built `luma-<version>.jar` into your `mods` folder.

The repository is configured to ship Luma as a **single distributable jar**. Support libraries used by the mod itself are included through Loom jar-in-jar packaging.

## Build

```powershell
.\gradlew.bat build
```

The main distributable jar is written to `build/libs/`.

## Quick start

1. Open a local singleplayer world.
2. Press `U` to open the dashboard.
3. Create a project by entering a name and two corners.
4. Build inside the tracked bounds.
5. Open the project, go to `History`, enter a message, and save a version.
6. Use `Variants`, `Compare`, `Preview`, `Materials`, and `Recovery` from the project UI as needed.

## Documentation

- [User guide](docs/user-guide.md)
- [Commands](docs/commands.md)
- [Development](docs/development.md)
- [Storage format](docs/storage-format.md)
- [Test client profile](docs/test-client.md)

