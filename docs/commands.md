# Commands

Commands are diagnostic tools. Lumi project creation, save, restore, branches, recovery, share, merge, import/export, cleanup, settings, and compare workflows remain UI-first for normal use.

All `/lumi` commands require an operator-level player permission set. In singleplayer, that means cheats must be enabled for the world.

Runtime regression coverage does not live under `/lumi` commands. Use the Gradle test-client and GameTest profiles for the integrated singleplayer harness.

The onboarding replay command is client-side and is intentionally separate from `/lumi`, so it does not conflict with the server diagnostic command tree.

## Onboarding

```mcfunction
/lumi-onboarding
```

Opens the short Lumi onboarding tour for the current singleplayer workspace. If the workspace does not exist yet, Lumi creates it the same way as pressing `U`.

Opening the workspace still follows Lumi's normal local access checks. If interrupted recovered work exists, Lumi opens the recovery screen first so the player does not skip the safety prompt.

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

## Runtime Regression Profiles

The client GameTest and test-client profiles run the integrated singleplayer harness directly. They are not exposed as in-world `/lumi` commands.

Each run writes a detailed log to:

```text
<save>/lumi/test-logs/singleplayer-<timestamp>.log
```

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
- runtime regression suites

Keeping these workflows in the UI preserves confirmation screens, previews, operation progress, imported-package trust checks, and cancellation boundaries.
