<p align="center">
  <img src="lumi-banner.png" alt="Lumi — version control for Minecraft" />
</p>

<h1 align="center">Lumi</h1>
<p align="center"><strong>Version control for Minecraft worlds.</strong></p>
<p align="center">
  <a href="https://github.com/KristopherZlo/lumi/releases/tag/v0.2.0-rc.2">Download</a>
  · <a href="https://github.com/KristopherZlo/lumi/issues">Report an issue</a>
  · <a href="docs/developer/README.md">Developer docs</a>
</p>

Lumi lets you save your Minecraft world, try changes on separate branches, and return to an earlier version. You can keep these versions without copying the world folder.

Each save covers the active workspace or zone. A workspace without bounds covers **the active dimension**.

> [!WARNING]
> **Lumi 0.2.0 RC2 is a pre-release.** Keep independent backups of worlds you care about.

## How it works

Press `Alt+S` to save the current state under a name. Lumi tracks blocks, block entities, supported persistent non-player entities, and player respawn positions. Each dimension starts with a protected **Initial** version.

Lumi does not record biome changes. Restore preserves the current biomes.

Branches let you try a different build without losing the original. You can switch branches, compare saved versions in the world, or merge branches. Compare marks added blocks in **green**, removed blocks in **red**, and changed blocks in **amber**. When merging conflicting changes, the **incoming branch takes priority**; changes made only on the current branch remain.

Restoring a version can affect the entire saved state or just part of it. Use a **wooden sword** to select an area with left-click and right-click, then restore that area or everything outside it. For recent edits, use session Undo/Redo; `Alt+R` restores unsaved changes to the active saved version, and you can undo that action if needed.

Lumi also has colored zones, version previews, tags, search, and import/export packages.

## Installation

### Published RC2

The published [0.2.0 RC2 release](https://github.com/KristopherZlo/lumi/releases/tag/v0.2.0-rc.2) is built for Minecraft **1.21.11**.

| Requirement | Version |
| --- | --- |
| Minecraft | `1.21.11` |
| Fabric Loader | `0.19.2` or a newer compatible version |
| Fabric API | `0.141.3+1.21.11` or a newer compatible version |
| Java | `21` |

Install Fabric Loader, then place the [Lumi JAR](https://github.com/KristopherZlo/lumi/releases/download/v0.2.0-rc.2/lumi-0.2.0-rc.2.jar) and Fabric API in your `mods` folder. **Axiom and WorldEdit are optional.**

### Current branch builds

The current branch builds four Lumi JARs for six Minecraft versions:

| Minecraft | Lumi JAR suffix | Java |
| --- | --- | --- |
| `1.21.11` | `+mc1.21.11.jar` | `21` |
| `26.1`, `26.1.1`, `26.1.2` | `+mc26.1.jar` | `25` |
| `26.2` | `+mc26.2.jar` | `25` |
| `26.3` | `+mc26.3.jar` | `25` |

The three `26.1` releases share one Lumi JAR. Use matching Fabric API, Axiom, and WorldEdit versions for your game. Keep only one Lumi JAR in each instance.

Building all four JARs requires JDK 21 and JDK 25. Use the [build script](scripts/build-supported-artifacts.ps1) with `-RunBuild`.

## Default controls

| Shortcut | Action |
| --- | --- |
| `U` | Open Lumi |
| `Alt+S` | Save |
| `Alt+Z` / `Alt+Y` | Undo / Redo |
| `Alt+R` | Restore unsaved changes |
| `H` | Show or hide Compare highlights |
| `Alt+I` | Open the hotkey guide |
| `Alt+1`–`Alt+0` | Switch to an assigned branch |
| Hold `Alt` | Preview unsaved work and use action shortcuts |

With a wooden sword equipped, Undo/Redo applies to selection edits instead of world edits. Put it away to undo building actions. Shortcuts can be reassigned in Minecraft Controls, including ones that overlap with Axiom.

In builds from this branch, Lumi clears pending Axiom edits and Axiom's Undo/Redo history before Undo, Redo, or Restore. This prevents queued Axiom work from changing the restored world.

## Multiplayer

Lumi stores history on the server, including in singleplayer. Everyone in a dimension shares the same active workspace and branch, so switching branches or restoring a version changes the world for **everyone**, not just the player who requested it.

On a dedicated server, install Lumi on the server and on clients that use its menus or highlights. Server actions require operator permission, and Lumi's settings must allow Survival access when applicable.

## Recovery

Lumi keeps recovery data for interrupted operations and verifies restored world data before reporting success. If a Restore is interrupted, **keep Lumi installed until recovery finishes**. RC2 still has incomplete verification for some large Restores and multiplayer failure cases; see the [release notes](https://github.com/KristopherZlo/lumi/releases/tag/v0.2.0-rc.2).

## Development

Build instructions, architecture, tests, and storage details are in the [developer documentation](docs/developer/README.md).

## License

[GPL-3.0-only](LICENSE).
