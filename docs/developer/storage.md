# Storage and recovery

[Documentation home](README.md) / Storage and recovery

## Locate a dimension repository

Lumi stores world history under this path:

```text
<world>/lumi/history/<encoded-dimension-id>/
```

[DimensionRepositoryLayout](../../src/main/java/io/github/lumi/storage/repository/DimensionRepositoryLayout.java)
encodes the UTF-8 dimension ID as URL-safe Base64 without padding.
Use its `resolve` and `dimensionId` methods instead of constructing paths from player input.

The encoded directory contains this logical layout:

| Path | Purpose | Owner |
| --- | --- | --- |
| `objects/`, `objects/packs/` | Immutable world payloads and tree nodes | [ObjectStore](../../src/main/java/io/github/lumi/storage/object/ObjectStore.java) |
| `commits/` | Immutable saved-version records | [CommitRepository](../../src/main/java/io/github/lumi/storage/repository/CommitRepository.java) |
| `refs/heads/` | Branch and hidden checkpoint references | [BranchRefRepository](../../src/main/java/io/github/lumi/storage/repository/BranchRefRepository.java) |
| `refs/active.bin` | Active branch name and pointer revision | [ActiveBranchRepository](../../src/main/java/io/github/lumi/storage/repository/ActiveBranchRepository.java) |
| `workspaces/` | Workspace metadata, active pointer, and zone metadata | [WorkspaceRepository](../../src/main/java/io/github/lumi/storage/repository/WorkspaceRepository.java) |
| `origins/` | Original payload IDs for sparse history | [OriginStore](../../src/main/java/io/github/lumi/storage/repository/OriginStore.java) |
| `working/index.bin` | Dirty keys and captured-generation boundaries | [WorkingIndexRepository](../../src/main/java/io/github/lumi/storage/repository/WorkingIndexRepository.java) |
| `operations/active.bin` | Current operation kind, target, and durable phase | [OperationJournalRepository](../../src/main/java/io/github/lumi/storage/repository/OperationJournalRepository.java) |
| `operations/active-generations.bin` | Separate file with captured generations for the operation | `OperationJournalRepository` |
| `names/`, `tags/`, `previews/` | Mutable display metadata and optional thumbnails | [VersionDisplayNameRepository](../../src/main/java/io/github/lumi/storage/repository/VersionDisplayNameRepository.java), [VersionTagRepository](../../src/main/java/io/github/lumi/storage/repository/VersionTagRepository.java), [VersionPreviewRepository](../../src/main/java/io/github/lumi/storage/repository/VersionPreviewRepository.java) |
| `tombstones/` | Soft-delete markers | [TombstoneRepository](../../src/main/java/io/github/lumi/storage/repository/TombstoneRepository.java) |

World-wide Survival permission settings live separately under `lumi/history/server/`.
[SurvivalOptInRepository](../../src/main/java/io/github/lumi/storage/repository/SurvivalOptInRepository.java) owns that file.

## Understand object identity

Lumi hashes canonical, uncompressed payload bytes with SHA-256.
The hash identifies the content regardless of its compressed physical location.
Loose objects and packed objects use the same ID space.
Readers validate lengths and the payload hash.

[WorldObjectRepository](../../src/main/java/io/github/lumi/storage/repository/WorldObjectRepository.java) exposes typed world objects.
Its codecs live in [storage/object](../../src/main/java/io/github/lumi/storage/object).

| Object | Contents | Codec |
| --- | --- | --- |
| Section | Block palette, 4096 block indexes, and canonical block-entity NBT | [SectionBlobCodec](../../src/main/java/io/github/lumi/storage/object/SectionBlobCodec.java) |
| Entity chunk | Durable non-player entities and canonical NBT | [EntityChunkBlobCodec](../../src/main/java/io/github/lumi/storage/object/EntityChunkBlobCodec.java) |
| Tree node | Coordinate keys and child object IDs | [MerkleNodeCodec](../../src/main/java/io/github/lumi/storage/object/MerkleNodeCodec.java) |
| Commit | Tree ID, parents, author, message, scope, kind, statistics, and player respawn points | [CommitCodec](../../src/main/java/io/github/lumi/storage/object/CommitCodec.java) |

