# User Guide

## Scope

Lumi is built first for local singleplayer worlds.

Project data is stored inside the Minecraft save folder.

Normal Lumi workflows are UI-first. Use screens for saving, restoring, branching, recovery, import/export, cleanup, settings, and compare. Commands are limited to help, diagnostics, onboarding replay, and runtime tests.

Lumi UI actions are intended for the local world owner. Capture and mutating Lumi actions activate only when the current player has the required admin/operator-level permission. In singleplayer, this follows the world's command permission state; on dedicated servers, the server operator permission gate applies.

## Quick Start

1. Open a singleplayer world.
2. Press `U` to open Lumi for the current dimension.
4. Build normally.
3. Complete or close the short onboarding tour.
5. Use `Save build` or `Left Alt+S` to create a save.
6. Use `See changes` to compare the saved head against the current build.
7. Use `Left Alt+Z` and `Left Alt+Y` to undo or redo recent tracked actions.
8. Press `Left Alt+I` if you forget Lumi's shortcuts.
9. Press `R` to quick rollback unsaved work to the active branch head, or only the selected area when a wooden-sword selection is active.
10. Open a save card when you need details, restore, partial restore, compare, rename, export, or branch actions.
11. Use `Branches` for alternate build directions.
12. Use `Import / Export` to move history packages or combine imported work.
13. Use `More` for cleanup, manual highlight, onboarding replay, deleted saves, the history graph, and raw support ids.
14. Use `Settings` for safety, previews, HUD, storage cadence, performance, and debug logging.

## Terms

| Player-facing term | Meaning |
| --- | --- |
| Workspace | Lumi history for the current world dimension |
| Save | A named history point for a build |
| Branch | A separate build direction inside the same workspace |
| Unsaved work | Tracked edits that are not saved as a save yet |
| Restore | Apply a save back into the world and move the active branch head |
| Partial restore | Apply only a selected region from a save, then write a new save |
| See changes | Compare two states and show changed blocks/materials |
| Recovery | Stored unsaved work from an interrupted previous session |
| Quick rollback | Remove current unsaved work without moving the saved branch head |
| Return before restore | Restore the explicit safety point saved before the last full restore |

## Open Lumi

- Press `U` while no screen is open.
- Lumi opens the workspace for the current dimension.
- If the workspace does not exist yet, Lumi creates it.
- If interrupted recovered work exists, Lumi opens `Recovered work` first.
- If onboarding has not been completed for this installation, Lumi opens onboarding before the normal workspace.
- If another Lumi world operation is active, screens show operation progress and block conflicting mutation actions.
- If a newer Lumi version is available for the same Minecraft version, Lumi can show an update notice after the normal workspace opens.

`Esc` closes the current Lumi screen. Detail screens return to their parent. Top-level workspace screens close the Lumi UI.

## Update Notices

Lumi checks for mod updates when Build History opens. It only prompts for a newer Lumi release that supports the Minecraft version you are currently running.

The normal source is the Lumi website manifest. If that source cannot be loaded, Lumi tries the GitHub fallback manifest from the public repository.

The update window can open the download page, open the changelog, hide the prompt until the game restarts with `Later`, or permanently hide that specific version with `Don't show this version`.

The check is cached for about 12 hours so opening the menu repeatedly does not keep hitting the network.

## Projects Screen

The `Projects` screen is the workspace picker when a UI route exposes it.

It shows:

- the current dimension;
- the current workspace for that dimension;
- other dimension workspaces in the same world;
- save count;
- unsaved change count;
- recovery badge when interrupted work exists.

Available actions:

- `Open project`;
- `Recovery`, when a recovery draft exists;
- `Settings`;
- `Refresh`;
- `Back`.

Pressing `U` is faster for normal play because it opens the current dimension workspace directly.

## Legacy Bounded Project Creation

Automatic dimension workspaces are the normal supported workflow.

The legacy bounded project creation screen is only for UI routes or testing paths that expose it. It asks for:

- project name;
- min XYZ bounds;
- max XYZ bounds.

After creation, Lumi opens that project.

There are no `/lumi` commands for manual bounded project creation. Use the UI route if it is exposed by the current build.

## Onboarding And Tips

The first workspace open shows a guided onboarding flow. It is intentionally not a full manual and does not use fake `Save build` or `See changes` buttons.

The tour has nine cards and surfaces:

