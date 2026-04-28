# Commands

Commands are diagnostics and local testing tools. Lumi project creation, save, restore, branches, recovery, share, merge, import/export, cleanup, settings, and compare workflows remain UI-only for normal use.

All `/lumi` commands require an operator-level player permission set. In singleplayer, that means cheats must be enabled for the world.

## Help

```mcfunction
/lumi
/lumi help
```

Shows the currently supported diagnostic commands and reminds the player to use the Lumi UI for workflows that mutate project history or the world.

## Status

```mcfunction
/lumi status
```

Shows:

- the number of Lumi projects in the current world and their active branch ids
- the active or most recent Lumi world operation, including operation id, label, stage, progress, and detail text when available

## Singleplayer Testing

```mcfunction
/lumi testing singleplayer
```

Runs an integrated-server regression suite against the real in-world Lumi services. The command is singleplayer-only, refuses to start while another Lumi world operation is active, and needs an empty `5x4x5` air volume above the player's current chunk.

The suite creates a temporary bounded project, then exercises project creation, initial snapshots, capture, recovery draft summaries, current diff, material delta, live undo/redo, save, amend, branch creation/switching, branch save, version compare, project export, branch export, partial restore, full restore, integrity inspection, and cleanup inspection. It removes the test blocks and archives the temporary project when the run finishes or fails.

## Removed Command Workflows

The following workflows intentionally no longer have `/lumi` commands:

- project creation
- save/amend
- restore
- branch create/switch
- recovery restore/discard
- archive import/export
- cleanup apply
- share/merge

Keeping these workflows in the UI preserves confirmation screens, previews, operation progress, conflict review, and cancellation boundaries.
