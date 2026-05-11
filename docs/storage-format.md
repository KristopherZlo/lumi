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

World-level installation markers are stored at:

```text
<world>/lumi/created-with-lumi.marker
<world>/lumi/pre-mod-backup/alpha-backup-warning-acknowledged.txt
```

The first-entry pre-open checkpoint and optional pre-mod backup payloads are stored at:

```text
<world>/lumi/pre-mod-backup/
```

Runtime test logs are stored at:

```text
<world>/lumi/test-logs/
```

The optional runtime load log is stored at:

```text
<game>/logs/lumi-load.log
```

Focused lighting and restore block-apply diagnostics are stored at:

```text
<game>/logs/lumi-light.log
<game>/logs/lumi-block-apply.log
```

Project history archives and share packages exported from the UI are stored at:

```text
<game>/lumi-projects/
```

Client-only onboarding state is stored at:

```text
<game>/config/lumi-client.json
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
  history-tombstones.json
  versions/
    index.json
  patches/
  snapshots/
  previews/
  preview-requests/
  recovery/
  cache/
    content/
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

### `created-with-lumi.marker`

Marks a save folder that was created through Minecraft's world creation flow while Lumi was installed.

The marker is intentionally separate from `world-origin.json` because it must exist before server-side world-origin bootstrap runs. Fresh Lumi-created worlds use it to skip the pre-Lumi alpha backup warning. Existing worlds without this marker are treated conservatively unless their origin manifest already has `createdWithLumi = true` or their pre-mod backup is complete.

### `pre-mod-backup/alpha-backup-warning-acknowledged.txt`

Records that the player accepted the alpha checkpoint gate before opening an existing pre-Lumi world without a completed Lumi checkpoint.

This file is not a backup manifest and does not imply that the backup scan completed. Lumi no longer treats it as sufficient to enter a pre-Lumi world; the gate repeats until `manifest.json` exists for the current seed.

### `pre-mod-backup/manifest.json`

Stores the one-time pre-open safety checkpoint created before Lumi first opens an existing pre-Lumi world.

The manifest records:

- schema version, currently `2`
- level name
- world seed
- classifier name
- maximum compressed payload budget
- per-dimension scanned, backed-up, and skipped chunk counts
- visited-only and storage-budget skip counts
- compressed backup bytes
- start and completion timestamps

When full chunk backup is enabled, chunk payloads live under `pre-mod-backup/chunks/<dimension>/chunk_<x>_<z>.nbt.gz`.
They contain raw chunk NBT compressed with gzip. Backup attempts first write chunk payloads under `pre-mod-backup/staging/attempt-*`; only a fully scanned attempt is promoted to the final `chunks/` directory, and `manifest.json` is written after that promotion. If the game or computer stops during an attempt, the next run discards the staging directory. If interruption happens while replacing a previous completed backup, Lumi restores the previous manifest/chunk set before retrying, so a completed manifest never intentionally points at a partial chunk set.

The default budget is `0 MiB`, so existing worlds get a manifest-only checkpoint without scanning and recompressing every generated chunk before entry. Set the `lumi.preModBackup.maxMiB` JVM property to a positive value before first open to enable the older full scan. That scan is storage-first: chunks whose only activity marker is non-zero `InhabitedTime` are treated as visited-only and skipped, because visited terrain can cover very large explored worlds without containing builder edits. Chunks with persistent payloads such as block entities, entities, or pending ticks are kept until the compressed backup budget is reached. Values less than or equal to zero keep only the manifest and skip chunk payload writes.

The vanilla Edit World restore action uses the completed manifest and the stored chunk payload files as the only source of truth. It writes each backed-up raw chunk NBT payload back into the matching region file and leaves `<world>/lumi/projects/` untouched, so Lumi commits and history packages remain available for diagnostics or future tooling. Chunks that were skipped by the backup policy are not regenerated or deleted during this restore.

### `test-logs/singleplayer-<timestamp>.log`

Stores the detailed report for `/lumi testing singleplayer`.

Each log includes:

- start and finish timestamps
- total passed and failed checks
- phase progress messages
- per-check PASS/FAIL entries
- stack traces for unexpected phase or operation errors
- completed prepared-apply metrics, including changed/skipped blocks, rewrite/native/direct/fallback section counts, packets, redstone updates, light checks, apply/work ticks, redstone/light-drain ticks/duration, and fallback reasons
- bulk apply diagnostic summaries for dense rewrite-friendly, block-entity fallback, and sparse direct-section batches when the singleplayer suite can reserve safe high-altitude target cells

These logs are diagnostic artifacts only. They are not referenced by project history, cleanup policies, import/export packages, or restore workflows.

### `<game>/logs/lumi-load.log`

Stores opt-in runtime load diagnostics when the JVM flag `-Dlumi.loadLog=true` is set.

Each log uses key-value rows and may include:

- `type="span"` rows for individual calls that exceeded `lumi.loadLog.slowMs`
- `type="summary"` rows with the highest cumulative `area`/`name` costs, counts, average time, and max time
- `type="operation-metrics"` rows with completed prepared-apply counters such as prepare, preload, apply, light/redstone finalize duration, work ticks, max apply tick time, and fallback reasons

The log is an operational artifact only. It is not stored in project folders, exported in history packages, or consumed by restore/cleanup workflows.

### `<game>/logs/lumi-light.log`

Stores focused lighting and shadow diagnostics when `-Dlumi.lightLog=true` is set. It is also enabled by `-Dlumi.loadLog=true`.

Rows include `light-refresh` scheduling, async light-check preparation, dirty chunk bounds, per-tick `checkBlock` drain counts, touched chunk unsaved marks, light-engine barrier waits, publish ticks, and completion summaries. The log is intended for bugs where restored blocks look correct until the world is reloaded and stale shadows return.

### `<game>/logs/lumi-block-apply.log`

Stores focused restore/rollback block apply diagnostics when `-Dlumi.blockApplyLog=true` is set. It is also enabled by `-Dlumi.loadLog=true`.

Rows include preparation, preload ticks, chunk-level set/delete target counts, native/rewrite/direct apply step timings, block-entity and entity tail work, per-apply-tick stop reasons, fallback summaries, and aggregate timing. It deliberately avoids per-block rows so diagnostic logging does not dominate large restores.

These logs are operational artifacts only. They are not stored in project folders, exported in history packages, or consumed by restore/cleanup workflows.

### `config/lumi-client.json`

Stores installation-level client UI state that is not part of any project and is not exported with history packages.

- `schemaVersion`
- `completedOnboardingVersion` (current tour version: `4`)
- `dismissedContextualHintIds`

Schema v2 adds the dismissed contextual hint id set. v1 files are normalized to v2 with the completed onboarding version preserved and no dismissed hints. Missing or malformed files are treated as incomplete onboarding and do not block project loading.

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
  - `autoCheckpointEnabled`
  - `workspaceHudEnabled`
- legacy favorite flag and archive flag

Older project files may omit `workspaceHudEnabled`; Lumi treats the missing value as `true` so existing workspaces keep the top-right panel visible until the user disables it in Settings.
Older project files may omit `autoCheckpointEnabled`; Lumi treats the missing value as `false`, so automatic checkpoints before large external edits remain opt-in.

### `variants.json`

Stores the full variant list. Each variant keeps its own head version id and base version id.
Variant ids are generated from the branch name and receive numeric suffixes when distinct names normalize to the same id.

Restore and amend workflows move variant heads by rewriting this file. Older detached version files are left on disk for safety even when they are no longer reachable from a live variant head.
The client history view still lists those detached versions after a restore-style reset.

### `history-tombstones.json`

Stores soft-deleted history ids for normal UI and lineage filtering.

Important fields:

- schema version
- tombstoned version ids
- tombstoned variant ids
- updated timestamp

Soft delete never removes version manifests, patch payloads, snapshots, previews, baseline chunks, or archive files. `ProjectService` and history screens filter tombstoned versions and branches from normal workflows while leaving the underlying files available for diagnostics or future cleanup tooling.

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

`versions/index.json` is an optional disposable cache for `VersionRepository.loadAll(...)`. It stores version records plus each manifest file's size and modification time. Lumi uses it only when every version manifest matches and no extra version JSON file exists; stale or corrupt indexes are ignored and rebuilt. Deleting `index.json` never changes restore correctness and the file is not a version manifest.

Whole-dimension workspaces now start with a metadata-backed `WORLD_ROOT` version. That root version has:

- empty `patchIds`
- empty `snapshotId`
- `versionKind = WORLD_ROOT`
- a user-facing message of `Initial`

Additional semantic version kinds:

- `MERGE`: a local or imported branch merge written as a normal patch-first save on the active branch
- `AUTO_CHECKPOINT`: a pending draft saved automatically before a large external edit
- `PARTIAL_RESTORE`: a selected-region restore written as a new save instead of moving the active branch head

### `patches/<patchId>.meta.json`

Stores the patch metadata and chunk index for one version payload.

Important fields:

- project id
- version id
- payload filename
- `PatchChunkSlice` entries with chunk coordinates, record count, byte offset, and byte length
- visible section fingerprints and visible change counts for preview bounds that ignore hidden builder-surface changes
- `entityChunkIndex` entries with entity id, stored frame chunk, old chunk, and new chunk membership
- aggregated patch stats

Metadata reads must not require deserializing the full payload. The chunk index lets selected block reads seek to matching frames. The visible section index lets preview scheduling resolve bounds without decoding payloads when the metadata is new enough. The entity chunk index lets restore and partial restore select entity frames by either old or new chunk membership, so move-out and move-in records stay visible without scanning the whole patch payload.

### `patches/<patchId>.bin.lz4`

Patch payloads are the primary history format for tracked saves. New payloads use binary schema v9. The filename suffix remains `.bin.lz4`, but v7+ is not one monolithic LZ4 frame. It is a small uncompressed Lumi header followed by independently compressed per-chunk LZ4 frames. The chunk offsets and lengths in `PatchChunkSlice` are physical file offsets for those frames, so readers can seek directly to selected chunks.

Current payload characteristics:

- chunk-addressable per-chunk LZ4 frames for schema v7, v8, and v9
- chunk-sorted records
- chunk -> section frames with a 4096-cell changed mask
- schema v9 chunk metadata carries per-section fingerprints with `xxHash64` for fast comparisons and `SHA-256` for durable content identity
- section-local old/new palettes for block states
- section-local old/new palettes for block entity payloads
- mask-order state and block-entity ids so restore can build `LumiSectionBuffer` batches without first materializing a flat per-block list
- schema v8+ writes a section-local hidden-change mask after the state/entity id arrays. Hidden changes are durable and replayable, but builder-facing stats, diffs, overlays, and preview bounds ignore them. Older v7 section frames and v6 point frames load with all block changes visible.
- per-chunk entity diff records with entity id, entity type, nullable old full-NBT payload, and nullable new full-NBT payload for non-player entity spawn/remove/update, including position and the saved entity NBT as captured by Minecraft; tick fields such as item age, motion, and pickup delay are not stripped before storage
- block-only saves write empty entity sections, and schema v3/v4 patch payloads still load as block-only/entity-empty payloads
- schema v3-v5 legacy payloads still load from the older single LZ4 stream format
- first-old / last-new semantics preserved by `TrackedChangeBuffer` before persistence
- settled redstone and mechanism state is stored with the normal block-state NBT already present in patch palettes. Lever/button `powered`, wire `power`, lamp `lit`, openable `open`, repeater/comparator properties, piston base `extended`, settled `piston_head`, and moved blocks are schema-compatible state deltas; no schema bump is needed because these properties already fit the existing block-state tag payload. Stabilization waits a short tick window after the last causal redstone or piston mutation and requeues dirty chunks that still contain `moving_piston` before writing dirty-chunk deltas, so the storage layer receives settled cells instead of the in-flight piston animation. Apply preparation may synthesize missing settled piston head/removal companions from a recorded piston base for replay safety, may recover a retracted base when a raw undo target still references a transient moving-piston base, and may replace normalized transient air at the expected head position when an extended base requires it, but storage still contains ordinary per-position old/new state tags. Runtime replay queues vanilla neighbor updates from redstone power/source transitions after stored blocks have been applied; it does not add storage records for pulses or update events. Only short-lived `moving_piston` animation state is normalized to air before new patch payloads are written

`PatchMetaRepository` reads `*.meta.json`, while `PatchDataRepository` reads and writes `*.bin.lz4`.
Patch repositories expose persisted block/entity changes only. Minecraft-layer preparers convert those records into apply batches after the payload has been read off-thread.
Direct partial restore uses the metadata chunk index to load only chunk frames that intersect the selected bounds. Entity reads use the old/new chunk index when present so moves across the selection boundary are included from their stored frame chunk. Schema v6/v7 chunk-addressable payloads and legacy v3-v5 payloads remain compatible, but selected-region reads must still scan and filter the legacy stream when no chunk or entity index is available. Non-direct partial restore reconstructs finite current and target states from `snapshots/`, `cache/baseline-chunks/`, and patch payloads before writing a normal `PARTIAL_RESTORE` patch; missing required payload files are treated as an invalid restore plan.
Patch readers bound NBT lengths, compressed/uncompressed frame lengths, palette counts, entity counts, and selected chunk slices before allocating buffers. A selected chunk slice whose stored frame coordinates or entity coordinates do not match the requested chunk is treated as corrupt storage.

### `snapshots/<snapshotId>.bin.lz4`

Checkpoint snapshots store a full project-area block state for reconstruction anchors.

Current snapshot characteristics:

- schema v7 uses a small uncompressed Lumi header followed by independently compressed per-chunk LZ4 frames; schema v6 keeps the same chunk-addressable layout without section content references and remains readable
- each v7 chunk frame header includes section fingerprints, optional section `ContentRef` entries, entity count, compressed length, and uncompressed length so selected reads can skip unrelated chunks without decompression
- prepared snapshot and baseline writes store immutable section payloads in `cache/content/<sha>.bin.lz4` and wire those refs into the frame index; the chunk frame still embeds the section payload so older v6-era read paths can be migrated safely
- chunk -> section -> palette structure
- only non-empty sections are stored
- block entities are kept in a sparse side table keyed by local block index
- schema v5 writes per-chunk non-player entity snapshots with position and persistent state; schema v3/v4 snapshots still load as block-only snapshots
- `moving_piston` states are normalized to air during new snapshot capture, but dirty redstone/piston stabilization first checks live chunks for `moving_piston` and delays reconciliation while that transient state is present. Settled `piston_head` states and piston bases, including `extended=true`, are stored as normal block states. Snapshot restore applies the stored section state directly; it does not replay piston events or derive piston bases from head-only states, and replay completion is still derived from explicit piston bases instead of schema-specific storage records
- restore planning can list v6 snapshot chunks by scanning frame headers without materializing `SnapshotData` or deserializing block/entity tags
- selected snapshot reads can materialize only requested chunk frames; legacy v3-v5 snapshots still require stream filtering after decompression
- live chunk capture is performed on the Minecraft server thread into immutable compact payloads; snapshot storage only serializes and reads those prepared payloads
- snapshot readers return persisted payloads, while Minecraft-layer preparers convert them into apply batches off the tick-thread path
- snapshot readers bound chunk, section, palette, palette-index, block-entity, entity, and NBT lengths before allocating arrays; impossible palette indexes are rejected as corrupt storage

They are currently created:

- for the initial version
- for legacy migration saves
- every configured snapshot interval
- when the configured changed-volume threshold is exceeded for bounded projects

Whole-dimension projects do not create volume-triggered snapshots. They rely on the metadata-backed `WORLD_ROOT`, patch replay, tracked baselines, and the configured snapshot interval.

### `previews/*.png`

Preview images are textured isometric PNG files generated on the client per version when preview generation is enabled.

Preview coverage is resolved from the visible changed block positions first, with a small context padding around that span. Hidden action-scoped growth changes are ignored so bonemeal crop/plant/amethyst updates do not move or invalidate screenshots, while unrelated ambient random ticks are skipped before storage. For saved patch versions, Lumi resolves bounds from section fingerprints and chunk metadata before considering coarser touched-chunk fallback, so preview scheduling does not need to decode the patch payload only to find bounds.

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

Stores the current compacted recovery base snapshot in schema v5 binary format. Schema v3 drafts still load as block-only/entity-empty drafts, and schema v4 drafts load with every block change visible.

### `recovery/draft.wal.lz4`

Stores append-only recovery draft updates as an LZ4-compressed write-ahead log.

The active in-memory `TrackedChangeBuffer` is periodically snapshotted into an immutable `RecoveryDraft` and queued to the low-priority capture-maintenance executor instead of rewriting one large JSON file for every change on the server tick. Recovery drafts now carry block changes, hidden block-change visibility bits, and entity spawn/remove/update diffs. They store only the durable working draft, not the in-memory live undo/redo action stack. Once the WAL reaches the compaction threshold, the latest entry is rewritten into `draft.bin.lz4` and the WAL is removed.

Recovery load reads the compacted base and WAL independently. If the WAL has a corrupt entry or truncated tail, Lumi quarantines the WAL and returns the base or the last valid WAL entry. When a last valid WAL entry exists, it is compacted back into `draft.bin.lz4` so the next load no longer depends on the damaged WAL.

### `recovery/operation-draft.bin.lz4`

Stores the draft currently being saved or amended.

This file is separate from `draft.bin.lz4` and `draft.wal.lz4`. Live capture never resumes it. If the player edits blocks while a save is still running, those edits start a new recovery draft and are not merged into the in-progress version. After the save commits, that new draft is rebased from the consumed head id to the newly saved version id without changing the draft payload schema.

When no Lumi world operation is active, project bootstrap, recovery loading, save/amend startup, and cleanup first treat this file as an interrupted operation. If no live draft exists, Lumi promotes it back to the visible recovery draft. If a compatible live draft exists, Lumi merges the operation draft first and the live draft second so later captured edits win while first-old/latest-new semantics are preserved. If the operation draft is incompatible with the live draft or belongs to another project id, Lumi keeps it in place and cleanup reports a warning instead of deleting it automatically.

### `recovery/journal.json`

Stores recovery, restore, migration, and other workflow events shown in the Log tab.

### `recovery/last-restore-return.json`

Stores the local return-before-restore pointer written before each full restore. It contains the project id, variant id, version id to restore back to, creation timestamp, and the restore target version id that caused the pointer to be written.

If a full restore starts with unsaved draft changes, Lumi first writes a `RESTORE` checkpoint and stores that checkpoint id as the return target. If the draft is clean, Lumi stores the current active branch head id. This file is local operational recovery state and is not included in project or variant archives.

### `cache/`

Reserved for future cache artifacts and rebuildable derived data.

The `cache/baseline-chunks/` subtree is not rebuildable without touching the live world. It is part of archive export/import and must not be treated as disposable cache data by maintenance workflows. Each baseline file is written from a prepared compact chunk snapshot payload captured on the server thread, then compressed and persisted later by the capture-maintenance executor.

`cache/content/` stores content-addressed immutable payload blobs keyed by SHA-256. These files are rebuildable from referenced history payloads or can be compacted by future idle maintenance, but compaction must never run on the server tick path.
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

Version manifests may use `versionKind = PARTIAL_RESTORE` for region-scoped restores, `MERGE` for branch merges, and `AUTO_CHECKPOINT` for pending drafts saved before large external edits. The patch payload uses the normal block/entity-change format; the semantic difference is how the version was produced and how the UI labels it.

Project import/export uses zip archives stored by default in the game-root `lumi-projects` folder. Each archive contains:

- `manifest.json`
- `project/project.json`
- `project/variants.json`
- `project/history-tombstones.json`
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

Archive import is a trust boundary. Lumi validates archive manifests before copying payloads: project folder names must be safe `.mbp` folder names, entry paths must stay under the supported `project/` subtrees, the manifest is size-bounded, and each copied entry is checked against per-file and total unpacked-size limits. Imported version, patch, snapshot, and preview identifiers must be storage-safe file ids, and patch metadata ids must match the patch id referenced by the version manifest. Malformed archives are rejected before they become normal project history.
New archive manifests store a SHA-256 digest for each payload entry. Export hashes source files from a stable regular-file read, rejects files that change while they are being copied into the zip, and import verifies each digest before the unpacked project is promoted from its temporary folder. Older archives without entry digests remain size-checked and importable.

Imported history payloads are executable world-state data, not just visual diffs. During imported combine previews and imported-project restore checks, Lumi scans block-entity and entity payloads for command blocks, structure blocks, jigsaw blocks, spawners, command block minecarts, and unknown entity or block-entity ids. Unsafe payloads are not stripped; applying an imported combine requires an explicit trusted-package confirmation, and restoring an unsafe imported review project is blocked unless a trusted restore API path is used.

Archive export does not follow symbolic links. Files are only copied when their resolved source remains inside the project root and the archive path is one of the supported project-relative locations.

Runtime Lumi region selections are intentionally excluded from storage. They are client memory only and must be recreated after the client or world closes.

## Cleanup policy

Current cleanup is conservative and UI-driven:

- dry-run first
- delete only unreferenced snapshot payloads, orphaned preview PNGs, disposable cache files outside `baseline-chunks`, and operation drafts that have already been recovered or safely classified by the domain cleanup flow
- never delete baseline chunks or files still referenced by version manifests
- resolve deletion candidates back through the project root and skip symlink directories while pruning empty folders
- tombstoned history remains soft-deleted only; physical cleanup of tombstoned version, patch, snapshot, and preview files is not part of the current cleanup policy
