# Lumi V2

Lumi is a Fabric mod for Minecraft 1.21.11 that gives builders fast, durable
world history without copying whole save folders.

V2 is a clean implementation. It does not read legacy patch v9 or snapshot v8
projects. The old world remains ordinary Minecraft data; new history lives in
`<world>/lumi/history-v2/<dimension>/`.

## Product contract

- Save reflects visible blocks, block entities, durable non-player entities,
  and saved player spawn positions.
- Published commits and refs survive crashes.
- Restore verifies the applied result before publishing a ref.
- Singleplayer and multiplayer use the same server-authoritative path.
- Idle play performs no history work when nothing is dirty.
- Alt+Z/Y is session-only and separate from durable commits.
- Alt+S opens the Save form; Alt+R starts Quick Rollback through the same server path.
- The same form can replace the latest version with a crash-safe Amend.
- Alt+L opens the current workspace history from the immutable server snapshot.

The implementation grows only through tested vertical slices. See
[`modules.md`](modules.md) for code ownership and `docs/architecture.md` for
the fixed boundaries.

## Build

Use JDK 21:

```powershell
.\gradlew.bat build --no-daemon
```
