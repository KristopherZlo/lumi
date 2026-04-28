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

Runtime test logs are stored at:

```text
<world>/lumi/test-logs/
```

Project history archives and share packages exported from the UI are stored at:

```text
<game>/lumi-projects/
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
  preview-requests/
  recovery/
  cache/
  locks/
```

## File roles

### `world-origin.json`

Stores the world-level origin manifest shared by all automatic dimension workspaces.

Important fields:

- schema version
- level name
- Minecraft version
- data version
- world seed
- `createdWithLumi`
- datapack fingerprint
- per-dimension generator identity, biome source identity, sea level, and generator fingerprint
- timestamps

Automatic dimension workspaces use this manifest to define the meaning of the `WORLD_ROOT` / `Initial` history node.
Legacy manifests without `createdWithLumi` are treated as `createdWithLumi = false`, so automatic generator-based restore strategies stay disabled unless the world was positively marked by Lumi.
Once written, origin fingerprints are preserved as the original restore-safety baseline. Later datapack or generator changes are compared against these stored values instead of overwriting them during startup.
If this manifest is malformed, Lumi moves it aside as `world-origin.json.corrupt-<timestamp>` and regenerates the manifest from the current world instead of blocking the workspace UI.

### `test-logs/singleplayer-<timestamp>.log`

Stores the detailed report for `/lumi testing singleplayer`.

Each log includes:

- start and finish timestamps
- total passed and failed checks
- phase progress messages
- per-check PASS/FAIL entries
- stack traces for unexpected phase or operation errors

These logs are diagnostic artifacts only. They are not referenced by project history, cleanup policies, import/export packages, or restore workflows.

### `project.json`

Stores the project metadata, including:

- schema version
- project id and name
- tracked bounds
- dimension id
- active and main variant ids
- timestamps
- project settings:
  - `autoVersionsEnabled`
  - `autoVersionMinutes`
  - `sessionIdleSeconds`
  - `snapshotEveryVersions`
  - `snapshotVolumeThreshold`
  - `safetySnapshotBeforeRestore`
  - `previewGenerationEnabled`
  - `debugLoggingEnabled`
  - `workspaceHudEnabled`
- legacy favorite flag and archive flag

Older project files may omit `workspaceHudEnabled`; Lumi treats the missing value as `true` so existing workspaces keep the top-right panel visible until the user disables it in Settings.

### `variants.json`

Stores the full variant list. Each variant keeps its own head version id and base version id.
Variant ids are generated from the branch name and receive numeric suffixes when distinct names normalize to the same id.

Restore and amend workflows move variant heads by rewriting this file. Older detached version files are left on disk for safety even when they are no longer reachable from a live variant head.
The client history view still lists those detached versions after a restore-style reset.

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

Patch payloads are the primary history format for tracked saves. New payloads use binary schema v6. The filename suffix remains `.bin.lz4`, but v6 is not one monolithic LZ4 frame. It is a small uncompressed Lumi header followed by independently compressed per-chunk LZ4 frames. The chunk offsets and lengths in `PatchChunkSlice` are physical file offsets for those frames, so readers can seek directly to selected chunks.

Current payload characteristics:

- chunk-addressable per-chunk LZ4 frames for schema v6
- chunk-sorted records
- per-chunk local-position ordering
- chunk-local palettes for block states
- chunk-local palettes for block entity payloads
- per-chunk entity diff records with entity id, entity type, nullable old full-NBT payload, and nullable new full-NBT payload
- block-only saves write empty entity sections, and schema v3/v4 patch payloads still load as block-only/entity-empty payloads
- schema v3-v5 legacy payloads still load from the older single LZ4 stream format
- first-old / last-new semantics preserved by `TrackedChangeBuffer` before persistence
- runtime-only redstone state flips and piston animation states are filtered before new patch payloads are written

`PatchMetaRepository` reads `*.meta.json`, while `PatchDataRepository` reads and writes `*.bin.lz4`.
Patch repositories expose persisted block/entity changes only. Minecraft-layer preparers convert those records into apply batches after the payload has been read off-thread.
Partial restore uses the metadata chunk index to load only v6 chunk frames that intersect the selected bounds. Legacy v3-v5 payloads remain compatible, but selected-region reads must still scan and filter the legacy stream.

### `snapshots/<snapshotId>.bin.lz4`

Checkpoint snapshots store a full project-area block state for reconstruction anchors.

Current snapshot characteristics:

- LZ4 frame compressed binary stream
- chunk -> section -> palette structure
- only non-empty sections are stored
- block entities are kept in a sparse side table keyed by local block index
- schema v5 writes per-chunk entity snapshots; schema v3/v4 snapshots still load as block-only snapshots
- `piston_head` and `moving_piston` states are normalized to air during new snapshot capture, and piston bases are stored retracted
- restore planning can list snapshot chunks by scanning the length-prefixed structure and skipping NBT payload bytes, without materializing `SnapshotData` or deserializing block/entity tags
- live chunk capture is performed on the Minecraft server thread into immutable compact payloads; snapshot storage only serializes and reads those prepared payloads
- snapshot readers return persisted payloads, while Minecraft-layer preparers convert them into apply batches off the tick-thread path

