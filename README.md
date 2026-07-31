# Lumi

<p align="center">
  <img alt="Lumi banner" src="lumi-banner.png" />
</p>

<p align="center">
  <strong>Save the build. Try the idea. Undo the mistake.</strong>
</p>

<p align="center">
  <img alt="Minecraft 1.21.11" src="https://img.shields.io/badge/Minecraft-1.21.11-5E7C16?style=for-the-badge" />
  <img alt="Fabric" src="https://img.shields.io/badge/Loader-Fabric-DBD0B4?style=for-the-badge" />
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-1F6FEB?style=for-the-badge" />
  <img alt="GPL 3.0" src="https://img.shields.io/badge/License-GPL%203.0-2EA043?style=for-the-badge" />
</p>

Lumi is durable world history for Minecraft builders.

Save a good moment, explore another idea on a branch, compare the result, and
return safely without copying the whole world folder or learning Git.

> [!WARNING]
> Lumi 0.2.0 is a release candidate. Keep normal backups of worlds you care about.

## What Lumi Does

- Saves named versions of visible blocks, block entities, durable non-player
  entities, and player respawn positions.
- Creates branches for experiments without duplicating stored world data.
- Compares any two saved versions and highlights added, removed, and changed
  blocks in the world.
- Restores a whole version, a wooden-sword selection, or everything outside the
  selection.
- Keeps workspaces and colored work zones as lightweight views over one shared
  dimension history.
- Provides session Undo/Redo and Quick Restore for unsaved builder changes.
- Supports safe Amend, branch merge, soft-deleted history, tags, renaming,
  search, and isometric save previews.
- Imports and exports portable Lumi packages after inspection and confirmation.
- Recovers interrupted operations without sacrificing previously valid history.
- Integrates with normal Minecraft edits and supported WorldEdit and Axiom
  builder workflows.

Lumi uses one shared, server-authoritative world model in integrated and
dedicated servers. Multiple builders can work in the same dimension; its active
workspace, branch, and mutating history operation are global. Player-specific
live branches are not virtualized.

## Quick Start

1. Install Lumi and Fabric API for Minecraft `1.21.11`.
2. Enter a world and follow the first-run builder tour.
3. Make a few changes and press `Alt+S` to save them.
4. Press `U` to open Lumi and browse the new version.
5. Create a branch before trying a risky idea, then compare or restore when you
   are ready.

Every initialized dimension starts with one protected **Initial** version, so
the original state remains available from the beginning of its Lumi history.

## Default Controls

| Key | Action |
| --- | --- |
| `U` | Open Lumi |
| `Alt+S` | Save the active workspace or zone |
| `Alt+Z` / `Alt+Y` | Undo / Redo the latest session action |
| `Alt+R` | Quick Restore unsaved changes to the active version |
| `H` | Show or hide the compare highlight |
| `Alt+I` | Open the hotkey guide |
| `Alt+1` … `Alt+0` | Switch to an assigned branch |
| Hold `Alt` | Preview pending work and enable Lumi action shortcuts |
| Wooden sword | Select restore bounds and edit active-zone cells |

All key bindings are remappable in Minecraft controls.

## Install

