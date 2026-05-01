# Commands

Commands are diagnostics and local testing tools. Lumi project creation, save, restore, branches, recovery, share, merge, import/export, cleanup, settings, and compare workflows remain UI-first for normal use.

All `/lumi` commands require an operator-level player permission set. In singleplayer, that means cheats must be enabled for the world.

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

## Singleplayer Testing

```mcfunction
/lumi testing singleplayer
```

Runs an integrated-server regression suite against the real in-world Lumi services. The command is singleplayer-only, refuses to start while another Lumi world operation is active, and needs a small empty air volume above the player's current chunk.

The suite shows phase progress in chat, records every check as pass/fail, and keeps running after failed checks when the next workflow can still be exercised. Hard workflow errors are logged, then the runner skips to the next safe phase or cleanup.

The suite creates a temporary bounded project, then exercises project creation, initial snapshots, capture, recovery draft summaries, current diff, material delta, live undo/redo, save, amend, branch creation/switching, branch save, version compare, project export, branch export, partial restore, full restore, integrity inspection, and cleanup inspection. It also checks a lightweight performance budget so undo/redo and restore operations remain scoped instead of replaying broad world data, and so the suite does not introduce large synchronous tick slices.

After the normal workflow budget checks, the command runs a large storage-backed history diagnostic. It captures about 262k placed blocks into a real main-branch save, captures a divergent 65k-block branch save, restores the main save, restores the branch head, and verifies both the restored blocks and the active branch metadata. This covers large persisted save files, branch divergence, and restore behavior through the same storage and world-operation services used by the UI.

The command then runs bulk apply diagnostics. These prepare and apply three large block batches: a dense rewrite-friendly `64x64x64` case, a same-sized block-entity fallback case, and a sparse direct-section case with about 250k changed cells spread across high-altitude chunks. The diagnostics preflight target cells for air before writing; a scenario is skipped instead of overwriting existing player blocks when any target cell is occupied. The test log records save/restore/fill/delete durations, work units, and fast-apply counters such as rewrite/native/direct/fallback sections, packets, light checks, apply ticks, work-per-tick counters, light-drain ticks/duration, and fallback reasons. It also logs per-gameplay-scenario timings so the performance budget can identify the slow interaction. It removes test blocks and archives the temporary project when the run finishes or fails.

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

Keeping these workflows in the UI preserves confirmation screens, previews, operation progress, conflict review, and cancellation boundaries.