- `welcome`: welcome the player and explain that Lumi keeps build history;
- `open`: show the remapped `Open current workspace` key inline and wait for the player to hold it;
- `save_spotlight`: open the Lumi workspace and spotlight the `Save build` button without running it;
- `changes_spotlight`: spotlight `See changes` without running it;
- `break_block`: close the workspace and ask the player to break or place one block in the world;
- `undo_world`: show the Undo shortcut inline, wait for the hold, run Undo, wait for that operation to finish, and close all screens for one more second so the undone world interaction is visible;
- `redo_world`: show the Redo shortcut inline, wait for the hold, run Redo, wait for that operation to finish, and close all screens for one more second so the restored world interaction is visible;
- `shortcuts`: show a compact shortcut table for opening Lumi, quick save, Undo, Redo, and Quick rollback;
- `finish`: remind the player that `Left Alt+I` opens shortcut information, close with wiki/help guidance, and leave the player in the world.

The card format is compact: `current/total Quick tour`, page name, one white teaching line, optional gray hold text with the key glyph inline, navigation buttons, and a green hold-progress line. Shortcut keys are not placed in a separate large box, and the copy does not explain the hold duration.

During onboarding, Lumi controls shortcut execution explicitly. Holding `U` advances the open step and then opens the workspace for the same tour instance. Holding `Left Alt+Z` or `Left Alt+Y` during the in-world teaching steps runs the matching onboarding action once the hold completes, keeps all screens hidden until that action reaches its terminal operation state, then waits about one more second with a small HUD confirmation before reopening the tour. The top-right `X` closes the full onboarding flow and marks it complete. Other Lumi shortcut clicks are drained while a modal onboarding card or onboarding world-preview step is active.

Advanced workflows such as wooden-sword selection, partial restore modes, import review, cleanup, diagnostics, and raw references are taught later through contextual hints, in-world teaching, and focused confirmations while the player is using the relevant tab or tool.

Replay onboarding from `More` -> `Show onboarding` or with:

```mcfunction
/lumi-onboarding
```

Focused screens and tabs also show small dismissible tips next to the relevant workflow. Use `More` -> `Show tips again` to reset those tips without replaying the full onboarding tour.

## Shortcuts

All keybinds are remappable in Minecraft `Controls` under `Lumi`.

| Default | Action |
| --- | --- |
| `U` | Open the current workspace |
| `Left Alt+S` | Open Quick save |
| `Left Alt+Z` | Undo the latest tracked action |
| `Left Alt+Y` | Redo the latest undone tracked action |
| Hold `Left Alt` | Preview next undo/redo targets, or compare x-ray when compare is active |
| `R` | Quick rollback unsaved work, scoped to the active wooden-sword selection when present |
| `H` | Hide or show the active compare overlay |
| `Left Alt+I` | Open the Lumi hotkey information table |

The Lumi action button defaults to `Left Alt`. Changing it changes quick save, undo, redo, preview, compare x-ray, and wooden-sword selection modifier behavior.

While undo or redo chords are active, Lumi suppresses normal use/attack input. The shortcut will not also click the lever, button, block, or item you are looking at.

## HUD And Operation Feedback

The optional top-right HUD shows:

- `Lumi`;
- the current dimension;
- the active branch id;
- unsaved added and removed counts.

Disable it in `Settings` -> `HUD` -> `Show top-right Lumi panel`.

Action bar messages are separate from the HUD. Lumi uses the action bar for:

- active operation stage;
- short active-operation status text;
- quick save/restore/undo/redo feedback;
- wooden-sword selection feedback;
- warnings and errors.

Active world operations also show a native Minecraft bossbar. It covers save, restore, undo/redo, merge, recovery, preparation, chunk preloading, apply, and finalization progress.

Large operations may show stages such as queued, preparing, preloading, writing, applying, finalizing, completed, or failed.

Only one map mutation operation is expected per workspace at a time. While an operation is active, Lumi disables conflicting mutation actions instead of starting a second restore/save/merge/cleanup apply.
Repeated `Left Alt+Z` and `Left Alt+Y` presses are the exception: Lumi queues up to 16 undo/redo shortcut intents for the current workspace and runs them in order after each active world operation completes. If the relevant stack is empty when a queued intent runs, Lumi shows the unavailable message and continues with the next queued intent.

## What Lumi Tracks

Lumi tracks explicit builder-driven changes in the current workspace:

- player block edits;
- non-player entity spawn, removal, position, and persistent state changes from explicit player actions;
- supported builder-tool block and entity edits;
- supported falling-block outcomes inside an active causal envelope;
- TNT ignition and the resulting TNT damage;
- explosion edits tied to a tracked action;
- fluid fallout inside an active causal envelope;
- fire spread and burn-out inside an active causal envelope;
- crop, sapling, and stem growth when it belongs to active tracked fallout;
- redstone and piston fallout after the mechanism settles.

