# User Guide

## Runtime scope

The mandatory MVP is currently intended for **local singleplayer worlds**. Luma runs on the integrated server and stores project data inside the save folder for that world.

## Opening the dashboard

- Press `U` by default to open the Luma dashboard.
- The dashboard lists known projects for the current world.
- Use the search box and the favorites/archive toggles to filter the list.
- Use `Create project` to open the project creation screen.

## Creating a project

1. Open the dashboard.
2. Click `Create project`.
3. Enter a project name.
4. Enter the minimum and maximum corners of the tracked build area.
5. Confirm creation.

Luma immediately creates the project folder, captures the initial checkpoint snapshot, creates the `main` variant, and stores the initial version.

## Working with history

Open a project from the dashboard to reach the main project screen.

The `History` tab lets you:

- Enter a message and save a new version from currently tracked edits.
- Select a version as the active context for the `Changes`, `Preview`, and `Materials` tabs.
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

## Variants

Variants provide separate version heads inside one project.

Use the `Variants` tab to:

- See the active variant.
- Create a new variant from a chosen base version or from the active head.
- Switch the active variant.

When you switch variants, Luma restores that variant head into the world and future saves continue from that head only.

## Compare

Use the `Compare` screen from the project header or from version cards.

Current compare behavior:

- You can compare two version ids directly.
- You can also enter variant ids, and Luma compares the current heads of those variants.
- The compare result shows changed block counts, changed chunk counts, a sample of changed positions, and material delta entries.
- `Show overlay` enables a client-side overlay that highlights changed block positions in the live world.

## Preview and materials

- `Preview` shows the generated preview metadata for the selected version and can refresh the preview file.
- `Materials` shows the per-block-id delta between the selected version and its parent.

Preview generation is lightweight and asynchronous failure-safe in the sense that version saving does not fail if preview generation fails.

## Settings

The project settings screen lets you change:

- Auto-version toggle and interval
- Change session idle timeout
- Checkpoint frequency
- Checkpoint volume threshold
- Safety snapshot before restore
- Preview generation

You can also mark the project as favorite or archived from the same screen.

## Storage location

Each world stores Luma project data under:

```text
<world save>/luma/projects/
```

Each project is stored as a folder ending in `.mbp`. See [storage-format.md](storage-format.md) for the exact layout.

