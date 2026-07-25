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

Lumi is durable world history for Minecraft builders.

Save a good moment, explore another idea on a branch, compare the result, and
return safely without copying the whole world folder or learning Git.

> [!WARNING]
> Lumi is in alpha. Keep normal backups of worlds you care about.

## What Lumi Does

- Saves named versions of visible blocks, block entities, durable non-player
  entities, and player respawn positions.
- Creates branches for experiments without duplicating stored world data.
- Compares any two saved versions and highlights added, removed, and changed
  blocks in the world.
- Restores a whole version, a wooden-sword selection, or everything outside the
  selection.
- Keeps workspaces and colored work zones as lightweight views over one shared
  dimension history.
- Provides session Undo/Redo and Quick Restore for unsaved builder changes.
- Supports safe Amend, branch merge, soft-deleted history, tags, renaming,
  search, and isometric save previews.
- Imports and exports portable Lumi packages after inspection and confirmation.
- Recovers interrupted operations without sacrificing previously valid history.
- Integrates with normal Minecraft edits and supported WorldEdit and Axiom
  builder workflows.

Lumi is singleplayer-first. Integrated and dedicated servers use the same
server-authoritative operation path, revision checks, progress reporting, and
final verification.

## Quick Start

1. Install Lumi and Fabric API for Minecraft `1.21.11`.
2. Enter a world and follow the first-run builder tour.
3. Make a few changes and press `Alt+S` to save them.
4. Press `U` to open Lumi and browse the new version.
5. Create a branch before trying a risky idea, then compare or restore when you
   are ready.

Every initialized dimension starts with one protected **Initial** version, so
the original state remains available from the beginning of its Lumi history.

## Default Controls

| Key | Action |
| --- | --- |
| `U` | Open Lumi |
| `Alt+S` | Save the active workspace or zone |
| `Alt+Z` / `Alt+Y` | Undo / Redo the latest session action |
| `R` | Quick Restore unsaved changes to the active version |
| `H` | Show or hide the compare highlight |
| `Alt+I` | Open the hotkey guide |
| `Alt+1` … `Alt+0` | Switch to an assigned branch |
| Hold `Alt` | Preview pending work and enable Lumi action shortcuts |
| Wooden sword | Select restore bounds and edit active-zone cells |

All key bindings are remappable in Minecraft controls.

## Install

Download an alpha build from
[GitHub Releases](https://github.com/KristopherZlo/lumi/releases) and place it
in the `mods` folder together with Fabric API.

| Requirement | Version |
| --- | --- |
| Minecraft | `1.21.11` |
| Fabric Loader | `0.19.2` or newer compatible release |
| Fabric API | `0.141.3+1.21.11` or newer compatible release |
| Java | `21` |

For a dedicated server, install Lumi on the server and on clients that use its
screens and overlays. Server actions require operator permission; Survival-mode
access must also be enabled in Lumi settings.

WorldEdit and Axiom are optional. Lumi does not require either mod for ordinary
play.

## Reliability

Lumi stores only the state needed for exact recovery. Save captures the visible
world boundary; Restore prepares heavy work away from the server tick, applies
bounded batches, persists them through Minecraft storage, and reads the result
back before reporting success.

Published history uses immutable content-addressed objects, sparse Merkle trees,
hash-verified LZ4 packs, atomic refs, and operation journals. A failed or
interrupted operation keeps the last valid history intact and offers a safe
recovery path.

Lumi data is separate from vanilla chunk data:

```text
<world>/lumi/history/<dimension>/
```

Removing Lumi leaves a normal playable Minecraft world. V2 starts a new history
and does not import legacy patch-v9 or snapshot-v8 projects.

When there is no useful work, Lumi does no history processing. Long operations
remain incremental and observable, while their working memory is bounded by the
current batch instead of the entire world.

## For Developers

Use JDK 21.

```powershell
.\gradlew.bat build --no-daemon
.\gradlew.bat test --no-daemon
.\gradlew.bat runClient --no-daemon
```

The code is split by responsibility:

| Layer | Responsibility |
| --- | --- |
| `domain/model` | Value objects, persisted records, summaries, runtime state |
| `domain/service` | Save, Restore, Compare, branch, workspace, and zone rules |
| `minecraft/*` | Capture hooks, Minecraft adapters, tick-time application |
| `storage/*` | Object storage, serialization, refs, journals, and packages |
| `network` | Revision-checked client/server commands and immutable payloads |
| `client/ui/*` | Rendering, thin controllers, and immutable view state |

## License

GPL-3.0-only. See [LICENSE](LICENSE).
