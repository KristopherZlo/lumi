# Commands

Commands are the fallback interface for Luma. The menu flow is the primary UX, but the core mandatory workflow is also available from commands.

## Project commands

### List projects

```mcfunction
/luma list
```

Shows the known projects for the current world.

### Create a project

```mcfunction
/luma create <name> <from> <to>
```

Example:

```mcfunction
/luma create tower 0 64 0 31 120 31
```

Creates the project, captures the initial checkpoint snapshot, and creates the first version on `main`.

### Save a version

```mcfunction
/luma save <project> [message]
```

Examples:

```mcfunction
/luma save tower
/luma save tower Front facade completed
```

Saves the currently tracked draft as a new version for the active variant.

### Restore a version

```mcfunction
/luma restore <project> [version]
```

Examples:

```mcfunction
/luma restore tower
/luma restore tower v0003
```

If `version` is omitted, Luma restores the current head of the active variant.

## Variant commands

### List variants

```mcfunction
/luma variant list <project>
```

Shows the known variants and their head versions.

### Create a variant

```mcfunction
/luma variant create <project> <variant> [fromVersion]
```

Examples:

```mcfunction
/luma variant create tower alt-roof
/luma variant create tower alt-roof v0002
```

If `fromVersion` is omitted, the new variant starts from the active variant head.

### Switch variant

```mcfunction
/luma variant switch <project> <variant>
```

Example:

```mcfunction
/luma variant switch tower alt-roof
```

Switches the active variant and restores that variant head into the world.

## Recovery commands

### Check recovery status

```mcfunction
/luma recovery status <project>
```

Shows whether a recovery draft exists and how many tracked changes it contains.

### Restore a recovery draft

```mcfunction
/luma recovery restore <project>
```

Re-applies the recovery draft back into the world.

### Discard a recovery draft

```mcfunction
/luma recovery discard <project>
```

Deletes the pending draft without applying it.

## Notes

- Commands currently target the local integrated server workflow.
- Compare, preview refresh, project settings, and dashboard filters are currently menu-first features.
- Recovery draft save-as-version is available from the Recovery screen, not from a command yet.