NBT is Minecraft's structured binary data format.
Its canonical form gives equivalent captured states stable object IDs.
Treat canonicalization as part of the storage contract.

The sparse tree contains explicit saved leaves.
An absent leaf resolves through the key's origin state.
It does not automatically mean air or an empty entity chunk.

## Read formats in the right place

Codecs define binary field order, bounds, and compatibility readers.
Repositories define names, publication, and cleanup.
Do not duplicate serialization details in a domain service or UI controller.

Useful format identifiers include `LUO2` for loose objects and `LUP2`/`LUI2` for pack data and indexes.
World payloads use `LUS2`, `LUE2`, `LUC2`, `LUR2`, `LUD2`, and `LUM2`.
These prefixes identify different payload types.
The matching codec remains the exact field-level reference.

For a format change, inspect the codec, repository, existing readers, and matching tests together.
Keep old-format fixtures when the reader promises compatibility.
Lumi V2 has no importer for legacy patch-v9 or snapshot-v8 history.

## Publish safely

Immutable files never change after publication.
Small mutable records use [AtomicFileWriter](../../src/main/java/io/github/lumi/storage/repository/AtomicFileWriter.java).
It writes and forces a temporary file before atomic replacement.
It does not use a non-atomic move as a successful fallback.
Atomic replacement exposes either the previous file or the complete replacement.
Forcing the file requests persistence of its written bytes.

Publish an object pack and its validated index before a ref can expose the new commit.
Create the operation journal before publishing a Save's ref.
For Restore, complete exact durable world verification before publishing pointers.
Remove the journal only after publication and captured-boundary cleanup finish.

Display names, tags, and previews do not change commit identity or world payloads.
Renaming a version must not rewrite its commit.
A missing display name falls back to the immutable commit message.

## Interrupted operations

On startup, `FabricDimensionRuntime.open` reads the active journal and acquires a recovery freeze when needed.
[RecoveryService](../../src/main/java/io/github/lumi/domain/service/RecoveryService.java) reconstructs the appropriate recovery operation.
[LumiRecoveryScreen](../../src/main/java/io/github/lumi/client/ui/LumiRecoveryScreen.java) lets the player choose a supported recovery direction.

The journal distinguishes preparation, apply, durable world completion, publication, and cleanup.
A restart after `WORLD_PERSISTED` must not repeat destructive work just because pointer publication was interrupted.
Scope and ref validation still apply to recovery.

Do not manually delete `operations/` to clear a recovery prompt.
Preserve a copy of the stopped world and logs before diagnosing an interrupted operation.
Keep Lumi installed until recovery resolves it.

Read [RecoveryServiceTest](../../src/test/java/io/github/lumi/domain/service/RecoveryServiceTest.java) and
[OperationJournalRepositoryTest](../../src/test/java/io/github/lumi/storage/repository/OperationJournalRepositoryTest.java).
The [real process-crash matrix](testing.md#real-process-crash-matrix) checks persistence cutpoints with separate Minecraft processes.

## Retention and cleanup

Refs, origins, journals, and protected metadata determine which objects remain reachable.
Soft delete preserves recovery references and a tombstone before cleanup can collect data.
Session Undo checkpoints remain owned by their in-memory action until permanent eviction or clean shutdown.

[RetentionService](../../src/main/java/io/github/lumi/domain/service/RetentionService.java) manages operation checkpoint retention.
[SessionCheckpointRefService](../../src/main/java/io/github/lumi/domain/service/SessionCheckpointRefService.java) manages session-owned refs.
[GarbageCollector](../../src/main/java/io/github/lumi/storage/repository/GarbageCollector.java) handles unreachable history after the orphan grace period.
The orphan grace period delays deletion of recent unreachable payloads.
Use these owners instead of deleting payload files directly.
