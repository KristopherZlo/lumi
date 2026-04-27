# User Guide

## Scope

Lumi is built first for local singleplayer saves.

Project data is stored inside the save folder.

Lumi's UI operations are intended for the local world owner. Dedicated servers still require operator-level permissions for mutating Lumi actions, while singleplayer integrated worlds capture builder edits for history and live undo/redo immediately.

## Open Lumi

- Press `U` to open the project for the current dimension.
- If that project does not exist yet, Lumi creates it.
- Use `Alt+Z` to undo the latest tracked Lumi action.
- Use `Alt+Y` to redo the latest undone Lumi action.
- Ambient world-settling updates like fluid spread or crop growth do not create a project by themselves before you open Lumi or make an explicit tracked edit.
- Those ambient or secondary effects also do not start a new pending draft by themselves while you simply load into the world.
- Use `More` -> `Projects` when you need to switch dimensions or choose another workspace.

## Legacy Manual Projects

Manual bounded project workflows are not exposed through commands. Use the Lumi UI for supported project workflows.

## Main Screen

The main screen is `Build History` for the current dimension. It is designed around builder actions instead of internal history terms.

It is meant to answer these questions in a few seconds:

- where you are
- whether anything changed
- how to save right now
- how to see changes
- how to restore from a selected save
- where branches and rare tools live

The top block shows:

- the current dimension
- the current branch
- whether there are unsaved changes
- added, removed, and changed block counts when a draft exists

The primary action is `Save build`.

Secondary actions stay short:

- `See changes`
- `Branches`
- `More`

Below that, `Recent saves` shows recent save cards for the selected branch.

Each card keeps only the essentials visible:

- save name
- time
- small isometric preview
- simple changed-block summary
- `Open`
- `Restore this save`

Rare tools like import/export, settings, cleanup, diagnostics, manual compare, technical graph, raw references, and legacy limited projects stay under `More` or `More` -> `Advanced`.

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
- supported explosion edits

Lumi does not record its own restore apply pass as normal history.
Ambient fluid, fire, growth, block-update, and mob changes no longer bootstrap history globally just because the dimension project exists.
Whole-dimension workspaces now treat that explicit tracked action as the root of a causal envelope. Lumi keeps a one-chunk halo around the root chunk, captures per-chunk baselines lazily as fallout reaches those chunks, then reconciles later fallout such as falling gravel and fluid spread against the current world before the draft is flushed, saved, or frozen.
Secondary effects such as falling gravel, fire spread, fluid spread, and TNT or explosion fallout only join a draft after an explicit tracked action has already started that draft, and only while they stay inside that same causal envelope.
Runtime-only redstone state flips are ignored. Piston animation blocks such as `minecraft:piston_head` and `minecraft:moving_piston` are normalized out of new history storage so active mechanisms do not clutter saves or the recent `Alt` action overlay.

For automatic dimension projects, the first node is `Initial`.

That node is a metadata-backed `WORLD_ROOT`.

## Save And Amend

Open the `Save` screen from the main action on the project home screen.

The save screen is intentionally small:

- `Save name`
- suggestion buttons for common builder notes
- `Save`
- `Cancel`

If you need the advanced rewrite flow, open `More` on the save screen and use `Replace latest save`.

## Restore

Restore rebuilds the selected state from history data.

Lumi first tries direct replay or rollback on the active branch line.

If that is not valid, it falls back to checkpoint snapshot plus patch chain.

After restore, the active branch head moves to the selected save.

That means restore behaves like a hard reset for the project.

If you restore an older version and keep building, the next save continues from that point.

If you restore `Initial`, Lumi restores only chunks that the current project has already tracked.

It does not roll back unrelated game state like inventory, time, gamerules, or untouched chunks.

Every restore from a save card or save details screen requires confirmation. Before an `Initial` restore starts, Lumi also shows the planned mode, branch, base save, target save, and affected chunk count in a confirmation block above the scrollable workspace panes.
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

