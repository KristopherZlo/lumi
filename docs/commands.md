# Commands

Commands are diagnostics only. Lumi project creation, save, restore, variants, recovery, share, merge, import/export, cleanup, settings, and compare workflows are UI-only.

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

- the number of Lumi projects in the current world and their active variant ids
- the active or most recent Lumi world operation, including operation id, label, stage, progress, and detail text when available

## Removed Command Workflows

The following workflows intentionally no longer have `/lumi` commands:

- project creation
- save/amend
- restore
- variant create/switch
- recovery restore/discard
- archive import/export
- cleanup apply
- share/merge

Keeping these workflows in the UI preserves confirmation screens, previews, operation progress, conflict review, and cancellation boundaries.
