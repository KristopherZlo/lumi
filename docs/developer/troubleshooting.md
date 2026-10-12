# Troubleshooting

[Documentation home](README.md) / Troubleshooting

## Build and setup

| Symptom | Check |
| --- | --- |
| Gradle cannot find a Java 21 toolchain | Install JDK 21 and select it as the IDE's Gradle JVM. Check `JAVA_HOME` in the terminal. |
| Dependency download fails | Read the first repository or network error. Check connectivity before changing dependency versions. |
| Axiom API classes are missing | Run the wrapper build so `unpackAxiomRuntime` extracts the nested libraries. Avoid manually compiled classpaths. |
| A command works in the IDE but fails in PowerShell | Run it from the repository root and quote each `-Dname=value` argument. |
| Gradle does not run a test again | Use the appropriate task with `--rerun-tasks` when you need a fresh result. |
| The development client does not open | Read `run/logs/latest.log` and the terminal's first launch error. |

Inspect registered tasks without starting Minecraft:

```powershell
.\gradlew.bat tasks --all --no-daemon
```

Inspect the build's task graph without executing it:

```powershell
.\gradlew.bat build --dry-run --no-daemon
```

## Find evidence

| Run | Main evidence |
| --- | --- |
| Unit tests | `build/reports/tests/test/index.html`, `build/test-results/test/` |
| Development client | `run/logs/latest.log` |
| Server GameTests | `build/run/gameTest/logs/latest.log` and terminal summary |
| Client GameTests | `build/run/clientGameTest/logs/latest.log` and screenshots in the run directory |
| Behavior scenarios | `build/reports/lumi-behavior/` |
| Compare renderer | `build/run/clientGameTest/compare-render.csv` |
| Performance regression tests | `build/reports/lumi-regressions/` |
| Restore crash matrix | `build/reports/lumi-crash-matrix/` and the producer/verifier logs |

Preserve a failed run's reports before another test run recreates its directories.
Record the branch, commit ID, Java version, command, and exact failure.

## Follow one operation

Client logs include the outgoing request ID, action, dimension, and expected revision.
Server logs connect that request to an operation ticket, queue, phases, and terminal state.
Use the request ID to follow the same action across the connection.

For a slow Save, find preparation, durability, chunk readiness, capture, and publication timings.
For a slow Restore, distinguish queue time, background preparation, apply, lighting, persistence, and verification.
Record whether chunks used loaded, stored, or mixed paths.
Do not compare samples from different paths as if they measured the same work.

The optional developer mode in Lumi settings shows operation diagnostics in chat.
Keep the runtime log for the full phase and failure context.

## Compare preparation

The highlight preparation bar reports client geometry and visible GPU uploads.
It does not report server history progress or estimated time remaining.
The GUI HUD setting controls its display in the world and Lumi screens.

If a completed server Compare has no visible highlight, inspect local preparation and renderer readiness.
The first highlight waits for its visible batches.
Hidden, completed, and failed preparation have no active bar.
For an unexpected bar or partial first highlight, follow the [client ownership path](highlight-preparation.md#read-the-code).

## Shared-world rejections

A rejected queued request can be correct behavior.
Another builder may have changed the active ref, workspace, zone revision, or actor authority.
Inspect the activation checks before treating rejection as a world-apply bug.
Never silently rebind an old destructive request to newer shared state.

For a disconnected player, identify whether apply had started.
Queued work belongs to the client.
Journaled world mutation belongs to the server and must reach a durable outcome.

## Recovery prompt or retained freeze

1. Stop unrelated attempts to mutate the affected dimension.
2. Preserve the logs and a copy of the stopped world.
3. Identify the active journal and last completed persistence phase.
4. Use Lumi's supported recovery direction.
5. Verify the result and reopen the world after completion.

Keep immutable history files unchanged during diagnosis.
Do not remove the journal to suppress the prompt.
See [Storage and recovery](storage.md#interrupted-operations) and the [crash matrix](testing.md#real-process-crash-matrix).
