# User Guide

## Scope

Lumi is built first for local singleplayer saves.

Project data is stored inside the save folder.

## Open Lumi

- Press `U` to open the project for the current dimension.
- If that project does not exist yet, Lumi creates it.
- Use `Workspaces` in the header to move between dimensions.

## Legacy Manual Projects

Manual bounded projects still exist through commands like `/lumi create`.

They are a fallback. The main flow is the menu UI.

## Main Screen

The main project screen has three parts:

- composer at the top
- version graph in the center
- detail pane on the right

Click a node in the graph to select a version.

The detail pane updates from that selection.

Lumi stores new versions as patches first.

Checkpoint snapshots are added by policy.

Tracked history includes:

- player block edits
- supported falling-block edits
- supported mob edits
- TNT ignition
- supported explosion edits

Lumi does not record its own restore apply pass as normal history.

For automatic dimension projects, the first node is `Initial`.

That node is a metadata-backed `WORLD_ROOT`.

## Save And Amend

Use the composer to save a new version.

Write a message. Then save.

If you want to replace the current head instead of adding a new version on top, use `Amend head`.

## Restore

Restore rebuilds the selected state from history data.

Lumi first tries direct replay or rollback on the active variant line.

If that is not valid, it falls back to checkpoint snapshot plus patch chain.

After restore, the active variant head moves to the selected version.

That means restore behaves like a hard reset for the project.

If you restore an older version and keep building, the next save continues from that point.

If you restore `Initial`, Lumi restores only chunks that the current project has already tracked.

It does not roll back unrelated game state like inventory, time, gamerules, or untouched chunks.

Runtime rules:

- restore runs under the internal `RESTORE` source
- restore block applies are not written back into normal history
- if `Safety snapshot before restore` is on and a draft exists, Lumi saves that draft before restore starts

## Recovery

Lumi keeps a recovery draft while unsaved tracked changes exist.

If the game stops before you save them as a version, Lumi shows the recovery screen on the next open.

From that screen you can:

- restore the draft into the map
- discard the draft
- save the draft as a new version

Recovery is only a stored copy of unsaved changes.

It does not create a hidden variant.

The `Log` tab shows recovery and restore journal entries.

## Variants

Variants are separate version heads inside one project.

Use the `Branches` section to:

- see the active variant
- create a new variant from the selected version
- create a new variant from the active head
- switch the active variant

When you switch variants, Lumi restores that variant head into the map.

Future saves continue from that head.

If you want to move to a variant from the details pane, use `Checkout branch`.

## Compare

Open `Compare` from a version card or from version details.

You can compare:

- two version ids
- two variant ids
- a saved version against the current game state

Current compare output includes:

- changed block count
- changed chunk count
- sample of changed positions
- material delta

The compare screen keeps manual `From` and `To` fields.

It also has presets for common flows like parent, selected version, and active head.

`Highlight in world` turns on a client-side overlay for changed positions.
The overlay is drawn through blocks by default.
Press `H` to hide or show the current overlay without rebuilding the comparison.

The overlay gives priority to changes near the camera.

## Version Details

`Preview` is the main detail tab.

It shows the generated top-down thumbnail for the selected version.

You can also refresh the preview file there.

`Materials` shows the block-id delta between the selected version and its parent.

If an item form exists, Lumi shows its icon.

The old sampled `Changes` view is still in code, but hidden in the main UI for now.

## Settings

The settings screen includes:

- auto-version toggle and interval
- change session idle timeout
- checkpoint frequency
- checkpoint volume threshold
- safety snapshot before restore
- preview generation

You can also mark the project as favorite or archived there.

## Storage Path

Lumi stores project data under:

```text
<save>/lumi/projects/
```

Each project is a folder with the suffix `.mbp`.

Shared origin metadata is stored in:

```text
<save>/lumi/world-origin.json
```

The origin manifest records the world seed, selected datapacks, and per-dimension generator fingerprints.
Old manifests without a Lumi creation marker are treated conservatively and are not eligible for automatic generator regeneration.

See [storage-format.md](storage-format.md) for the full layout.