Lumi does not record its own restore/apply pass as normal history.

Ambient world-settling changes do not create a workspace by themselves. Loading a world, fluid spread, crop growth, ordinary mob movement, or unrelated block updates do not start a new pending draft unless they are connected to an explicit tracked builder action. Causal secondary fallout from tracked actions, including fluid/lava-water block formation, fire, growth, falling blocks, fallback explosion edits, and mob block edits, is stored as hidden history: save, restore, recovery, undo, and redo can replay it, but change summaries, screenshots/previews, compare overlays, pending overlays, and recent undo/redo overlays ignore it.

Whole-dimension workspaces treat the explicit edit as the root of a causal envelope. Related fallout can join the draft while it stays near the action and inside player-loaded chunks. Lumi waits a short settle window before finalizing redstone and piston chunks so saves and undo/redo target the final block state instead of transient animation states.

Redstone and mechanism saves store final settled state:

- lever/button `powered`;
- redstone wire `power`;
- lamp `lit`;
- doors/trapdoors/openable `open`;
- repeater/comparator properties;
- piston base `extended`;
- settled `minecraft:piston_head`;
- moved blocks after piston motion settles.

Short-lived `minecraft:moving_piston` animation state is normalized away. Replay can complete expected piston companions from explicit piston-base records, but it does not invent a piston base from a head-only record.

Non-player entities are replayed from saved NBT payloads, including item age, pickup delay, motion, names, tags, and item data. Spawned entities are snapshotted only after Minecraft accepts them into the world.

## Build History

`Build History` is the main workspace screen.

It answers:

- where you are;
- which branch is active;
- whether there is unsaved work;
- how many blocks were added, removed, or changed;
- how to save now;
- how to compare changes;
- how to open recent saves;
- where branches and less common tools live.

The primary action is `Save build`.

Secondary actions include:

- `See changes`;
- `Quick rollback`;
- `Return before restore`, only when a restore return point exists.

Recent saves show the selected branch. Each card shows:

- save name;
- author/time;
- small isometric preview;
- changed-block summary;
- current-head badge when applicable;
- `Open`;
- `Restore this save`.

Use the branch picker above recent saves to switch to another branch and restore that branch head into the world. Use `Show older saves` when the branch has more saves than the initial recent list.

If a preview PNG is still being generated, the preview card shows the centered loading animation instead of the no-preview text.

## Save Build

Use `Save build` from Build History when you want a normal named save.

The save screen contains:

- `Save name`;
- `Save`;
- `Cancel`.

The save action consumes the current unsaved draft and writes a new save on the active branch. If there are no unsaved changes, the save action is disabled and the screen explains that the build is clean.

Lumi stores saves as patch-first history. Checkpoint snapshots are added by policy so restore paths stay reliable.

## Quick Save

Use Quick save when you only need to name the current work and save without opening Build History.

- Default chord: `Left Alt+S`.
- Both the action button and quick-save key are remappable.
- Quick save opens a small standalone dialog.
- It saves to the current dimension workspace.
- It does not require entering the Build History screen first.

## Replace Latest Save

Use `Replace latest save` only when you intentionally want to amend the active branch head instead of creating a new save.

Open it from `More` on the save screen or save details flow when available.

Use a new save for normal milestones. Use replace/amend for correcting the latest save message/content while staying on the same branch head.

## Save Details

Open a save card with `Open`.

Save details shows:

- save name;
- isometric preview of blocks and fluids with automatic empty-margin trimming;
- preview zoom controls;
- automatic refresh when async preview generation finishes;
- time and author;
- lightweight change summary from stored save metadata.

Primary actions:

- `Restore this save`;
- highlight changes against the current build;
- highlight changes from the parent save.

When the selected save is the current active branch head and there is no unsaved work, the current-build highlight uses the previous save against that latest save instead of opening an empty latest-save-to-current-build comparison.

Extra actions live under `More`:

- `Restore part of save`;
- rename save;
- delete save;
- replace latest save;
- create branch from this save;
- export this save;
- raw info.

`See changes` loads the full added/removed/changed breakdown, material delta, and changed-position sample.

### Rename Save

Renaming a save changes only the saved message.

It does not rewrite history payloads, branch structure, or stored changes.

### Delete Save

Deleting a save is a soft delete.

Rules:

- root saves cannot be deleted;
- non-leaf saves are blocked;
- safe branch-head deletes can move that branch head back to the parent before hiding the save;
- deleted save files stay on disk.

Review soft-deleted saves from `More` -> `Deleted saves`.

## Restore

Restore rebuilds the selected saved state into the world.

Normal restore behavior:

