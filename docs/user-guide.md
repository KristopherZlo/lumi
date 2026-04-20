# User Guide

## Runtime scope

The mandatory MVP is currently intended for **local singleplayer worlds**. Luma runs on the integrated server and stores project data inside the save folder for that world.

## Opening the dashboard

- Press `U` by default to open the Luma dashboard.
- The dashboard focuses on the automatic workspace for your current dimension.
- Open that workspace to continue saving history, comparing versions, or handling recovery drafts.
- Workspaces for other dimensions in the same world appear as a secondary list.

## Legacy manual projects

Manual bounded projects still exist as a legacy fallback through commands such as `/luma create`, but they are no longer the primary menu flow.

## Working with history

Open a workspace from the dashboard to reach the main workspace screen.

The main workspace screen is organized around `History`. It lets you:

- Enter a message and save a new version from currently tracked edits.
- Open a version as the active context for `Changes`, `Preview`, and `Materials`.
- Restore a version into the world.
- Open compare from the selected or clicked version.

New versions are stored primarily as patches. Luma creates checkpoint snapshots when the configured policy requires them.

## Restore behavior

Restore reconstructs the target state from the nearest checkpoint snapshot plus the patch chain after that snapshot.

Important runtime rules:

- Restore runs under an internal `RESTORE` mutation source.
- Restore does not write its own block applications back into player history.
- If the project setting `Safety snapshot before restore` is enabled and there is a pending tracked draft, Luma saves that draft as a restore checkpoint version before applying the target restore.

## Recovery flow

Luma keeps a recovery draft while tracked changes are pending. If the game or session ends before those changes are saved as a version, the next project open will surface a recovery screen.

From the recovery screen you can:

- Restore the draft back into the world.
- Discard the draft.
- Save the draft directly as a new version.

The `Log` tab shows recovery and restore journal entries.

## Branches

Branches provide separate version heads inside one workspace.

Use the `Branches` section to:

- See the active branch.
- Create a new branch from the selected version or from the active head.
- Switch the active branch.

When you switch branches, Luma restores that branch head into the world and future saves continue from that head only.

## Compare

Use the `Compare` screen from version cards or from the version details area.

Current compare behavior:

- You can compare two version ids directly.
- You can also enter branch ids, and Luma compares the current heads of those branches.
- You can compare a saved version against the current live world by using the `current` preset.
- The compare result shows changed block counts, changed chunk counts, a sample of changed positions, and material delta entries.
- `Highlight in world` enables a client-side overlay that highlights changed block positions in the live world.

## Version details

- `Preview` shows the generated preview metadata for the selected version and can refresh the preview file.
- `Materials` shows the per-block-id delta between the selected version and its parent.
- `Changes` shows the sampled block-level diff for the selected version.

Preview generation is lightweight and asynchronous failure-safe in the sense that version saving does not fail if preview generation fails.

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

Each world stores Luma project data under:

```text
<world save>/luma/projects/
```

Each project is stored as a folder ending in `.mbp`. See [storage-format.md](storage-format.md) for the exact layout.
