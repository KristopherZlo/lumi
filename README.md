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
- Every initialized dimension exposes one protected Initial save as the first
  history entry, so builders can always restore the world starting point.
- Restore verifies the applied result before publishing a ref.
- Restore is one confirmation flow. With both an active wooden-sword selection
  and the sword held, it offers whole-save, selected-area, and outside-selection
  modes; both partial modes require an exact server preview before Apply.
- Singleplayer and multiplayer use the same server-authoritative path.
- Idle play performs no history work when nothing is dirty.
- Automatic versions are opt-in per workspace. When enabled, dirty work
  receives a ref-neutral version every five minutes and the latest 64 per
  branch appear in normal history; they are neither created nor shown by
  default.
- Alt+Z/Y is session-only and separate from durable commits.
- Alt+S opens the Save form, using the same focused modal with a Save zone
  action while a zone is active; R rolls back only pending builder-root work
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
  forward versions visible.
- Successful integrated Save and Amend operations capture a bounded world
  transparent isometric render for their new history entry; no player
  framebuffer or open menu is captured. The successful operation carries the
  exact captured-section bounds, so an asynchronous Save-form refresh cannot
  drop or misframe the preview.
- The Save modal refreshes its state automatically and has no manual preview
  refresh control.
- Work zones support empty color-assigned creation, exclusive Enter/Leave,
  sword cell editing, merged Focused/All/Hidden world shells, overlap counts,
  metadata-only Delete, and paged Cards/Graph commit management using the same
  Build, Latest save, History and full-window search layout with exact
  zone-scoped pending totals.
- First-run onboarding opens once per client installation and remains replayable
  from More. One nine-step event-driven tour asks for five real world edits,
  previews those edits, reacts to press/release events from live remappable
  Save/Open shortcuts, opens the real Save form, spotlights Dashboard
  Save/See changes/Restore controls and finishes in the live hotkey guide. Every
  step has Next, Back and Skip; Escape closes without completing and reopening
  resumes the active step. Contextual tips remain independently dismissible;
  Alt+I opens that same guide.
- Settings persists workspace history, Restore, preview, HUD and automatic
  version defaults through descriptive rows with compact On/Off controls;
  diagnostic telemetry remains client-local. More groups its secondary actions
  into vertical History, Guides and Maintenance sections and does not duplicate
  Settings, support or credits.
- Check updates immediately performs a bounded lookup against Lumi's fixed
  GitHub-hosted release manifest and shows progress/result in a compact modal.
- Every project page owns the same bordered sidebar/header shell; switching tabs
  replaces the content pane without retaining Dashboard as an input or rendering
  proxy. Fullscreen changes use Minecraft's live logical viewport instead of
  forcing the menu back to a framebuffer-derived compact profile.
- The project sidebar keeps More with the navigation and a bottom Support Lumi
  block with full-width icon-and-text actions for Buy me a coffee, PayPal and
  GitHub bug reports; the container keeps bottom padding before the author
  credit and mod version below it.
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
