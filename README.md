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
- Dirty work receives a ref-neutral automatic version every five minutes;
  the latest 64 per branch remain available in normal history.
- Alt+Z/Y is session-only and separate from durable commits.
- Alt+S opens the Save form; Alt+R starts Quick Rollback through the same server path.
- The same form can replace the latest version with a crash-safe Amend.
- Another branch can be merged through the same verified apply pipeline;
  overlapping conflicts explicitly use the source branch.
- Alt+L opens the current workspace history from the immutable server snapshot.
  History exposes separate Save/Amend actions, compact version actions and a
  branch browser for Create, Switch and Merge. Restoring an older version keeps
  its still-restorable forward versions visible, matching the legacy menu.
- Successful integrated Save and Amend operations capture a bounded world
  thumbnail for their new history entry; missing previews fall back safely.
- First-run onboarding opens once per client installation and remains replayable
  from More. Contextual tips can be dismissed independently and restored with
  More > Show tips again without replaying onboarding; Alt+I opens the live
  hotkey guide.
- More > Settings persists whether work-zone saves join active-workspace
  history and whether full Restore includes durable entities by default.
  Diagnostic telemetry remains a separate client-local allowlisted control.
- More > Check updates performs a manual bounded lookup against Lumi's fixed
  GitHub-hosted release manifest.
- A one-time native Minecraft world backup can be requested before first V2
  history creation with `-Dlumi.preModBackup.maxMiB=<limit>`; it is off by default.

The implementation grows only through tested vertical slices. See
[`modules.md`](modules.md) for code ownership and `docs/architecture.md` for
the fixed boundaries.

## Build

Use JDK 21:

```powershell
.\gradlew.bat build --no-daemon
```
