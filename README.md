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

Lumi keeps durable world history for Minecraft builders.

Save a good moment. Test another idea on a branch. Compare the result, then
return without copying the world or learning Git.

> [!WARNING]
> Lumi 0.2.0 is a release candidate. Keep normal backups of worlds you care about.

## What Lumi Does

- Saves named versions of visible blocks, block entities, durable non-player
  entities, and player respawn positions.
- Creates branches for experiments without copying stored world data.
- Compares two saved versions and highlights added, removed, and changed blocks.
- Restores a complete version, a wooden-sword selection, or everything outside
  the selection.
- Shows workspaces and colored work zones as views of one shared dimension
  history.
- Gives builders session Undo/Redo and Quick Restore for unsaved changes.
- Supports safe Amend, branch merge, tags, renaming, search, and isometric save
  previews.
- Keeps soft-deleted history available for recovery.
- Imports and exports portable Lumi packages after you inspect and confirm them.
- Recovers interrupted operations and keeps earlier valid history safe.
- Integrates with normal Minecraft edits and supported WorldEdit and Axiom
  workflows.

Lumi uses one shared, server-authoritative world model in integrated and
dedicated servers. Multiple builders can work in the same dimension; its active
workspace, branch, and mutating history operation are global. Player-specific
live branches are not virtualized.

## Quick Start

1. Install Lumi and Fabric API for Minecraft `1.21.11`.
2. Open a world and follow the first-run builder tour.
3. Make a few changes and press `Alt+S` to save them.
4. Press `U` to open Lumi and browse the new version.
5. Create a branch before you test a risky idea.
6. Compare the result or restore an earlier version.

Each initialized dimension starts with one protected **Initial** version. This
version preserves the original state from the start of Lumi history.

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
| Hold `Alt` | Preview pending work and use Lumi action shortcuts |
| Wooden sword | Select restore bounds and edit active-zone cells |

While holding the wooden sword, Undo/Redo affects only your selection. An empty
selection history shows a message. Put the sword away to undo or redo world edits.

You can change all key bindings in Minecraft controls.

Zone Save, Amend, and See Changes use the zone's current pending state. Changes
elsewhere in the workspace do not enable these controls. They show a reason
while statistics load or the zone is clean; entity-only edits remain savable.
Zone See Changes displays only points inside that zone.

## Install

1. Download a release-candidate build from
   [GitHub Releases](https://github.com/KristopherZlo/lumi/releases).
2. Put Lumi and Fabric API in the `mods` folder.

| Requirement | Version |
| --- | --- |
| Minecraft | `1.21.11` |
| Fabric Loader | `0.19.2` or newer compatible release |
| Fabric API | `0.141.3+1.21.11` or newer compatible release |
| Java | `21` |

For a dedicated server, install Lumi on the server. Also install it on clients
that use Lumi screens and overlays. Server actions require operator permission.
Lumi settings must also allow access from Survival mode.

WorldEdit and Axiom are optional. Lumi does not require these mods for ordinary
play.

## Reliability

Lumi stores only the state that exact recovery needs. Save captures the visible
world boundary. Restore prepares large tasks outside the server tick.

Restore applies bounded batches. It persists these batches through Minecraft
storage and reads the result before it reports success. Restore and live
Undo/Redo drain lighting in all affected loaded chunks before they finish.

After a verified Restore or direct Axiom edit, Lumi rebases session block-entity
baselines. Later Undo/Redo operations use these new baselines.

Restore repairs duplicate legacy entity UUIDs across saved chunks in a
deterministic way. A new frozen Save rejects this invalid state.

Lumi stores published history in immutable, content-addressed objects and sparse
Merkle trees. It uses hash-verified LZ4 packs, atomic refs, and operation
journals.

A failed or interrupted operation keeps the last valid history intact. Lumi
then offers a safe recovery path.

Lumi data is separate from vanilla chunk data:

```text
<world>/lumi/history/<dimension>/
```

A cleanly completed Lumi operation leaves a normal playable Minecraft world.
If the server stops during Restore, keep Lumi installed until journal recovery
finishes. Lumi V2 starts a new history and does not import legacy patch-v9 or
snapshot-v8 projects.

When Lumi has no useful work, it does not process history. Long operations
remain incremental and report progress. Working memory stays bounded by the
current batch instead of the full world.

During Restore, Lumi reuses one immutable object-pack channel at a time. It does
not reopen a channel for every section. It also decodes repeated Merkle chunk
nodes once per compared region in physical pack order.

Outdated pending-work statistics do not report a history error or delay a
confirmed Save, Restore, or Undo action. History search waits four client ticks
after the last keystroke before it requests a page. Zone overlays wait for a
stable player cell and history revision before they request a new shell.

A newer full Restore, branch switch, or workspace switch replaces only the same
player's older queued world selection. Active work and other builders are not
cancelled.

The optional developer mode in Lumi settings prints operation times, exact
Restore phases, and verification status in chat. Each terminal report, including
a failure, has a copy action.

## For Developers

Use JDK 21.

```powershell
.\gradlew.bat build --no-daemon
.\gradlew.bat test --no-daemon
.\gradlew.bat runClient --no-daemon
```

`build` compiles the mod. It also runs the unit tests and server GameTest suites.

The bundled redstone GameTests place two Rube Goldberg machines on the ground.
A nearby player presses each stone button. The tests verify exact block,
block-entity, and entity state after Quick Restore, Restore, and Undo.

Checks run at 2 ticks, then every 5 ticks through 40 or 245 ticks. Run the tests
with `.\gradlew.bat runGameTest --no-daemon`.

To run one server GameTest, add
`-Dfabric-api.gametest.filter=<test-id>` before `runGameTest`. This skips the
full suite.

Client GameTests run all current suites by default. Select `SMOKE`, `UI`, `INTERACTION`,
`RECOVERY`, `EXACT`, or `BENCHMARK` with
`-Dlumi.gametest.suite=<name> runClientGameTest --no-daemon`.

Zone and selection fixes, regression coverage, and paired shell measurements
are recorded in [the interaction verification report](docs/zone-interaction-fixes.md).
Single-click container, block and lever timing is described in
[the latency test guide](docs/interaction-latency-tests.md).

Restore benchmark `totalMs` starts before the final UI confirmation and ends at
the durable terminal event. `queueMs` and `serverMs` split that interval. Apply
metrics separate background preparation, loaded and stored apply, lighting, and
verification. The cold benchmark rejects latency, tick, heap, workload, and
exactness regressions.

Cold-path causes, previous attempts and the current bounded prewarm change are
tracked in [the cold Restore report](docs/cold-restore-path-preservation.md).

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
