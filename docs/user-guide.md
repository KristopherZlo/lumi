# User Guide

## Scope

Lumi is built first for local singleplayer saves.

Project data is stored inside the save folder.

Lumi only works for players with operator-level permissions or when cheats are enabled in the current world.

## Open Lumi

- Press `U` to open the project for the current dimension.
- If that project does not exist yet, Lumi creates it.
- Use `Alt+Z` to undo the latest tracked Lumi action.
- Use `Alt+Y` to redo the latest undone Lumi action.
- Ambient world-settling updates like fluid spread or crop growth do not create a project by themselves before you open Lumi or make an explicit tracked edit.
- Those ambient or secondary effects also do not start a new pending draft by themselves while you simply load into the world.
- Use `Projects` in the header to move between dimensions.

## Legacy Manual Projects

Manual bounded project workflows are not exposed through commands. Use the Lumi UI for supported project workflows.

## Main Screen

The main project screen is now a home screen first and a history screen second.

The first block is `What do you want to do?`.

It is meant to answer these questions in a few seconds:

- where you are
- whether anything changed
- how to save right now
- how to restore the latest save
- how to see saved moments
- how to try or share ideas

The top block shows:

- the current dimension
- the current idea
- whether there are unsaved changes
- added, removed, and changed block counts when a draft exists

The primary action is `Save now`.

Secondary actions stay short:

- `Go back`
- `Show them`
- `Ideas`
- `Share`

Below that, `Saved moments` shows recent save cards for the selected idea.

Each card keeps only the essentials visible:

- save name
- time
- small isometric preview
- simple changed-block summary
- `Look closer`
- `See changes`
- `Go back here`

Rare tools like the technical graph, diagnostics, and recovery log now stay under `More`.

Lumi stores new versions as patches first.

Checkpoint snapshots are added by policy.

Tracked history includes:

- player block edits
- supported falling-block outcomes inside an active causal envelope
- supported mob edits
- TNT ignition
- fluid fallout inside an active causal envelope
- fire spread and burn-out
- crop, sapling, and stem growth
- piston movement
- supported explosion edits

Lumi does not record its own restore apply pass as normal history.
Ambient fluid, fire, growth, block-update, and mob changes no longer bootstrap history globally just because the dimension project exists.
Whole-dimension workspaces now treat that explicit tracked action as the root of a causal envelope. Lumi keeps a one-chunk halo around the root chunk, captures per-chunk baselines lazily as fallout reaches those chunks, then reconciles later fallout such as falling gravel and fluid spread against the current world before the draft is flushed, saved, or frozen.
Secondary effects such as falling gravel, fire spread, fluid spread, piston aftermath, and TNT or explosion fallout only join a draft after an explicit tracked action has already started that draft, and only while they stay inside that same causal envelope.

For automatic dimension projects, the first node is `Initial`.

That node is a metadata-backed `WORLD_ROOT`.

## Save And Amend

Open the `Save` screen from the main action on the project home screen.

The save screen is intentionally small:

- `Save name`
- suggestion buttons for common builder notes
- `Save`
- `Cancel`

If you need the advanced rewrite flow, open `More` and use `Replace latest save`.

## Restore

Restore rebuilds the selected state from history data.

Lumi first tries direct replay or rollback on the active variant line.

If that is not valid, it falls back to checkpoint snapshot plus patch chain.

After restore, the active variant head moves to the selected version.

That means restore behaves like a hard reset for the project.

If you restore an older version and keep building, the next save continues from that point.

If you restore `Initial`, Lumi restores only chunks that the current project has already tracked.

It does not roll back unrelated game state like inventory, time, gamerules, or untouched chunks.

Before an `Initial` restore starts, Lumi shows the planned mode, branch, base version, target version, and affected chunk count in a confirmation block above the scrollable workspace panes.
If the stored generator or datapack fingerprint no longer matches the world, automatic generator regeneration is blocked and Lumi stays on the safer history/baseline path.

Runtime rules:

- restore runs under the internal `RESTORE` source
- restore block applies are not written back into normal history
- if `Safety snapshot before restore` is on and a draft exists, Lumi saves that draft before restore starts

