# Lumi Automated Behavior Coverage

This is the product behavior contract used to choose focused automated coverage.
Keep it short and update it when history behavior or the executable test surface changes.

## History

- Save consumes pending tracked work, writes one visible version, moves the active
  branch head, and clears the draft.
- Amend replaces the visible branch or zone head. It writes a new version from
  the old head parent plus old head changes plus draft changes. The superseded
  version remains on disk but is hidden from visible history.
- Rename and tag edits are metadata-only. They do not move branch heads, create
  versions, or change the world.
- Full restore applies the target version, moves the target branch head to that
  version, and clears pending draft state.
- Partial restore applies only the requested region and leaves the restored
  region as pending draft work. It does not create a saved version by itself.
- Undo, redo, and quick rollback apply through the world operation model and must
  not be captured as new user edits.

## Branches And Merge

- Creating a branch writes metadata only and leaves the world untouched.
- Switching branches restores the selected branch head and rejects pending drafts.
- Local merge rejects merging a branch into itself and requires an empty draft.
- A successful local merge applies source-only changes, writes one
  `VersionKind.MERGE` version on the active branch, moves only the active branch
  head, and leaves the source branch head unchanged.

## Zones

- Creating and selecting zones persists zone state per actor.
- Zone save/amend with an active touched zone consumes only changes inside that
  zone. Out-of-zone changes remain pending.
- Zone amend follows the same replacement-head rule as normal amend.
- Zone history shows only visible zone versions.

## Settings And Operations

- Project settings persist through reload and are sanitized on write.
- Preview, safety-checkpoint, debug, auto-version, auto-checkpoint, and HUD flags
  remain project settings, not global state.
- Only one world operation may run at a time. A second save/amend/restore/merge
  while one is active must fail as busy and must not create another version.

## Runtime Scope

- Client GameTests exercise current screen action wiring and compare/pending overlay rendering in an integrated world.
- The pending-overlay GameTest creates a real draft, holds Left Alt, requires a visible mesh, then requires it to clear on release.
- Server GameTests keep focused Minecraft regressions for falling-block entity capture and crop random ticks.
- Domain, storage, capture, restore, undo/redo, and recovery rules are verified by focused JUnit tests with explicit assertions.
- Idle startup comparison runs matching vanilla and Lumi workloads; it does not claim save/restore workflow coverage.
- Mode-driven journeys that call domain services directly are not accepted as product behavior coverage.
