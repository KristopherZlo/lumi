# Storage Format

## Root location

For each world, Lumi stores project data under:

```text
<world>/lumi/projects/
```

Each project is one folder with the suffix `.mbp`.

Shared world-level metadata is stored at:

```text
<world>/lumi/world-origin.json
```

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

### `world-origin.json`

Stores the world-level origin manifest shared by all automatic dimension workspaces.

Important fields:

- level name
- Minecraft version
- data version
- world seed
- per-dimension generator identity, biome source identity, and sea level
- timestamps

Automatic dimension workspaces use this manifest to define the meaning of the `WORLD_ROOT` / `Initial` history node.

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

Version manifests stay lightweight. They are written only after referenced patch and snapshot payloads have been written successfully.

Whole-dimension workspaces now start with a metadata-backed `WORLD_ROOT` version. That root version has:

- empty `patchIds`
- empty `snapshotId`
- `versionKind = WORLD_ROOT`
- a user-facing message of `Initial`

### `patches/<patchId>.meta.json`

Stores the patch metadata and chunk index for one version payload.

Important fields:

- project id
- version id
- payload filename
- `PatchChunkSlice` entries with chunk coordinates, record count, byte offset, and byte length
- aggregated patch stats

Metadata reads must not require deserializing the full payload.

### `patches/<patchId>.bin.lz4`

Patch payloads are the primary history format for tracked saves in schema v3.

Current payload characteristics:

- LZ4 frame compressed binary stream
- chunk-sorted records
- per-chunk local-position ordering
- chunk-local palettes for block states
- chunk-local palettes for block entity payloads
- first-old / last-new semantics preserved by `TrackedChangeBuffer` before persistence

`PatchMetaRepository` reads `*.meta.json`, while `PatchDataRepository` reads and writes `*.bin.lz4`.

### `snapshots/<snapshotId>.bin.lz4`

Checkpoint snapshots store a full project-area block state for reconstruction anchors.

Current snapshot characteristics:

- LZ4 frame compressed binary stream
- chunk -> section -> palette structure
- only non-empty sections are stored
- block entities are kept in a sparse side table keyed by local block index

They are currently created:

- for the initial version
- for legacy migration saves
- every configured snapshot interval
- when the configured changed-volume threshold is exceeded
- before restore, if safety snapshot is enabled and a draft exists

### `previews/*.png`

Preview images are lightweight top-down PNG files generated per version when preview generation is enabled.

Preview generation failure does not block version save.

### `recovery/draft.bin.lz4`

Stores the current compacted recovery base snapshot in schema v3 binary format.

### `recovery/draft.wal.lz4`

Stores append-only recovery draft updates as an LZ4-compressed write-ahead log.

The active in-memory `TrackedChangeBuffer` is periodically flushed into recovery storage instead of rewriting one large JSON file for every change. Once the WAL reaches the compaction threshold, the latest entry is rewritten into `draft.bin.lz4` and the WAL is removed.

### `recovery/journal.json`

Stores recovery, restore, migration, and other workflow events shown in the Log tab.

### `cache/`

Reserved for future cache artifacts and rebuildable derived data.

### `locks/`

Reserved for future coordination and lock files.

## Legacy handling

The current code keeps legacy snapshot-only projects readable at the project/version metadata level.

Current behavior:

- legacy projects can be loaded
- the first new save after loading legacy data writes a patch-era version on top of that project
- no compatibility layer is provided for older development-era patch or recovery payload formats
