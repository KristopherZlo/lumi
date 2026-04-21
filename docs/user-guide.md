# User Guide

## Runtime scope

The mandatory MVP is currently intended for **local singleplayer worlds**. Lumi runs on the integrated server and stores project data inside the save folder for that world.

## Opening the workspace

- Press `U` by default to open the automatic workspace for your current dimension.
- If the workspace does not exist yet, Lumi bootstraps it automatically.
- The `Workspaces` button in the header opens the dashboard when you want to move between dimensions.

## Legacy manual projects

Manual bounded projects still exist as a legacy fallback through commands such as `/lumi create`, but they are no longer the primary menu flow.

## Working with history

The main workspace screen is organized like a source-control view:

- the commit composer always stays at the top
- the middle area shows one commit graph across all variants in the workspace
- the detail pane on the right owns restore, compare, branch checkout, and version details

The commit graph is branch-aware and built from real `parentVersionId` links plus current variant heads. Clicking a node selects it and updates the detail pane.

New versions are stored primarily as patches. Lumi creates checkpoint snapshots when the configured policy requires them.

Tracked history is not limited to direct player block breaks and placements. Explosions and other world mutations that touch tracked workspace blocks are also captured, except for Lumi's own internal restore application.

For automatic whole-dimension workspaces, the first visible history node is `Initial`. This is a metadata-backed `WORLD_ROOT` that records the world seed, game version, and generator context for that workspace.

## Restore behavior

Restore reconstructs the target state from the nearest checkpoint snapshot plus the patch chain after that snapshot.

When the selected target is on the current active branch lineage, Lumi first tries a direct patch replay or rollback path. This keeps restore focused on tracked positions instead of expanding through checkpoint chunk payloads unnecessarily.

After a successful restore, Lumi also resets the active branch head to the selected version. In practice this behaves like a builder-friendly `reset --hard`: if you restore an older version and keep building, the next save or amend continues from that restored point.

When you restore `Initial`, Lumi restores only the chunks that the current workspace has already tracked. In the current implementation this uses the workspace baseline-chunk history for those tracked chunks; it does not rewind unrelated world state such as inventory, time, gamerules, or untouched chunks.

Important runtime rules:

- Restore runs under an internal `RESTORE` mutation source.
- Restore does not write its own block applications back into player history.
- If the project setting `Safety snapshot before restore` is enabled and there is a pending tracked draft, Lumi saves that draft as a restore checkpoint version before applying the target restore.

## Recovery flow

Lumi keeps a recovery draft while tracked changes are pending. If the game or session ends before those changes are saved as a version, the next project open will surface a recovery screen.

From the recovery screen you can:

- Restore the draft back into the world.
- Discard the draft.
- Save the draft directly as a new version.

Recovery does not create a hidden branch or duplicate world. It is only a stored copy of unsaved tracked changes from the interrupted session.

The `Log` tab shows recovery and restore journal entries.

## Branches

Branches provide separate version heads inside one workspace.

Use the `Branches` section to:

- See the active branch.
- Create a new branch from the selected version or from the active head.
- Switch the active branch.

When you switch branches, Lumi restores that branch head into the world and future saves continue from that head only.

If you only want to move the active branch without restoring a specific selected version, use `Checkout branch` from the version detail pane.

If you have pending tracked changes on the current head and want to replace that head instead of adding another version on top, use `Amend head` from the composer area.

## Compare

Use the `Compare` screen from version cards or from the version details area.

Current compare behavior:

- You can compare two version ids directly.
- You can also enter branch ids, and Lumi compares the current heads of those branches.
- You can compare a saved version against the current live world by using the `current` preset.
- The compare result focuses on changed block counts, changed chunk counts, a compact sample of changed positions, and material delta entries.
- The compare setup keeps manual `From` and `To` inputs, but also exposes quick presets for the common parent, selected-version, and active-head workflows.
- `Highlight in world` enables a client-side overlay that highlights changed block positions in the live world.
- The overlay prioritizes changed blocks nearest to your current camera position, so large diffs stay usable while you move through the build.

## Version details

- `Preview` is the primary detail tab. It shows the generated top-down thumbnail for the selected version and can refresh the preview file.
- `Materials` shows the per-block-id delta between the selected version and its parent, including block/item icons when an item representation exists.
- The old sampled `Changes` view remains in code for future iteration, but it is currently hidden from the main details UI because it was too noisy to be useful.

Preview generation is lightweight and failure-safe in the sense that version saving does not fail if preview generation fails. Whole-dimension workspaces generate previews from the tracked chunk extent instead of requiring a manual bounded area.

## Settings

The settings screen lets you change:

- Auto-version toggle and interval
- Change session idle timeout
- Checkpoint frequency
- Checkpoint volume threshold
- Safety snapshot before restore
- Preview generation

You can also mark the workspace as favorite or archived from the same screen.

## Storage location

Each world stores Lumi project data under:

```text
<world save>/lumi/projects/
```

Each project is stored as a folder ending in `.mbp`. Shared world-origin metadata is stored in `lumi/world-origin.json`. See [storage-format.md](storage-format.md) for the exact layout.
