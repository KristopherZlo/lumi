# Maintenance Guide

## Scope

This guide defines the engineering rules for maintaining Lumi. It is written for human contributors and automation agents working in the repository.

The short version:

- follow OOP
- follow SOLID
- keep documentation current
- commit every 100-300 changed lines of code or earlier for a coherent slice

## Required engineering standards

## OOP expectations

- Model the domain explicitly. Prefer domain objects and services over free-floating utility logic.
- Keep state and behavior close together when they belong to the same concept.
- Use composition before inheritance unless inheritance expresses a stable semantic hierarchy.
- Keep classes small enough that their primary responsibility is obvious from the class name.

## SOLID expectations

### Single Responsibility Principle

Each class should have one primary reason to change. Split classes when they mix domain policy, persistence, rendering, or Minecraft engine side effects.

### Open/Closed Principle

Extend workflows by adding focused collaborators or interfaces instead of repeatedly adding branching logic into large service methods.

### Liskov Substitution Principle

When introducing abstractions, implementations must preserve the contract and operational assumptions of the abstraction.

### Interface Segregation Principle

Keep consumer-facing APIs narrow. Do not force UI code to depend on storage-only details or repositories to expose workflow-only operations.

### Dependency Inversion Principle

High-level workflow code should depend on domain concepts and repository/service contracts, not on low-level storage details leaking upward.

## Layer boundaries

- `domain/model`: persisted records, summaries, value objects, and tightly bounded mutable runtime structures
- `domain/service`: product workflows and orchestration
- `minecraft/*`: engine adapters, tick integration, and world mutation plumbing
- `storage/*`: file layout and persistence only
- `client/ui/*`: view state, rendering, and controller glue

Do not move file I/O into UI controllers. Do not move Minecraft world mutation code into repositories. Do not place product rules inside mixins.

## Documentation policy

Documentation updates are mandatory.

You must update documentation when a change affects:

- architecture or responsibility boundaries
- public behavior or user workflow
- storage format or recovery semantics
- history visibility, tombstone, or soft-delete semantics
- operational guarantees, limits, or threading behavior
- testing strategy or contributor workflow

Required documentation touch points:

- `README.md` for user-facing entry points
- `docs/architecture.md` for architectural or workflow changes
- `docs/storage-format.md` for persistence changes
- `docs/development.md` or this guide for contributor workflow changes
- `AGENTS.md` if automation instructions or mandatory maintenance rules change

If the code changed but the documentation stayed valid, state that explicitly in the review or commit message.

## Commit policy

Commits are required throughout implementation.

Rules:

- make a commit every 100-300 changed lines of code
- commit earlier when a coherent vertical slice is complete
- split scaffolding from behavior when that improves reviewability
- keep docs and tests in the same commit as the code they describe
- avoid giant mixed commits that blur storage, UI, domain, and integration responsibilities

If a task is intentionally handled without intermediate commits, that must be an explicit exception.

## Testing policy

Every non-trivial change should include verification proportional to risk.

Expected practices:

- add or update unit tests for model and repository behavior
- run compile or test tasks after structural changes
- validate save/restore/recovery changes against both success and failure paths
- validate corrupt storage paths with bounded reads when changing binary repositories; negative or oversized lengths must fail before large allocation
- prefer regression tests for any bug fixed from a real log or reproduction
- when changing Gradle launch or packaging behavior, verify the relevant run/build task path directly and document any automated cleanup or cache assumptions
- when changing world apply performance, verify dense section-native paths, sparse fallback paths, and storage compatibility in the same review
- before alpha release, run `.\scripts\run-alpha-release-check.ps1`; it combines the coverage ratchet, GameTests, focused runtime modes, runtime-load comparison, and crash harness
- update `config/coverage-baseline.properties` only after reviewing the JaCoCo report and accepting the new coverage level

Crash harness failpoints are guarded by explicit test properties or environment variables. Never enable `LUMI_TEST_FAILPOINT_ENABLED` or `-Dlumi.test.failpoint.enabled=true` outside disposable test runs.

## Logging policy

Background work, storage transitions, and user-triggered failures must be observable in logs.

Log:

- operation start and completion
- progress stage transitions for long-running operations
- rejected operations
- storage compaction and recovery events
- storage corruption quarantine and WAL salvage events
- caught exceptions that would otherwise surface only as generic UI errors
- skipped auto checkpoints, especially when another Lumi operation is active

Avoid noisy per-block logs. Favor stage, count, and context-rich summaries.

For runtime cost work, prefer the separate `-Dlumi.loadLog=true` load log over broad debug tracing. It keeps load diagnostics in `logs/lumi-load.log` with top cumulative spans and completed apply metrics, while normal debug logs remain focused on behavior and failure diagnosis.

## History tombstones and recovery

Soft deletion is visibility metadata, not physical cleanup. `history-tombstones.json` hides selected version and branch ids from normal UI and lineage, while version manifests, patches, snapshots, previews, and baseline chunks stay in place. Maintenance tooling must not treat tombstoned payloads as orphaned solely because they are hidden.

Recovery drafts remain the active crash-safety surface. Opening a project with a non-empty persisted draft should route to the recovery screen, and large external edits should save any existing draft as an auto checkpoint before the edit starts. If another Lumi operation is already active, the checkpoint is skipped and logged instead of racing the operation model.

Interrupted save/amend operation drafts in `recovery/operation-draft.bin.lz4` must be promoted or merged before recovery screens, save/amend startup, bootstrap repair, or cleanup treat the project as clean, but only from idle recovery/bootstrap paths where no world operation still owns the draft. Active save/amend failure paths must leave the operation draft isolated. Cleanup should preserve unresolved operation drafts and report a warning instead of deleting a potentially recoverable player edit.

Restore completion recovery uses `recovery/pending-restore-completion.json` when world apply succeeded but metadata publication did not finish. Idle bootstrap/recovery must complete this record before exposing the project as clean, and maintenance cleanup must not delete it as disposable cache data.

## Release metadata

The update notice flow depends on public JSON manifests. For every public release, update the website manifest first and keep the repository fallback at `updates/lumi-fabric.json` in sync. The fallback manifest should include only versions that are safe to advertise, should keep `versionCode` increasing, and should list the exact supported Minecraft versions so players are not prompted to install a build for a different game version.

## Change review checklist

Before considering work complete, verify:

- responsibilities are still clean
- OOP and SOLID were preserved or improved
- documentation was updated
- tests or compile checks were run
- commit boundaries are still reasonable
- new async work does not leak heavy decoding onto the server tick
- dense world mutation still uses bounded native-cell/rewrite-section bursts and reports native/rewrite/direct/fallback metrics
