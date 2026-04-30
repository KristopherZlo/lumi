# User Guide

## Scope

Lumi is built first for local singleplayer saves.

Project data is stored inside the save folder.

Lumi's UI operations are intended for the local world owner. Dedicated servers still require operator-level permissions for mutating Lumi actions, while singleplayer integrated worlds capture builder edits for history and live undo/redo immediately.

## Open Lumi

- Press `U` to open the project for the current dimension.
- If that project does not exist yet, Lumi creates it.
- The first Lumi workspace open on a Minecraft installation shows a short interactive safety tour. Shortcut steps show your current remapped keys as pixel icons and continue only after you hold the shown shortcut for 0.8 seconds. After the open-workspace shortcut, the tour continues over the Lumi workspace screen. Use the `X` button in the top-right of the tour if you need to leave it early. You can replay it later from `More` or with `/lumi-onboarding`.
- Press the Lumi action button plus `S` to open Quick save while no screen is open. The default chord is `Left Alt+S`, and both keys are remappable in Minecraft `Controls` under `Lumi`.
- Use the Lumi action button plus `Z` to undo the latest tracked action while no screen is open. The default action button is `Left Alt`.
- Use the Lumi action button plus `Y` to redo the latest undone tracked action while no screen is open.
- For WorldEdit and FAWE edits, those chords call the tools' native undo/redo commands and then update Lumi's pending draft. Captured Axiom capability edits replay through Lumi undo/redo, including tool-assisted breaks and placements.
- Undo and redo restore the stored block states without firing immediate redstone/block updates from the replay itself, so restored TNT does not auto-prime just because it is next to powered redstone.
- Undo also removes item drops caused by the tracked edit, such as TNT drops or water-broken blocks, and redo respawns those drops.
- Hold the Lumi action button to preview undo targets, or the action button plus `Y` to preview redo targets, when compare highlight is not active. Changing the action button changes both the preview hold and these undo/redo chords.
- Ambient world-settling updates like fluid spread or crop growth do not create a project by themselves before you open Lumi or make an explicit tracked edit.
- Those ambient or secondary effects also do not start a new pending draft by themselves while you simply load into the world.
- Use `More` -> `Projects` when you need to switch dimensions or choose another workspace.
- Press `Esc` to leave the current Lumi screen. Detail screens go back to their parent screen; top-level workspace screens close the Lumi UI.

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

Project maintenance and the history graph stay in the sidebar `More` route instead of the current-build card.

Below that, `Recent saves` shows recent save cards for the selected branch.

Each card keeps only the essentials visible:

- save name
- time
- small isometric preview
- simple changed-block summary
- `Open`
- `Restore this save`

Import/export and settings stay in the workspace sidebar. `More` contains storage cleanup, manual compare, the colored graph, and raw references directly.

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
When TNT is primed by a tracked builder action, Lumi keeps that action context through the fuse delay and records the resulting block damage with the same history step.
Ambient fluid, fire, growth, block-update, and mob changes no longer bootstrap history globally just because the dimension project exists.
Whole-dimension workspaces now treat that explicit tracked action as the root of a causal envelope. Lumi keeps a one-chunk halo around the root chunk, captures per-chunk baselines lazily as fallout reaches those chunks, then reconciles later fallout such as falling gravel and fluid spread against the current world before the draft is flushed, saved, frozen, or used to choose a live undo/redo action.
Secondary effects such as falling gravel, fire spread, fluid spread, and TNT or explosion fallout only join a draft after an explicit tracked action has already started that draft, and only while they stay inside that same causal envelope.
Runtime-only redstone state flips are ignored. Piston animation blocks such as `minecraft:piston_head` and `minecraft:moving_piston` are normalized out of new history storage so active mechanisms do not clutter saves or the recent action overlay.

For automatic dimension projects, the first node is `Initial`.

That node is a metadata-backed `WORLD_ROOT`.

## Save And Amend

Use Quick save when you only need a name, cancel, and save action. It opens as a standalone dialog from the remappable Lumi action button plus `Quick save key` chord and saves to the current dimension workspace.

Open the `Save` screen from the main action on the project home screen.

The save screen is intentionally small:

- `Save name`
- `Save`
- `Cancel`

If you need the advanced rewrite flow, open `More` on the save screen and use `Replace latest save`.

## Restore

Restore rebuilds the selected state from history data.

Lumi first tries direct patch replay. For another branch, it can roll the current branch back to the shared saved ancestor and then replay the target branch forward.

If that is not valid, it falls back to checkpoint snapshot plus patch chain.

After restore, the active branch head moves to the selected save.

If the selected save belongs to another branch, Lumi plans the restore from the
current live branch state and switches the active branch only after the world
apply finishes.

That means restore behaves like a hard reset for the project.

If you restore an older version and keep building, the next save continues from that point.

If you restore `Initial`, Lumi restores only chunks that the current project has already tracked.

It does not roll back unrelated game state like inventory, time, gamerules, or untouched chunks.

Every restore from a save card or save details screen requires confirmation. Before an `Initial` restore starts, Lumi also shows the planned mode, branch, base save, target save, and affected chunk count in a confirmation block above the scrollable workspace panes.
If you already have a Lumi region selected, the restore confirmation asks how to use that selection:

- `Restore whole save` moves the branch head to that save and applies the full restore.
- `Only selected area` copies only the selected area from that save into the current build and writes a new save.
- `Everything except selection` restores the save around the selection while leaving the selected area untouched, then writes a new save.

If the stored generator or datapack fingerprint no longer matches the world, automatic generator regeneration is blocked and Lumi stays on the safer history/baseline path.

Runtime rules:

