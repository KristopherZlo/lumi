# Storage Format

## Root location

For each world, Luma stores project data under:

```text
<world>/luma/projects/
```

Each project is one folder with the suffix `.mbp`.

Example:

```text
MyHouse.mbp/
```

## Folder layout

Current project layout:

```text
<project>.mbp/
  project.json
  variants.json
  versions/
  patches/
  snapshots/
  previews/
  recovery/
  cache/
  locks/
```

## File roles

### `project.json`

Stores the project metadata, including:

- schema version
- project id and name
- tracked bounds
- dimension id
- active and main variant ids
- timestamps
- project settings
- favorite/archive flags

### `variants.json`

Stores the full variant list. Each variant keeps its own head version id and base version id.

### `versions/*.json`

Stores one `ProjectVersion` record per saved version.

Important fields:

- `parentVersionId`
- `snapshotId`
- `patchIds`
- `versionKind`
- `stats`
- `preview`
- `sourceInfo`

### `patches/*.json.lz4`

Patch files are the primary history format for new saves.

Each patch file stores:

- patch metadata
- the ordered list of `BlockChangeRecord` entries for that save

The patch payload is JSON compressed with LZ4 frame compression.

### `snapshots/*.nbt.lz4`

Checkpoint snapshots store a full project-area block state for reconstruction anchors.

Snapshots are saved as:

- NBT payload
- compressed with LZ4 frame compression

They are currently created:

- for the initial version
- for legacy migration saves
- every configured snapshot interval
- when the changed volume threshold is exceeded
- when the patch chain gets too long
- before restore, if safety snapshot is enabled and a draft exists

### `previews/*.png`

Preview images are lightweight top-down PNG files generated per version when preview generation is enabled.

Preview generation failure does not block version save.

### `recovery/draft.json`

Stores the current in-progress recovery draft when tracked changes exist but have not been saved as a version.

### `recovery/journal.json`

Stores recovery, restore, migration, and other workflow events shown in the Log tab.

### `cache/`

Reserved for future cache artifacts and rebuildable derived data.

### `locks/`

Reserved for future coordination and lock files.

## Legacy handling

The current code keeps legacy snapshot-only projects readable.

Current behavior:

- legacy projects can be loaded
- the first new save after loading legacy data writes a patch-era version on top of that project
- no broader compatibility layer is currently implemented beyond the current local dev format

