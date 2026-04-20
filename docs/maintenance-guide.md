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
- prefer regression tests for any bug fixed from a real log or reproduction

## Logging policy

Background work, storage transitions, and user-triggered failures must be observable in logs.

Log:

- operation start and completion
- progress stage transitions for long-running operations
- rejected operations
- storage compaction and recovery events
- caught exceptions that would otherwise surface only as generic UI errors

Avoid noisy per-block logs. Favor stage, count, and context-rich summaries.

## Change review checklist

Before considering work complete, verify:

- responsibilities are still clean
- OOP and SOLID were preserved or improved
- documentation was updated
- tests or compile checks were run
- commit boundaries are still reasonable
- new async work does not leak heavy decoding onto the server tick