- full restore requires confirmation;
- Lumi applies the selected save into the map;
- the active branch head moves to the restored save after the world apply finishes;
- if you keep building after restoring an older save, the next save continues from that restored point;
- restore does not roll back inventory, time, gamerules, or untouched chunks.

Restore path:

- Lumi first tries direct patch replay.
- For another branch, Lumi can plan from the current live branch through a shared saved ancestor.
- Direct restores to `Initial` or `WORLD_ROOT` finish by replaying the saved root state for chunks touched by the rollback, so branch switches back to a root save do not rely only on reverse patches.
- When direct replay touches redstone or mechanisms, Lumi also resolves a bounded target-state envelope off-thread. This lets restore clear stale dust, torches, repeaters, comparators, lamps, observers, dispensers, droppers, pistons, and controls without turning the operation into a full-world restore.
- If that mechanism envelope is too large, Lumi skips direct replay and uses the existing snapshot/patch-chain restore path instead of silently slowing the operation.
- If direct replay is not valid, Lumi falls back to checkpoint snapshot plus patch chain.
- If required payloads are missing or corrupt, restore is rejected before the world changes.

Restoring `Initial` only restores chunks the project has already tracked.

Before an `Initial` restore, Lumi shows a plan summary:

- planned mode;
- branch;
- base save;
- target save;
- affected chunk count.

Runtime rules:

- restore runs as an internal restore source;
- restore block applies are not written back as normal history;
- paired blocks such as beds, doors, and tall plants are completed during replay;
- if `Safety save before restore` is enabled and a draft exists, Lumi saves that draft before restore starts.

After a full restore completes, Lumi clears the old live undo/redo stack because those actions belonged to the pre-restore world state.

## Return Before Restore

When Lumi performs a full restore, it can keep an explicit return point for the state before that restore.

Use `Return before restore` from Build History when:

- the button is visible;
- you need the harder branch-head return path;
- normal quick rollback or undo is not the right tool.

This is separate from quick rollback. It restores the saved pre-restore return point through the normal world-operation path.

## Quick Rollback

Quick rollback removes current unsaved work and returns the world to the active branch head.

Use it when an experiment went wrong before you saved it.

Rules:

- default key: `R`;
- it only targets the current dirty draft;
- when a Lumi wooden-sword selection is active, the key restores only pending draft changes inside that selection and leaves the rest pending;
- it does not move the saved branch head;
- it applies the inverse draft through the fast action apply path;
- for redstone/mechanism edits, full quick rollback also resolves the saved head state for the captured mechanism halo; selected quick rollback clips all writes to the selection before applying that halo;
- it records one fresh live undo/redo action for the rollback itself.

After quick rollback, press `Left Alt+Z` if you need to bring the rolled-back work back. Press `Left Alt+Y` to redo the rollback.

Quick rollback is not the same as full restore. Full restore changes the active branch head. Quick rollback only removes unsaved work.

## Undo And Redo

Live undo/redo is a lightweight in-memory action stack for the current play session.

Use:

- `Left Alt+Z` to undo;
- `Left Alt+Y` to redo.

Repeated undo/redo shortcut presses are queued per current workspace, up to 16 pending intents. The action is chosen when the queued intent runs, so later edits or unavailable stacks are handled at execution time.

Rules:

- saving a version does not clear the live undo/redo stack;
- undoing after a save creates a new pending draft on top of the saved head;
- restarting the game keeps recovery drafts but not the step-by-step undo/redo stack;
- redo remains available after passive fallout is folded into the active action;
- undo/redo waits for still-settling redstone or piston chunks instead of selecting a partial lever-only action, and it can load those pending chunks even if you have walked away from the edit area.

Undo and redo restore stored block states and captured block-entity payloads, including container item counts, with side-effect-suppressed placement flags.

Redstone power/source changes queue scoped neighbor updates after stored blocks are written so nearby circuitry can settle. Fluid replay queues bounded vanilla fluid ticks around loaded connected fluid tails while exact replay keeps the stored target cells fixed, so removed water or lava tails do not hang until a nearby block update. Ordinary replay still avoids placement physics and suppresses stale replay callbacks around piston/observer mechanism positions.

Undo also removes item drops caused by the tracked edit, such as:

- player-killed mob drops;
- TNT drops;
- water-broken block drops;
- falling-block fallout drops.

Redo respawns those drops for the same tracked action.

## Preview Unsaved And Recent Actions

When compare highlight is not active:

- hold the Lumi action button to preview the next live undo and redo targets.

Small and medium previews render exposed translucent sides with thicker outlines. The next undo target is highlighted red; the next redo target is highlighted green; older recent actions remain orange. If no live action preview is available, Lumi falls back to the pending unsaved changes overlay. Large dense pending previews collapse into merged low-alpha volume blobs.

