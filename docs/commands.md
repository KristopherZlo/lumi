# Commands

Commands are the fallback interface for Lumi. The menu flow is the primary UX, but the core mandatory workflow is also available from commands.

All `/lumi` commands now require an operator-level player permission set. In singleplayer, that means cheats must be enabled for the world.

## Project commands

### List projects

```mcfunction
/lumi list
```

Shows the known projects for the current world.

### Create a project

```mcfunction
/lumi create <name> <from> <to>
```

Example:

```mcfunction
/lumi create tower 0 64 0 31 120 31
```

Creates the project, captures the initial checkpoint snapshot, and creates the first version on `main`.

### Save a version

```mcfunction
/lumi save <project> [message]
```

Examples:

```mcfunction
/lumi save tower
/lumi save tower Front facade completed
```

Saves the currently tracked draft as a new version for the active variant.

### Restore a version

```mcfunction
/lumi restore <project> [version]
```

Examples:

```mcfunction
/lumi restore tower
/lumi restore tower v0003
```

If `version` is omitted, Lumi restores the current head of the active variant.

After a successful restore, Lumi also resets the active variant head to the restored version.

## Variant commands

### List variants

```mcfunction
/lumi variant list <project>
```

Shows the known variants and their head versions.

### Create a variant

```mcfunction
/lumi variant create <project> <variant> [fromVersion]
```

Examples:

```mcfunction
/lumi variant create tower alt-roof
/lumi variant create tower alt-roof v0002
```

If `fromVersion` is omitted, the new variant starts from the active variant head.

### Switch variant

```mcfunction
/lumi variant switch <project> <variant>
```

Example:

```mcfunction
/lumi variant switch tower alt-roof
```

Switches the active variant and restores that variant head into the world.

## Recovery commands

### Check recovery status

```mcfunction
/lumi recovery status <project>
```

Shows whether a recovery draft exists and how many tracked changes it contains.

### Restore a recovery draft

```mcfunction
/lumi recovery restore <project>
```

Re-applies the recovery draft back into the world.

### Discard a recovery draft

```mcfunction
/lumi recovery discard <project>
```

Deletes the pending draft without applying it.

## Archive commands

### Export a project archive

```mcfunction
/lumi archive export <project> [includePreviews]
```

Examples:

```mcfunction
/lumi archive export tower
/lumi archive export tower true
```

Writes a zip archive under `<save>/lumi/exports/`.

The archive contains project metadata, variants, versions, patches, snapshots, baseline chunks, and the recovery journal. Preview PNGs are included only when `includePreviews` is `true`. Recovery draft payloads are excluded.

### Import a project archive

```mcfunction
/lumi archive import <archivePath>
```

Examples:

```mcfunction
/lumi archive import tower-history-20260421-083000.zip
/lumi archive import C:\exports\tower-history-20260421-083000.zip
```

Imports the archive into the current world's Lumi project storage. Relative paths resolve from `<save>/lumi/exports/`.

## Cleanup commands

### Inspect cleanup candidates

```mcfunction
/lumi cleanup inspect <project>
```

Shows a dry-run summary for orphaned preview files, unreferenced snapshots, disposable cache files outside `baseline-chunks`, and stale operation drafts.

### Apply cleanup

```mcfunction
/lumi cleanup apply <project>
```

Deletes the candidates from the dry-run set.

If the project has an active Lumi world operation, the cleanup flow keeps `recovery/operation-draft.bin.lz4` and reports that skip in the command response.

## Notes

- Commands currently target the local integrated server workflow.
- Compare, preview refresh, project settings, import/export, cleanup, and dashboard filters are currently menu-first or command-first features.
- Recovery draft save-as-version is available from the Recovery screen, not from a command yet.
