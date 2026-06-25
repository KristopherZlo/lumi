# Lumi

![Lumi banner](lumi-banner.png)

![Minecraft 1.21.11](https://img.shields.io/badge/Minecraft-1.21.11-5E7C16?style=for-the-badge)
![Fabric](https://img.shields.io/badge/Loader-Fabric-DBD0B4?style=for-the-badge)

Lumi adds build history to Minecraft.

Use it to save versions of a build, compare changes, branch ideas, restore old states, roll back mistakes, and recover interrupted work without copying the whole world folder every time.

Status: alpha. Keep normal backups.

## Features

- Project history for a dimension or selected build area
- Named saves with stats, previews, and restore data
- Quick save, amend latest save, restore, and quick rollback
- Partial restore for a selected region
- Compare overlays for saved versions, branches, and the current world
- Branches, local merges, import, and export
- Live undo/redo for recent tracked edits
- Crash recovery drafts and restore return points
- Best-effort capture for supported builder tools like WorldEdit, FAWE, and Axiom

## Default Controls

| Key | Action |
| --- | --- |
| `U` | Open Build History |
| `Left Alt+S` | Quick save |
| `Left Alt+Z` | Undo |
| `Left Alt+Y` | Redo |
| `R` | Quick rollback |
| `H` | Toggle compare overlay |
| `Left Alt+I` | Show Lumi hotkeys |
| Wooden sword | Select a region for partial restore |

Keybinds can be changed in Minecraft controls.

## Requirements

- Minecraft `1.21.11`
- Fabric Loader `0.19.2+`
- Fabric API `0.141.3+1.21.11`
- Java `21+`
- owo-lib `0.13.0+1.21.11`
- Cloth Config `21.11.153+`

## Client / Server

- Client: required
- Server: required on dedicated servers
- Singleplayer: primary target
- Integrated server: supported
- Dedicated server: available, but advanced
- Client-only install: no
- Server-only install: no

## Limits

- Lumi does not add blocks, items, mobs, biomes, or worldgen.
- Lumi does not replace normal world backups.
- Multiplayer collaboration is not the focus.
- Builder-tool support depends on the tool.
