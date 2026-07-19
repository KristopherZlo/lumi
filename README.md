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
- Full Restore is a minimal confirmation. Partial Restore is a separate save
  action that consumes only the current wooden-sword selection, shows exact
  changed block/section counts, and requires a clean-state preview before Apply.
- Singleplayer and multiplayer use the same server-authoritative path.
- Idle play performs no history work when nothing is dirty.
- Automatic versions are opt-in per workspace. When enabled, dirty work
  receives a ref-neutral version every five minutes and the latest 64 per
  branch appear in normal history; they are neither created nor shown by
  default.
- Alt+Z/Y is session-only and separate from durable commits.
- Alt+S opens the Save form; R rolls back only pending builder-root work
  inside the active workspace and reports when the current saved version has
  nothing to restore.
- The same form can replace the latest version with a crash-safe Amend.
- Another branch can be merged through the same verified apply pipeline;
  overlapping conflicts explicitly use the source branch.
- Alt+L opens paged Cards/Graph history with branch and full-window server
  tag/text filtering. Its workspace header reports exact added, removed and
  changed pending block totals.
  Cards expose preview, Open, Restore and Create branch; the branch browser
  supports Create, Switch, Merge, protected Delete and persistent explicit
  Action+1..0 assignments. Restoring an older version keeps its still-restorable
  forward versions visible, matching the legacy menu.
- Successful integrated Save and Amend operations capture a bounded world
  transparent isometric render for their new history entry; no player
  framebuffer or open menu is captured.
- Work zones support empty color-assigned creation, exclusive Enter/Leave,
  sword cell editing, merged Focused/All/Hidden world shells, overlap counts,
  metadata-only Delete, and paged Cards/Graph commit management with the same
  full-window search and exact zone-scoped pending totals.
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
the fixed boundaries. The evidence-backed legacy workflow audit and ordered V2
parity work are tracked in
[`docs/legacy-user-parity-plan.md`](docs/legacy-user-parity-plan.md).

## Build

Use JDK 21:

```powershell
.\gradlew.bat build --no-daemon
```