Download a release-candidate build from
[GitHub Releases](https://github.com/KristopherZlo/lumi/releases) and place it
in the `mods` folder together with Fabric API.

| Requirement | Version |
| --- | --- |
| Minecraft | `1.21.11` |
| Fabric Loader | `0.19.2` or newer compatible release |
| Fabric API | `0.141.3+1.21.11` or newer compatible release |
| Java | `21` |

For a dedicated server, install Lumi on the server and on clients that use its
screens and overlays. Server actions require operator permission; Survival-mode
access must also be enabled in Lumi settings.

WorldEdit and Axiom are optional. Lumi does not require either mod for ordinary
play.

## Reliability

Lumi stores only the state needed for exact recovery. Save captures the visible
world boundary; Restore prepares heavy work away from the server tick, applies
bounded batches, persists them through Minecraft storage, and reads the result
back before reporting success. Restore and live Undo/Redo drain lighting for all
affected loaded chunks as their final world-application stage.
Verified Restore and direct Axiom edits also rebase session block-entity
baselines before later Undo/Redo can use them. Restore rebuilds those runtime
baselines from loaded chunks without reopening its lazy history payloads on the
server tick.
Restore deterministically repairs legacy entity UUIDs duplicated across saved
chunks, while new frozen Save captures reject that impossible state.

Published history uses immutable content-addressed objects, sparse Merkle trees,
hash-verified LZ4 packs, atomic refs, and operation journals. A failed or
interrupted operation keeps the last valid history intact and offers a safe
recovery path.

Lumi data is separate from vanilla chunk data:

```text
<world>/lumi/history/<dimension>/
```

A cleanly completed Lumi operation leaves a normal playable Minecraft world.
If the server stops during Restore, keep Lumi installed until journal recovery
finishes. Lumi V2 starts a new history and does not import legacy patch-v9 or
snapshot-v8 projects.

When there is no useful work, Lumi does no history processing. Long operations
remain incremental and observable, while their working memory is bounded by the
current batch instead of the entire world. Restore reuses one immutable object
pack channel at a time instead of reopening it for every section, and decodes
repeated Merkle chunk nodes once per compared region in physical pack order.
Outdated pending-work statistics are cancelled without reporting a history error,
and never delay a confirmed Save, Restore, or undo action.
History search waits four client ticks after the last keystroke before requesting
a page, while initial loads, paging, branch changes, and invalidations stay immediate.
Zone overlays likewise wait for a stable player cell and history revision before
requesting a new shell.
The opt-in developer mode in Lumi settings prints observed operation timings,
exact Restore apply phases and verification status in chat; every terminal
report, including failures, has a copy action.

## For Developers

Use JDK 21.

```powershell
.\gradlew.bat build --no-daemon
.\gradlew.bat test --no-daemon
.\gradlew.bat runClient --no-daemon
```

`build` compiles the mod and runs its unit and server GameTest suites.
The bundled redstone GameTests place both Rube Goldberg machines on the ground,
press their stone buttons as a nearby player, and verify exact block,
block-entity, entity, and player-respawn results after Quick Restore, Restore,
and Undo at
2 ticks and every 5 ticks through 40 or 245 ticks.
Run them directly with `.\gradlew.bat runGameTest --no-daemon`.
Pass `-Dfabric-api.gametest.filter=<test-id>` before `runGameTest` to run one
server GameTest without starting the full suite.
Client GameTests run all current suites by default. Select `SMOKE`, `UI`,
`RECOVERY`, or `BENCHMARK` with
`-Dlumi.gametest.suite=<name> runClientGameTest --no-daemon`.
Restore benchmark `totalMs` starts immediately before the final UI
confirmation and ends at the durable terminal event. `queueMs` and `serverMs`
split that same interval; performance gates report every failed budget together.
Apply metrics cover loaded and stored changes and separate background batch
preparation from lighting drain time.
The tick gate reads Minecraft's completed full-tick timings. Lumi gives
incremental operations 30 ms of the 50 ms limit so vanilla work keeps headroom.
The fresh-JVM cold benchmark rejects latency, tick, heap, application, workload,
or exactness regressions instead of accepting a small or already-warm sample.

The code is split by responsibility:

| Layer | Responsibility |
| --- | --- |
| `domain/model` | Value objects, persisted records, summaries, runtime state |
| `domain/service` | Save, Restore, Compare, branch, workspace, and zone rules |
| `minecraft/*` | Capture hooks, Minecraft adapters, tick-time application |
| `storage/*` | Object storage, serialization, refs, journals, and packages |
| `network` | Revision-checked client/server commands and immutable payloads |
| `client/ui/*` | Rendering, thin controllers, and immutable view state |

## License

GPL-3.0-only. See [LICENSE](LICENSE).