## Recovery

Lumi keeps a recovery draft while unsaved tracked changes exist.

If the game stops before you save them as a version, Lumi shows the recovery screen on the next open.

From that screen you can:

- restore the recovered work into the world
- delete the recovered work
- save the recovered work as a new save

Recovery is only a stored copy of unsaved changes.

It does not create a hidden variant.

More technical recovery details are still available, but they are hidden behind `More`.

## Variants

Variants are separate version heads inside one project.

Use the `Variants` screen to:

- see the active variant
- create a new variant from the current latest save
- create a new variant from a specific save
- switch the active variant
- compare a variant against the current build

When you switch variants, Lumi restores that variant head into the map.

Future saves continue from that head.

## Share

`Share` is the current merge-and-share MVP.

Use the `Share` screen to:

- import a shared package as a review project
- review imported packages without leaving the current project
- export one local variant as a history package
- choose whether exported packages include preview PNGs
- delete imported review packages after you are done with them
- merge an imported variant into a local target variant

Import comes first on the screen because it is the usual starting point.

After a package is imported, Lumi keeps you on `Share`, selects that imported review project, and builds a merge review against the current active local variant automatically.
That merge review runs in the background and is cached for the selected imported package and target variant, so reopening the same review does not repeat the file scan unless the imported package list changes.

Merge conflicts are grouped into conflict zones instead of one long raw block list.

For each zone you can:

- keep local
- use imported
- skip for now
- show that zone in world

You can also show all conflict zones at once. Failed imports, incompatible packages, and rejected merges are shown on the screen as validation messages instead of only falling back to a generic failure banner.

Lumi only enables `Apply merge` when every conflict zone has a decision and the result would still bring in at least one imported change.

## Compare

Open `Compare` from the main project screen, a save details screen, or the variants screen.

You can compare:

- two saves
- two variants
- a saved version against the current build

Current compare output includes:

- added, removed, and changed counts
- material delta
- sample of changed positions

The compare screen keeps manual `From` and `To` fields, but the primary output is visual and action-oriented.

It also has presets for common flows like parent, selected version, and active head.

Running `Compare` now turns on the client-side world highlight for the resolved diff immediately.
Press `H` to hide or show the current overlay without rebuilding the comparison.
Hold the compare x-ray key to see that highlight through blocks. The default binding is `Left Alt`, and the key can be changed in Minecraft `Controls`.
Dense diff regions render as an exposed translucent shell, so nearby changes stay readable instead of stacking into a solid color slab.
If one side of the comparison is the `current` build, that active highlight refreshes automatically while you keep editing.
If compare highlight is not active, holding `Alt` shows the latest 10 tracked Lumi actions instead, fading from the newest action to older ones.

The overlay gives priority to changes near the camera.

## Version Details

Open a save card to reach the save details screen.

The save details screen shows:

- the save name
- isometric preview with automatic empty-margin trimming
- time
- variant
- change summary

Primary actions stay focused:

- `Restore`
- `Compare to current build`
- `Compare to previous save`

Advanced actions like refresh preview, replace latest save, create variant from this save, and raw info stay under `More`.

`Partial restore` is also under `More`. Use it when you want to restore only a bounded region from the selected save. Preview the region first, then apply it. Lumi writes the result as a new save on the active variant instead of moving the variant head back to the older save.

## Settings

The settings screen includes:

- change session idle timeout
- checkpoint frequency
- checkpoint volume threshold
- safety snapshot before restore
- preview generation
- debug logging

You can also archive the project there. Auto-version and favorite controls are no longer exposed because those workflows are not part of the supported UI surface.

## Cleanup

Cleanup is a UI workflow. Review the dry-run candidates before applying cleanup.

Cleanup can remove orphaned previews, unreferenced snapshots, disposable cache files outside `baseline-chunks`, and stale operation drafts.

If a Lumi world operation is still running for the project, cleanup keeps `recovery/operation-draft.bin.lz4` and reports the skip instead of deleting it.

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
