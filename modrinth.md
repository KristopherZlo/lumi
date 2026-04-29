# Lumi

![Lumi banner](lumi-banner.png)

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-5E7C16?style=for-the-badge)
![Loader](https://img.shields.io/badge/Loader-Fabric-DBD0B4?style=for-the-badge)
![Java](https://img.shields.io/badge/Java-21-1F6FEB?style=for-the-badge)
![Environment](https://img.shields.io/badge/Environment-Singleplayer%20First-2EA043?style=for-the-badge)
[![Donate with PayPal](https://img.shields.io/badge/Donate-PayPal-00457C?style=for-the-badge&logo=paypal&logoColor=white)](https://www.paypal.com/donate/?hosted_button_id=CY7A2U64JWY4W)

Lumi is singleplayer-first build history for Minecraft.

Save builds, compare changes, branch ideas, restore older states, recover interrupted work, and undo recent tracked edits without juggling duplicate world folders.

## What Lumi Is For

Lumi is made for builders and redstoners who want to:

- save named restore points for a build
- see exactly what changed since a save
- try alternate design branches without losing the stable version
- restore a whole save or only a selected region
- recover unsaved work after a crash or accidental close
- undo recent gameplay, explosion, water, and supported builder-tool edits
- move project history between worlds with import/export packages

## Core Features

- Automatic project history for the current dimension or tracked build area.
- One-time onboarding tour for the safe save, undo, restore, branch, compare, recovery, and import/export flow.
- Save versions with a message, stats, preview image, patch payload, and optional checkpoint snapshot.
- Branch-like variants for alternate ideas.
- Local branch merge into the current branch with conflict planning and a new merge save.
- Rename saves, soft-delete saves, soft-delete inactive branches, and review deleted saves from the More screen.
- Compare a save against its parent, another save, a branch head, or the live current build.
- In-world compare highlight with an x-ray hold mode through the Lumi action button.
- Restore saved versions back into the world with progress reporting.
- Partial restore from save details using manual XYZ bounds or the current Lumi selection, with `Only selected area` and `Everything except selection` modes, plus live undo/redo for the applied partial restore.
- Runtime-only wooden-sword region selection with highlighted cuboid bounds.
- Crash recovery drafts with a direct recovery prompt only for interrupted work from a previous session.
- Optional auto checkpoints before large vanilla `/fill` or `/clone`, WorldEdit, or Axiom edits when pending work exists. This setting is off by default.
- Zip import/export for portable history packages and review projects.
- Storage cleanup for orphaned snapshots, previews, caches, and stale operation drafts.
- Material delta summaries and integrity checks.
- Localized UI resources for English, Russian, French, Spanish, German, and Finnish.

## Undo And Redo

Lumi tracks recent builder actions separately from saved versions.

- Default undo chord: `Left Alt+Z`.
- Default redo chord: `Left Alt+Y`.
- `Left Alt` is the default Lumi action button and can be rebound in Minecraft controls.
- Changing the Lumi action button changes the undo, redo, quick-save, preview, selection, and deselect chords that use it.
- WorldEdit and FAWE actions use the tools' native undo/redo commands through Lumi's hotkeys.
- Axiom keeps its own undo flow.
- TNT, explosion, water, falling-block, and nearby block-update fallout can be folded into the matching tracked action.
- Dropped item entities from those effects are undo-only action data: undo removes them, redo respawns them, and saved versions/recovery drafts do not persist them.

## Region Selection And Partial Restore

Use a wooden sword as Lumi's selection tool.

- Left click in `corners` mode sets corner A.
- Right click in `corners` mode sets corner B.
- Left click in `extend` mode expands the selected bounds.
- Right click in `extend` mode resets the selection to the looked-at block.
- Lumi action button + scroll toggles `corners` / `extend`.
- Lumi action button + right click clears the selection.
- Selection raycasts through already loaded/rendered client chunks and does not force chunk loading.

Open a save, choose `Restore selected area`, choose `Only selected area` to copy the selection from that save or `Everything except selection` to restore around it, use the current Lumi selection or edit XYZ bounds manually, preview the affected blocks, then apply the partial restore as a new save.

## Builder Tool Capture

Lumi captures normal player edits and a conservative set of builder-tool mutation paths, including:

- WorldEdit
- FAWE-style chunk placement
- Axiom block buffers
- Axion
- AutoBuild
- SimpleBuilding
- Effortless Building
- Litematica/Tweakeroo placement paths
- known tool stacks that reach Minecraft block or entity mutation paths

WorldEdit support is optional and does not add a hard runtime dependency.

## Recovery And Safety

- Pending changes are flushed to recovery draft storage in the background.
- If the game crashes or the world is closed with an interrupted draft, Lumi routes project opening to a recovery screen.
- Recovery actions let you restore, save, or discard the draft.
- Auto checkpointing before large external edits is available in settings and is disabled by default.
- Old history data is soft-deleted or left on disk for safety unless cleanup tools explicitly identify removable files.

## Performance Notes

- Heavy save, compare, and restore preparation stays off the server tick.
- Restore apply uses prepared chunk batches and reports progress.
- JSON parsing, LZ4 decompression, and block-state decoding are kept away from the tick-thread apply path.
- Lumi screens do not pause the game.
- The mod is designed for singleplayer and integrated-server workflows first.

## Quick Start

1. Open a local singleplayer world.
2. Press `U` to open Build History.
3. Create or open the project for the current dimension.
4. Build normally.
5. Use `Left Alt+S` for quick save, or `Save build` for the full save screen.
6. Use `See changes`, `Restore`, `Branches`, `Import / Export`, or `More` from the workspace UI.
7. Use `Left Alt+Z` / `Left Alt+Y` while no screen is open to undo/redo the latest tracked action.

All default keybinds can be changed under Minecraft `Controls` -> `Lumi`.

## Builder Terms

- `Project` - one tracked dimension or build area.
- `Version` - one saved history point.
- `Variant` - a named branch-like line of work.
- `Compare` - a diff between saved data and another saved state or the current world.
- `Restore` - applying saved data back into the map.
- `Partial restore` - applying selected bounds from an older save, or restoring the save around selected bounds.
- `Recovery` - draft data kept for interrupted unsaved work.

## Requirements

- Minecraft `1.21.11`
- Fabric Loader `0.19.2`
- Fabric API `0.141.3+1.21.11`
- Java `21`