They are currently created:

- for the initial version
- for legacy migration saves
- every configured snapshot interval
- when the configured changed-volume threshold is exceeded for bounded projects

Whole-dimension projects do not create volume-triggered snapshots. They rely on the metadata-backed `WORLD_ROOT`, patch replay, tracked baselines, and the configured snapshot interval.

### `previews/*.png`

Preview images are textured isometric PNG files generated on the client per version when preview generation is enabled.

Preview coverage is resolved from the actual changed block positions first, with a small context padding around that span. If precise change positions are unavailable, Lumi falls back to the touched chunk span for that save.

Preview generation failure does not block version save.

Cleanup may remove preview PNGs that are no longer referenced by any version manifest.

### `preview-requests/<versionId>.json`

Stores lightweight pending preview capture jobs for the client renderer.

Important fields:

- `versionId`
- `dimensionId`
- `bounds`, usually tightened to the changed block span with safe padding
- `requestedAt`

These files let server-side save and refresh workflows queue preview work without trying to render textured images on the server thread or background server executors.

### `recovery/draft.bin.lz4`

Stores the current compacted recovery base snapshot in schema v4 binary format. Schema v3 drafts still load as block-only/entity-empty drafts.

### `recovery/draft.wal.lz4`

Stores append-only recovery draft updates as an LZ4-compressed write-ahead log.

The active in-memory `TrackedChangeBuffer` is periodically snapshotted into an immutable `RecoveryDraft` and queued to the low-priority capture-maintenance executor instead of rewriting one large JSON file for every change on the server tick. Recovery drafts now carry block changes and entity spawn/remove/update diffs. Once the WAL reaches the compaction threshold, the latest entry is rewritten into `draft.bin.lz4` and the WAL is removed.

### `recovery/operation-draft.bin.lz4`

Stores the draft currently being saved or amended.

This file is separate from `draft.bin.lz4` and `draft.wal.lz4`. Live capture never resumes it. If the player edits blocks while a save is still running, those edits start a new recovery draft and are not merged into the in-progress version.

Maintenance cleanup may delete this file only when no Lumi world operation is active for the project.

### `recovery/journal.json`

Stores recovery, restore, migration, and other workflow events shown in the Log tab.

### `cache/`

Reserved for future cache artifacts and rebuildable derived data.

The `cache/baseline-chunks/` subtree is not rebuildable without touching the live world. It is part of archive export/import and must not be treated as disposable cache data by maintenance workflows. Each baseline file is written from a prepared compact chunk snapshot payload captured on the server thread, then compressed and persisted later by the capture-maintenance executor.
Other cache files are treated as disposable cleanup candidates.

### `locks/`

Reserved for future coordination and lock files.

## Legacy handling

The current code keeps legacy snapshot-only projects readable at the project/version metadata level.

Current behavior:

- legacy projects can be loaded
- the first new save after loading legacy data writes a patch-era version on top of that project
- no compatibility layer is provided for older development-era patch or recovery payload formats

## Archive format

Version manifests may use `versionKind = PARTIAL_RESTORE` for region-scoped restores. The patch payload uses the normal block/entity-change format; the semantic difference is that the active variant advances to this new version instead of moving its head back to the historical target version.

Project import/export uses zip archives stored by default in the game-root `lumi-projects` folder. Each archive contains:

- `manifest.json`
- `project/project.json`
- `project/variants.json`
- `project/versions/*`
- `project/patches/*`
- `project/snapshots/*`
- `project/cache/baseline-chunks/*`
- optional `project/previews/*`
- optional `project/recovery/journal.json`

The archive manifest now carries a scope descriptor. Full-project archives keep `scope = PROJECT`. Variant share packages keep `scope = VARIANT` plus the selected variant id, name, base version id, and head version id.
The manifest also records whether preview PNGs were included. Import / Export exposes that as a UI toggle; disabling it keeps the package focused on durable history payloads.

Variant share packages still use the same zip format, but they only include the selected variant lineage and the payloads that lineage references. On import, Lumi rewrites the imported project metadata so the review project exposes just that imported variant as its active line.
Deleting an imported review package from Import / Export removes that review project folder after Lumi verifies it has the same project id as the current target project.

Recovery draft payloads are intentionally excluded from archives, so export/import remains focused on stable project history rather than live unsaved state.

## Cleanup policy

Current cleanup is conservative and UI-driven:

- dry-run first
- delete only unreferenced snapshot payloads, orphaned preview PNGs, disposable cache files outside `baseline-chunks`, and stale `operation-draft`
- never delete baseline chunks or files still referenced by version manifests