- restore runs under the internal `RESTORE` source
- restore block applies are not written back into normal history
- restore replay completes paired block halves such as beds, doors, and tall
  plants so one half is not left clipped after a branch switch or restore
- if `Safety snapshot before restore` is on and a draft exists, Lumi saves that draft before restore starts

## Recovery

Lumi keeps a recovery draft while unsaved tracked changes exist.

If the game stops before you save them as a version, Lumi shows the recovery screen on the next open. Normal unsaved work from the current running session stays as pending work in the project screen and does not repeatedly force the recovery screen.

From that screen you can:

- restore the recovered work into the world
- delete the recovered work
- save the recovered work as a new save

Recovery is only a stored copy of unsaved changes.

It does not create a hidden branch.

Restore and delete actions require confirmation. More technical recovery details are still available, but they are hidden behind `More`.

Auto checkpoints before large external edits are available but off by default. Enable `Auto checkpoint before large edits` in Settings if you want Lumi to save pending work before large vanilla `/fill` or `/clone` commands, before WorldEdit edit sessions, and before Axiom block-buffer edits. If there is no draft, the current branch head is already the checkpoint.

## Branches

Branches are separate build directions inside one project.

Use the `Branches` screen to:

- see the active branch
- create a new branch from the current build or a selected save
- switch the active branch
- open saves for one branch
- delete inactive branches
- merge another local branch into the current branch
- compare a branch against the current build from `More`

Creating a branch only adds a new branch head from the selected save or the
active branch's saved head. It does not consume, discard, or freeze unsaved
recovery draft edits. Lumi keeps branch names as written and generates a stable
internal id automatically when several names normalize to the same id.

When you switch branches, Lumi restores that branch head into the map and keeps
the selected branch active even if that head started from a save on another
branch.
The branch pointer changes at restore completion, not before the world state is
applied.
If a recovery draft is still pending, save or discard it before switching
branches so Lumi does not overwrite unsaved work.

Future saves continue from that head.

Deleting a branch is a soft delete. It hides the branch from normal UI without deleting the saved files. `main` and the active branch cannot be deleted.

`Merge into current branch` compares the selected branch against your active branch, applies the resolved result to the world, and writes a new merge save on the active branch. The source branch is unchanged.

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

Manual `From` and `To` references are hidden by default. Use `More details` in See Changes or `More` in the workspace when you need raw reference fields.

Running See Changes turns on the client-side world highlight for the resolved diff immediately.
Press `H` to hide or show the current overlay without rebuilding the comparison.
Hold the compare x-ray key to see that highlight through blocks. The default binding is `Left Alt`, and the key can be changed in Minecraft `Controls`.
Small and moderate diff regions render as an exposed translucent shell with thicker outlines, so nearby changes stay readable instead of stacking into a solid color slab. Very large diff regions collapse into merged low-alpha volume blobs.
If one side of the comparison is the `current` build, that active highlight refreshes automatically while you keep editing. Very large current-build highlights keep their initial snapshot to avoid client stalls.
If compare highlight is not active, holding the Lumi action button shows the latest 10 undo actions instead. Holding the action button plus `Y` switches that temporary overlay to redo actions. Small previews fade from the newest action to older ones and render translucent exposed sides with thicker outlines; dense previews collapse into merged low-alpha volume blobs.

The overlay gives priority to changes near the camera.

## Save Details

Open a save card to reach the save details screen.

The save details screen shows:

- the save name
- isometric preview with automatic empty-margin trimming, zoom controls, and automatic loading when the async preview render finishes
- time
- change summary

Primary actions stay focused:

- `Restore this save`
- `See changes`

Extra actions like rename save, delete save, replace latest save, create branch from this save, export this save, and raw info stay under `More`.

`Restore selected area` is a primary action on save details. Use it when you want a bounded restore instead of a full branch reset. You can choose `Only selected area` to copy that area from the selected save, or `Everything except selection` to restore the save around the area while keeping the selected area as it is now. Copy a Lumi selection into the form or edit Min/Max coordinates manually. Preview the region first, then apply it. Lumi writes the result as a new save on the active branch instead of moving the branch head back to the older save. The applied partial restore is also undoable with the Lumi action button + `Z`, and redoable with the Lumi action button + `Y`.

You can fill those bounds from Lumi's wooden-sword selection:

- Hold `minecraft:wooden_sword`.
- Look at a block in loaded chunks; it can be beyond normal interaction reach.
- Left click sets corner A in `corners` mode.
- Right click sets corner B in `corners` mode.
- Lumi action button + scroll toggles `corners` and `extend`.
- In `extend` mode, left click expands the current cuboid and right click resets the selection to the clicked block.
- Lumi action button + right click clears the selection.
- The selected cuboid is highlighted in-world.
- Use `Use selected area` in the partial-restore form to copy the selection into the restore bounds.

Renaming a save changes only the saved message. Deleting a save is a soft delete. Root saves cannot be deleted, non-leaf saves are blocked, and deleting a safe branch-head save moves that branch head back to the parent before hiding the save.

Soft-deleted saves are hidden from normal history, but their files remain on disk. Open `More` and review `Deleted saves` when you need to inspect those hidden save ids.

## Settings

The settings screen includes:

- safety snapshot before restore
- auto checkpoint before large edits
- preview generation
- top-right HUD panel visibility
- checkpoint frequency
- checkpoint volume threshold
- change session idle timeout
- debug logging

Settings apply and persist immediately when a checkbox or valid numeric field changes. There is no separate save/apply block.

Project archive controls are no longer part of normal Settings. Import/export lives in the workspace sidebar, while cleanup and history tools live under `More`. Auto-version and favorite controls are no longer exposed because those workflows are not part of the supported UI surface.

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
