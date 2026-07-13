# Lumi

<p align="center">
  <img alt="Lumi banner" src="https://raw.githubusercontent.com/KristopherZlo/lumi/main/lumi-banner.png" />
</p>

<p align="center">
  <strong>Save the build. Try the idea. Undo the mistake.</strong>
</p>

<p align="center">
  <img alt="Minecraft 1.21.11" src="https://img.shields.io/badge/Minecraft-1.21.11-5E7C16?style=for-the-badge" />
  <img alt="Fabric" src="https://img.shields.io/badge/Loader-Fabric-DBD0B4?style=for-the-badge" />
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-1F6FEB?style=for-the-badge" />
</p>

Lumi gives Minecraft builders a **project history for every build**. Save named versions, compare ideas, create branches, restore exactly what you need, and recover interrupted work—all without copying the entire world folder for every checkpoint.

Think of it as version control for structures, designed around Minecraft instead of code.

## Build freely, without losing good ideas

### Save meaningful versions

Turn your work into named checkpoints with previews, change statistics, and everything Lumi needs to restore the build later. Quick-save from the world, amend the latest version, or keep working until the next milestone.

### Compare before you decide

Compare two saved versions, different branches, or your current unsaved edits. Lumi highlights changed blocks in the world, so you can see what moved, appeared, or disappeared instead of guessing from screenshots.

### Explore ideas on separate branches

Try a new roof, palette, redstone layout, or entire redesign without overwriting the version you trust. Switch between branches and merge the idea you want to keep.

### Restore only what went wrong

Restore a complete build, one selected region, or everything outside the selection. Lumi also provides quick rollback plus undo and redo for recent tracked edits.

### Keep focused work separate

Create **work zones** inside a larger project, save a zone on its own, and leave unrelated pending changes untouched. This is useful when several parts of a large build are moving at once.

### Recover and share

Recovery drafts protect unsaved work after crashes or interrupted operations. Project history packages can also be imported and exported when you want to move or share a build history.

## What Lumi tracks

Lumi captures normal Minecraft building and player-caused block or entity fallout. It also supports edits made with **WorldEdit, FAWE, and Axiom** on a best-effort basis.

Save, compare, and restore preparation runs in the background where possible, while long operations report their progress in-game.

## Quick start

1. Enter a world and give Lumi a moment to initialize the workspace.
2. Build normally, then hold **[ALT]** to preview pending changes.
3. Press **[ALT]+[S]** to save your first version.
4. Press **[U]** to open Build History.
5. Use the Compare tab to inspect changes, or open a saved version to restore it.

## Default controls

| Key | Action |
| --- | --- |
| **[U]** | Open Build History, or Zones when a zone is active |
| **[ALT]+[S]** | Save the build, or save the active zone |
| **[ALT]** | Preview pending changes |
| **[ALT]+[1] … [0]** | Switch to a bound branch |
| **[ALT]+[Z]** / **[ALT]+[Y]** | Undo / redo |
| **[R]** | Roll back unsaved work |
| **[H]** | Toggle the compare overlay |
| **[ALT]+[I]** | Show Lumi hotkeys |
| **Wooden sword** | Select regions for partial restore and work zones |

All keybinds can be changed in Minecraft controls.

## Compatibility and requirements

- Minecraft `1.21.11`
- Fabric Loader `0.19.2+`
- Fabric API `0.141.3+1.21.11`
- Java `21+`
- owo-lib `0.13.0+1.21.11`
- Cloth Config `21.11.153+`

Lumi is singleplayer-first. On a dedicated server, install it on the server and on every client that uses Lumi screens or overlays. Server-side history actions require operator permission.

## Alpha notice

Lumi is currently in **alpha**. Features and storage may evolve, and bugs are still possible. Keep normal world backups while testing—history tools should add safety, not replace a backup strategy.

Found a problem or have an idea? [Open an issue on GitHub](https://github.com/KristopherZlo/lumi/issues).

Lumi is licensed under [GPL-3.0](https://github.com/KristopherZlo/lumi/blob/main/LICENSE).
