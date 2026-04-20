# Lumi

Lumi is a Fabric mod for Minecraft 1.21.11 that gives builders a project-oriented history workflow for per-dimension workspaces. Instead of exposing Git terminology in the UI, Lumi works with `workspaces`, `versions`, `branches`, `compare`, `restore`, and `recovery`.

The current milestone is **singleplayer-first**. It is designed and validated for local integrated-server worlds, with the primary workflow available through `owo-lib` screens and commands kept as a fallback layer.

## Current feature set

- Create or reuse an automatic workspace for the current dimension from the dashboard.
- Track block edits in the current workspace and store history as patch-first versions with checkpoint snapshots.
- Save manual versions with messages from the History tab or `/lumi save`.
- Restore a target version, with a safety checkpoint created from pending tracked edits when enabled.
- Recover interrupted tracked edits through the Recovery screen.
- Create and switch named branches with independent heads.
- Compare two versions, branch heads, or the current live world in the Compare screen, including a client-side highlight mode.
- Generate lightweight preview images for saved versions and inspect preview metadata in version details.
- Inspect material deltas, change summaries, integration availability, and project integrity status from the workspace UI.

## Limitations

- Local singleplayer is the supported runtime target for this milestone.
- The Integrations tab currently reports adapter availability and fallback capabilities, but deep WorldEdit/Axiom hooks are not implemented yet.
- Import/export, schematic workflows, merge/conflict resolution, archive deletion, and partial restore are not implemented yet.
- The visual compare overlay highlights changed block positions in the live world. It does not yet render a richer 3D diff preview.

## Installation

1. Install Minecraft `1.21.11` with the Fabric Loader.
2. Install Fabric API.
3. Place the built `lumi-<version>.jar` into your `mods` folder.

The repository is configured to ship Lumi as a **single distributable jar**. Support libraries used by the mod itself are included through Loom jar-in-jar packaging.

## Build

```powershell
.\gradlew.bat build
```

The main distributable jar is written to `build/libs/`.

## Quick start

1. Open a local singleplayer world.
2. Press `U` to open the dashboard.
3. Open the current workspace for the dimension you are in.
4. Build in the world as usual.
5. In `History`, enter a message and save a version.
6. Use `Branches`, version details, `Compare`, `Recovery`, and `Settings` from the workspace UI as needed.

## Documentation

- [User guide](docs/user-guide.md)
- [Commands](docs/commands.md)
- [Development](docs/development.md)
- [Architecture](docs/architecture.md)
- [Maintenance guide](docs/maintenance-guide.md)
- [Storage format](docs/storage-format.md)
- [Test client profile](docs/test-client.md)
- [Agent instructions](AGENTS.md)