If you keep holding the Lumi action button after `Left Alt+Z` or `Left Alt+Y`, the recent-action overlay refreshes when the undo/redo stack changes. You do not need to release and hold the key again to inspect the next target.

Previews are temporary. They do not create saves, change history, or apply blocks.

## See Changes

Open `See changes` from:

- Build History;
- save details;
- Branches;
- manual highlight in `More`.

You can compare:

- two saves;
- two branches;
- a save against the current build;
- a branch against the current build;
- manual raw references when needed for support/debug.

See Changes shows:

- added count;
- removed count;
- changed count;
- material delta;
- sample changed positions.

Running See Changes turns on the client-side world highlight for the resolved diff.

Overlay controls:

- press `H` to hide or show the current highlight without rebuilding the comparison;
- hold the compare x-ray key to see the highlight through blocks;
- the x-ray key defaults to the Lumi action button, `Left Alt`;
- when compare data is active, a lower-left hotkey card shows the current show/hide and x-ray bindings;
- if one side is `current`, the highlight can refresh while it is visible and you keep editing;
- very large current-build highlights keep their initial snapshot to avoid client stalls.

Small and moderate diff regions render as a translucent exposed shell with thicker outlines. Extremely large diff regions collapse into merged low-alpha volume blobs that are split into short section tiles and slightly offset from block faces so dense edits stay visible in normal mode. Hold the compare x-ray key when you intentionally need to see the highlight through blocks.

The overlay caches section geometry and reuses GPU buffers. It does not rebuild every highlighted block every frame, and very broad highlights draw the nearest visible sections first under a per-frame draw budget.

Compare highlight takes priority over undo/redo preview.

## Partial Restore

Use partial restore when you want a bounded restore instead of a full branch reset.

Open it from save details by expanding `More` and choosing `Restore part of save`.

Modes:

- `Only selected area`: copy only the selected area from the chosen save into the current build;
- `Everything except selection`: restore the save around the selected area and leave the selected area unchanged.

Bounds sources:

- current Lumi wooden-sword selection;
- manually entered min/max XYZ bounds.

Recommended flow:

1. Open the target save.
2. Expand `More` and choose `Restore part of save`.
3. Copy the Lumi selection or enter bounds manually.
4. Preview the region.
5. Apply the partial restore.

Lumi writes the result as a new save on the active branch. It does not move the branch head back to the older save directly.

The applied partial restore is also undoable with `Left Alt+Z` and redoable with `Left Alt+Y`.

Partial restore can target saves without a direct patch replay path from the current branch. In that case Lumi reconstructs current and target state from snapshots, baseline chunks, and patches before applying the selected region. Same-lineage partial restore uses the same target-state planner when the direct patch path contains redstone/mechanism states, so selected-area and everything-except-selection modes keep their write boundaries.

If stored generator or datapack fingerprints no longer match the world, automatic generator regeneration is blocked and Lumi stays on the safer history/baseline path.

## Wooden-Sword Selection

Use Lumi's wooden-sword selection to fill partial-restore bounds from the world.

Steps:

1. Hold `minecraft:wooden_sword`.
2. While you hold it in a Lumi workspace, Lumi shows a compact lower-left HUD hint for left click, right click, Lumi action button + right click, and Lumi action button + scroll.
3. Look at a block in loaded chunks. The target can be beyond normal interaction reach.
4. Left click sets corner A in `corners` mode.
5. Right click sets corner B in `corners` mode.
6. Use Lumi action button + scroll to switch between `corners` and `extend`.
7. In `extend` mode, left click expands the current cuboid.
8. In `extend` mode, right click resets the selection to the clicked block.
9. Use Lumi action button + right click to clear the selection.
10. Use `Use selected area` in the partial-restore form to copy the selection into restore bounds.
11. Press `R` with a selection active to quick rollback only the selected area.

The selected cuboid is highlighted in-world only while the wooden sword is still held. Putting the sword away hides the highlight without clearing the selected bounds.

## Recovery

Lumi stores a recovery draft while unsaved tracked changes exist.

If the game stops before those changes are saved, Lumi shows `Recovered work` on the next workspace open.

Normal unsaved work from the current running session stays as pending work in Build History. It does not repeatedly force the recovery screen.

Recovery actions:

- restore recovered work into the world;
- delete recovered work;
- save recovered work as a new save.

Recovery is only a stored copy of unsaved changes. It does not restore the in-memory live undo/redo action stack.

Recovery does not create a hidden branch.