It does not create a hidden branch.

Restore and delete actions require confirmation. More technical recovery details are still available, but they are hidden behind `More`.

## Branches

Branches are separate build directions inside one project.

Use the `Branches` screen to:

- see the active branch
- create a new branch from the current build or a selected save
- switch the active branch
- open saves for one branch
- compare a branch against the current build from `More`

When you switch branches, Lumi restores that branch head into the map.

Future saves continue from that head.

## Import / Export

`Import / Export` lives under `More` and is not part of the main Build History screen.

Use the `Import / Export` screen to:

- open the game-root `lumi-projects` folder
- import package zips listed in that folder
- import a shared package as a review project
- review imported packages without leaving the current project
- export the build history, current branch, or selected save as a package
- choose whether exported packages include preview PNGs
- delete imported review packages after you are done with them
- combine an imported branch into your current build

Export comes first on the screen, then Import lists package zips found in the game-root `lumi-projects` folder. You can still paste an absolute archive path manually.

After a package is imported, Lumi keeps you on `Import / Export`, selects that imported review project, and builds a combine review against the current active local branch automatically.
That combine review runs in the background and is cached for the selected imported package and target branch, so reopening the same review does not repeat the file scan unless the imported package list changes.

Same-area changes are grouped into review zones instead of one long raw block list.

For each zone you can:

- keep mine
- use imported
- skip for now
- show that zone in world

You can also show all same-area zones at once. Failed imports, incompatible packages, and rejected combines are shown on the screen as validation messages instead of only falling back to a generic failure banner.

Lumi only enables `Apply combine` when every same-area zone has a decision and the result would still bring in at least one imported change.

## See Changes

Open `See changes` from Build History, a save details screen, or the Branches screen.

You can compare:

- two saves
- two branches
- a saved version against the current build

Current See Changes output includes:

- added, removed, and changed counts
- material delta
- sample of changed positions

Manual `From` and `To` references are hidden by default. Use `More details` -> `Advanced manual compare` when you need raw reference fields.

Running See Changes turns on the client-side world highlight for the resolved diff immediately.
Press `H` to hide or show the current overlay without rebuilding the comparison.
Hold the compare x-ray key to see that highlight through blocks. The default binding is `Left Alt`, and the key can be changed in Minecraft `Controls`.
Dense diff regions render as an exposed translucent shell, so nearby changes stay readable instead of stacking into a solid color slab.
If one side of the comparison is the `current` build, that active highlight refreshes automatically while you keep editing.
If compare highlight is not active, holding `Alt` shows the latest 10 tracked Lumi actions instead, fading from the newest action to older ones.

The overlay gives priority to changes near the camera.

## Save Details

Open a save card to reach the save details screen.

The save details screen shows:

- the save name
- isometric preview with automatic empty-margin trimming and zoom controls
- time
- change summary

Primary actions stay focused:

- `Restore this save`
- `See changes`

Advanced actions like refresh preview, replace latest save, create branch from this save, export this save, and raw info stay under `More`.

`Restore selected area` is also under `More`. Use it when you want to restore only a bounded region from the selected save. Min/Max coordinate fields appear only after you choose that action. Preview the region first, then apply it. Lumi writes the result as a new save on the active branch instead of moving the branch head back to the older save.

## Settings

The settings screen includes:

- safety snapshot before restore
- preview generation
- top-right HUD panel visibility
- checkpoint frequency
- checkpoint volume threshold
- change session idle timeout
- debug logging

Settings apply and persist immediately when a checkbox or valid numeric field changes. There is no separate save/apply block.

Project archive controls are no longer part of normal Settings. Cleanup, diagnostics, import/export, and advanced tools live under `More`. Auto-version and favorite controls are no longer exposed because those workflows are not part of the supported UI surface.

## Cleanup

Cleanup lives under `More` -> `Cleanup`. Review the dry-run candidates before applying cleanup.

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
