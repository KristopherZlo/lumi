# Lumi

<p align="center">
  <img alt="Lumi banner" src="lumi-banner.png" />
</p>

<p align="center">
  <strong>Save the build. Try the idea. Undo the mistake.</strong>
</p>

<p align="center">
  <img alt="Minecraft 1.21.11" src="https://img.shields.io/badge/Minecraft-1.21.11-5E7C16?style=for-the-badge" />
  <img alt="Fabric" src="https://img.shields.io/badge/Loader-Fabric-DBD0B4?style=for-the-badge" />
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-1F6FEB?style=for-the-badge" />
  <img alt="GPL 3.0" src="https://img.shields.io/badge/License-GPL%203.0-2EA043?style=for-the-badge" />
</p>

Lumi keeps durable world history for Minecraft builders.

Save a good moment. Test another idea on a branch. Compare the result, then
return without copying the world or learning Git.

> [!WARNING]
> Lumi 0.2.0-rc.2 is a release candidate. Keep normal backups of worlds you care about.

## What Lumi Does

- Saves named versions of visible blocks, block entities, durable non-player
  entities, and player respawn positions.
- Creates branches for experiments without copying stored world data.
- Compares two saved versions and highlights added, removed, and changed blocks.
- Restores a complete version, a wooden-sword selection, or everything outside
  the selection.
- Shows workspaces and colored work zones as views of one shared dimension
  history.
- Gives builders session Undo/Redo and Quick Restore for unsaved changes.
- Supports safe Amend, branch merge, tags, renaming, search, and isometric save
  previews.
- Keeps soft-deleted history available for recovery.
- Imports and exports portable Lumi packages after you inspect and confirm them.
- Recovers interrupted operations and keeps earlier valid history safe.
- Integrates with normal Minecraft edits and supported WorldEdit and Axiom
  workflows.

Lumi uses one shared, server-authoritative world model in integrated and
dedicated servers. Multiple builders can work in the same dimension; its active
workspace, branch, and mutating history operation are global. Player-specific
live branches are not virtualized.

## Quick Start

1. Install Lumi and Fabric API for Minecraft `1.21.11`.
2. Open a world and follow the first-run builder tour.
3. Make a few changes and press `Alt+S` to save them.
4. Press `U` to open Lumi and browse the new version.
5. Create a branch before you test a risky idea.
6. Compare the result or restore an earlier version.

Each initialized dimension starts with one protected **Initial** version. This
version preserves the original state from the start of Lumi history.

## Default Controls

| Key | Action |
| --- | --- |
| `U` | Open Lumi |
| `Alt+S` | Save the active workspace or zone |
| `Alt+Z` / `Alt+Y` | Undo / Redo the latest session action |
| `Alt+R` | Quick Restore unsaved changes to the active version |
| `H` | Show or hide the compare highlight |
| `Alt+I` | Open the hotkey guide |
| `Alt+1` … `Alt+0` | Switch to an assigned branch |
| Hold `Alt` | Preview pending work and use Lumi action shortcuts |
| Wooden sword | Select restore bounds and edit active-zone cells |

While holding the wooden sword, Undo/Redo affects only your selection. An empty
selection history shows a message. Put the sword away to undo or redo world edits.

You can change all key bindings in Minecraft controls.

With the GUI HUD enabled, Compare shows a preparation percentage and bar.
The first world highlight appears when its visible parts are ready.
The preparation bar disappears when the highlight is ready or hidden.

Lumi screens use a fixed GUI scale of 2. Minecraft's GUI Scale setting does not
change their size. Resizing the window or entering fullscreen updates the
available layout space while keeping controls at the same pixel scale.

Zone Save, Amend, and See Changes use the zone's current pending state. Changes
elsewhere in the workspace do not enable these controls. They show a reason
while statistics load or the zone is clean; entity-only edits remain savable.
Zone See Changes displays only points inside that zone.

## Install

1. Download a release-candidate build from
   [GitHub Releases](https://github.com/KristopherZlo/lumi/releases).
2. Put Lumi and Fabric API in the `mods` folder.

| Requirement | Version |
| --- | --- |
| Minecraft | `1.21.11` |
| Fabric Loader | `0.19.2` or newer compatible release |
| Fabric API | `0.141.3+1.21.11` or newer compatible release |
| Java | `21` |

For a dedicated server, install Lumi on the server. Also install it on clients
that use Lumi screens and overlays. Server actions require operator permission.
Lumi settings must also allow access from Survival mode.

WorldEdit and Axiom are optional. Lumi does not require these mods for ordinary
play.

## World safety

Lumi saves the visible state of your build and verifies restored world data
before it reports success. Earlier valid history remains available if an
operation fails or the server stops.

A completed operation leaves a normal playable Minecraft world. If the server
stops during Restore, keep Lumi installed until recovery finishes.

Keep independent backups of important worlds, especially with release-candidate
builds. Lumi V2 starts a new history and does not import legacy patch-v9 or
snapshot-v8 projects.

## Developer documentation

For setup, the code map, workflow walkthroughs, and verification commands,
read the [developer documentation](docs/developer/README.md).

## License

GPL-3.0-only. See [LICENSE](LICENSE).