Restore and delete actions require confirmation. Technical recovery details stay behind `More`.

## Auto Checkpoints

Auto checkpoints before large external edits are available but off by default.

Enable them in `Settings` -> `Safety` -> `Auto checkpoint before large edits`.

When enabled, Lumi can save pending work before:

- large vanilla `/fill`;
- large vanilla `/clone`;
- WorldEdit edit sessions;
- Axiom block-buffer edits.

If there is no draft, the current branch head is already the checkpoint and Lumi does nothing.

## Branches

Branches are separate build directions inside one workspace.

Use `Branches` to:

- see the active branch;
- create a new branch from the current build or selected save;
- switch the active branch;
- open saves for one branch;
- compare a branch against the current build;
- merge another local branch into the current branch;
- delete inactive branches.

Creating a branch only adds a new branch head from the selected save or active branch head. It does not consume, discard, or freeze unsaved recovery draft edits.

Branch names stay as written. Lumi generates stable internal ids when names normalize to the same value.

Switching branches restores that branch head into the map.

Rules:

- the branch pointer changes after restore apply completes;
- if recovery is pending, save or discard it before switching;
- future saves continue from the switched branch head;
- `main` and the active branch cannot be deleted;
- deleting a branch is a soft delete and does not remove saved files.

`Merge into current branch` compares the selected branch against the active branch, applies the resolved result to the world, and writes a new merge save on the active branch. The source branch is unchanged.

## Import / Export

`Import / Export` is a first-level workspace sidebar tab.

Use it to:

- open the game-root `lumi-projects` folder;
- export the active history or selected branch as a package;
- choose whether exported packages include preview PNGs;
- list package zips in `lumi-projects`;
- import a package zip;
- import a shared package as a review project;
- review imported work without leaving the current project;
- combine an imported branch into your current build;
- delete imported review projects after use.

Export appears first. Import appears below it.

The package folder is:

```text
<game root>/lumi-projects/
```

After import, Lumi keeps you on `Import / Export`, selects the imported review project, and builds a combine review against the current active local branch.

Combine review:

- runs in the background;
- is cached for the selected imported package and target branch;
- groups same-area changes into review zones;
- avoids one long raw block list.

For each same-area zone, choose:

- keep mine;
- use imported;
- skip for now;
- show that zone in world.

You can also show all same-area zones at once.

`Apply combine` is enabled only when every same-area zone has a decision and the result would import at least one change.

Failed imports, incompatible packages, rejected combines, and package validation problems are shown on the screen.

## Settings

Settings apply immediately when a checkbox changes or a numeric field is valid. There is no separate save/apply button.

Sections:

| Section | Setting | Effect |
| --- | --- | --- |
| Safety | `Safety save before restore` | Save current draft before restoring an older save |
| Safety | `Auto checkpoint before large edits` | Save pending work before large fill/clone/WorldEdit/Axiom edits |
| Preview | `Preview generation` | Create isometric preview images when saves are made |
| HUD | `Show top-right Lumi panel` | Show or hide the persistent in-world Lumi panel |
| Storage | `Full save every N saves` | Create a full storage copy after this many saves |
| Storage | `Full-save volume threshold` | Create a full storage copy when changed volume is large enough |
| Performance | `Idle timeout (seconds)` | Close active tracked edit sessions after this idle time |
| Debug | `Debug logging` | Write detailed runtime logs for this workspace |

Numeric settings must be greater than zero. Invalid values show inline validation and are not saved.

Project archive controls are not part of Settings. Use `Import / Export`.

Cleanup, diagnostics, manual highlight, graph, deleted saves, raw references, onboarding replay, and tip reset live under `More`.

Auto-version and favorite controls are not exposed in the supported UI surface.

## More

`More` contains tools that are useful but should not crowd the daily Build History flow.

Tabs:

- `Project tools`;
- `Deleted saves`.

Project tools include:

- `Show onboarding`: replay the short safety tour;
- `Show tips again`: reset contextual hints;
- `Storage cleanup`: open Cleanup;
- `Manual highlight`: choose raw save/branch/current references for highlighting;
- `History graph`: visual graph of saves and branch heads;
- `Raw references`: project name, active branch id, and recent save ids for support/debug.

Deleted saves shows soft-deleted saves that remain on disk. It shows the save title, author/time, save kind, and raw save id.

## Cleanup

Open Cleanup from `More` -> `Storage cleanup`.

Cleanup is conservative. Always inspect first.

Flow:

1. Click `Inspect unused files`.
2. Review the dry-run result.
3. If candidates exist, click `Clean up`.

Cleanup can remove:

- orphaned previews;
- unreferenced snapshots;
- disposable cache files outside `baseline-chunks`;
- operation drafts that cannot hide recoverable edits.

Cleanup reports candidate path, reason, size, warnings, total file count, and reclaimable bytes.

If a Lumi world operation is still running for the project, cleanup keeps `recovery/operation-draft.bin.lz4` and reports the skip instead of deleting it. If no operation is running, Lumi first restores or merges an interrupted save/amend draft into normal recovery. Cleanup keeps unresolved operation drafts and reports a warning instead of deleting potentially recoverable edits.

## Diagnostics

Diagnostics are read-only support checks.

They show:

- project integrity status;
- integrity errors;
- integrity warnings;
- supported integration availability;
- integration capability labels;
- recent recovery and operation journal entries.

Use Diagnostics when:

- a restore/import/cleanup workflow reports a storage problem;
- an external tool does not appear to be captured;
- support needs raw capability or journal information;
- you enabled debug logging and need to understand recent Lumi operations.

Diagnostics do not mutate the world or project history.

## External Builder Tools

Lumi supports normal Minecraft edits first, then integrates with common builder tools where safe.

WorldEdit and compatible FAWE:

- undo/redo chords route through the tool's native undo/redo commands;
- Lumi suppresses its own capture during the native command;
- Lumi updates pending work afterward;
- stable capability reporting uses public selection, clipboard, and schematic APIs when present.

Axiom:

- captured capability edits replay through Lumi undo/redo;
- tool-assisted breaks and placements use the state Lumi recorded;
- simple Axiom place/break buffers, such as bulldozer or fast-place style hand edits, become block-scoped undo actions;
- Axiom does not claim stable WorldEdit-style capabilities unless a stable API exists.

Other supported or conservatively captured builder paths can include FAWE-style chunk placement, Axion, AutoBuild, SimpleBuilding, Effortless Building, and Litematica/Tweakeroo placement paths when they reach normal Minecraft block or entity mutation paths.

External tool support is conservative. Unsupported tool internals may still be captured through normal Minecraft mutations, but Lumi does not promise tool-specific capabilities unless Diagnostics reports them.

## Commands

Normal project workflows stay in the UI.

### Onboarding

```mcfunction
/lumi-onboarding
```

Opens the short onboarding tour for the current singleplayer workspace. If the workspace does not exist, Lumi creates it like pressing `U`.

If interrupted recovered work exists, Lumi opens Recovery first so the safety prompt is not skipped.

### Help

```mcfunction
/lumi
/lumi help
```

Shows supported diagnostic commands and reminds the player to use the UI for workflows that mutate project history or the world.

### Status

```mcfunction
/lumi status
```

Shows:

- number of Lumi projects in the current world;
- active branch ids;
- active or most recent Lumi world operation;
- operation id, label, stage, progress, and detail text when available.

### Singleplayer Runtime Tests

```mcfunction
/lumi testing singleplayer
/lumi testing smoke
/lumi testing structures
```

These commands are for validation, not normal play.

They are singleplayer-only, require operator-level permission, refuse to start while another Lumi world operation is active, and need a small empty air volume above the player's current chunk.

`/lumi testing singleplayer` exercises real in-world Lumi services:

- project creation;
- world bootstrap storage;
- initial snapshots;
- snapshot section content refs;
- capture;
- recovery draft summaries;
- current diff;
- material delta;
- live undo/redo;
- save and amend;
- branch creation/switching;
- branch save;
- version compare;
- project and branch export;
- partial restore;
- full restore;
- integrity inspection;
- cleanup inspection;
- gameplay scenarios;
- performance budgets;
- large persisted history diagnostics;
- bulk apply diagnostics;
- structure-fixture diagnostics.

`/lumi testing smoke` runs the shorter project smoke path. It covers world bootstrap, the pre-open checkpoint manifest and opt-in backup budget, snapshot content refs, section-indexed patch reads, capture, pending diff, undo/redo, save/amend, branch save/export, partial restore, full restore, integrity, cleanup, and then stops before gameplay, large-history, bulk-apply, and structure-fixture diagnostics.

`/lumi testing structures` runs only the structure fixture diagnostics and skips the broader save/restore/gameplay/bulk phases. Generated observer/sticky-piston fixtures are strict rollback checks; saved `.nbt` fixtures verify interaction and undo/redo operation flow while logging dynamic redstone/entity snapshot drift as diagnostics.

`LUMI_SINGLEPLAYER_TEST_MODE=backup-stress` with `runClientGameTest` runs the client-only pre-open backup stress path. It creates a save, removes Lumi's fresh-world marker, writes 100k blocks across 400 chunks, measures world exit, opens through the real backup gate with a 1024 MiB backup budget, creates a Lumi world workspace, writes another 100k-block builder action, commits that change set to Lumi history, measures exit again, restores the raw pre-Lumi backup offline, reopens, and verifies all 100k positions.

Test logs are written under:

```text
<save>/lumi/test-logs/
```

### Removed Command Workflows

The following workflows intentionally do not have `/lumi` commands:

- project creation;
- save/amend;
- restore;
- branch create/switch;
- recovery restore/discard;
- archive import/export;
- cleanup apply;
- share/merge.

Keeping these workflows in the UI preserves confirmation screens, previews, operation progress, conflict review, and cancellation boundaries.

## Storage Path

Lumi stores workspace data under:

```text
<save>/lumi/projects/
```

Each project folder uses the suffix:

```text
.mbp
```

Shared world-origin metadata is stored at:

```text
<save>/lumi/world-origin.json
```

The origin manifest records:

- world seed;
- selected datapacks;
- per-dimension generator fingerprints;
- Lumi creation marker for conservative regeneration checks.

Old manifests without a Lumi creation marker are treated conservatively and are not eligible for automatic generator regeneration.

Before opening an existing pre-Lumi world without a completed Lumi checkpoint, the client shows an alpha checkpoint gate. Pressing `Got it!` records a quick pre-open safety checkpoint and opens the world after the manifest is complete. Fresh worlds created through Lumi are marked as Lumi-created and do not show this gate.

Lumi writes the one-time pre-mod backup under:

```text
<save>/lumi/pre-mod-backup/
```

By default the checkpoint is manifest-only, so Lumi does not scan and recompress every generated chunk before letting the player enter a large existing save. Set the `lumi.preModBackup.maxMiB` JVM property to a positive value before first open to enable compressed chunk payload capture. In that opt-in mode, the backup scan runs before world entry on Lumi's low-priority client backup thread, records the world seed, scans generated region chunks, skips pristine and visited-only chunks, and stores chunks with persistent payloads such as block entities, entities, or pending ticks as gzip-compressed raw NBT. The scan writes to staging storage first, then publishes the chunk set and manifest together. If the game or computer stops during backup creation, Lumi retries on the next open instead of treating the partial attempt as restorable. The server bootstrap later verifies the completed manifest instead of repeating the backup.

The vanilla Edit World screen shows `RESTORE FROM LUMI BACKUP` when a completed Lumi pre-mod backup has restorable chunk payloads. Pressing it opens a red confirmation screen: the restore button remains disabled until the player checks the acknowledgement box, and Cancel returns without changing the save. Confirming restores the backed-up raw chunks to their pre-Lumi state while keeping Lumi project history and commits on disk.

History packages are stored under:

```text
<game root>/lumi-projects/
```

Runtime test reports are stored under:

```text
<save>/lumi/test-logs/
```

See [storage-format.md](storage-format.md) for the full storage layout.

## Limits And Guarantees

Lumi does not replace full world backups.

Lumi history focuses on tracked build changes in the current workspace. It does not restore:

- player inventory;
- time of day;
- gamerules;
- unrelated untouched chunks;
- arbitrary mod state outside captured block/entity payloads.

Lumi avoids heavy restore preparation on the server tick. Large restore, undo/redo, merge, and recovery applies prepare work off-thread, then apply bounded batches through operation progress.

One map mutation operation is expected per workspace at a time.

Detached old saves stay on disk for safety. Soft-deleted saves and branches are hidden from normal UI but remain on disk.

## Troubleshooting

If Lumi does not open:

- confirm you are in a singleplayer world;
- check whether a recovery prompt opened first;
- confirm the `Open current workspace` key is still bound;
- check actionbar messages for permission or project-open errors.

If save is disabled:

- there may be no unsaved tracked changes;
- wait for an active operation to finish;
- make an explicit edit in the workspace and try again.

If undo/redo does nothing:

- the live stack may be empty;
- the game may have restarted since the action;
- an operation may be active;
- redstone or piston fallout may still be inside the short settle window.

If restore is blocked:

- read the confirmation/status banner;
- save or discard pending recovery first;
- check whether required history payloads are missing or corrupt;
- use Diagnostics for integrity errors and warnings.

If import/combine is blocked:

- check the validation message on `Import / Export`;
- decide every same-area conflict zone;
- confirm the combine would import at least one change;
- verify the package belongs to a compatible project lineage.

If overlays look missing:

- press `H` to toggle compare visibility;
- hold the Lumi action button for x-ray while compare is active;
- make sure you are within render distance of the highlighted chunks;
- remember compare highlight takes priority over undo/redo preview.
