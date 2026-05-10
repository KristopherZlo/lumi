# Test Client Profile

This repository ships a dedicated Fabric development profile for a local singleplayer test client.

The automated `runClientGameTest` profile runs dedicated client screen and overlay smoke GameTests and invokes the shorter `/lumi testing smoke` storage/runtime path by default. Set `LUMI_SINGLEPLAYER_TEST_MODE=full`, `structure-fixtures`, `external-tools`, or `crash-safety` before `runClientGameTest` to run a focused or destructive runtime mode. Structure fixture comparisons are exact except for the transient `observer.powered` phase on the generated closed observer pair; the structural piston/observer rollback assertions still remain strict. Screen smoke renders safe non-storage owo screens, clicks validation/back/cancel actions, and exercises storage-backed Lumi route sections through live client component fixtures so missing buttons, inactive-state regressions, action callback failures, and render crashes fail the client GameTest without opening screens that synchronously wait on integrated-server storage. Overlay smoke covers small compare/recent overlays through the live renderer, large cached tiled-volume overlay meshes in client context, and pending-draft overlays for cumulative unsaved changes. The pinned recent-preview contract for live edits while the action button preview is held is covered by the focused preview-session regression tests.

## Launch

Use the default nickname:

```powershell
.\scripts\run-test-client.ps1
```

Use a custom nickname:

```powershell
.\scripts\run-test-client.ps1 -Username YourNickHere
```

The script automatically selects a compatible local JDK 21+ and prefers Java 21 when it is installed. It overrides an older shell `JAVA_HOME` for that launch only.

The test-client Gradle profile always starts with Lumi diagnostics enabled:

- `-Dlumi.debug=true`
- `-Dlumi.startupProfile=true`
- `-Dlumi.loadLog=true`
- `-Dlumi.lightLog=true`
- `-Dlumi.blockApplyLog=true`

The load log is written under `run/test-client/logs/lumi-load.log` by default. Restore, undo/redo, and quick rollback runs can emit an automatic `light-refresh` follow-up operation after the block/entity action; runtime checks should treat it as part of the operation window and verify it completes without `runLightUpdates()` thread exceptions.
Test-client launches also write `run/test-client/logs/lumi-light.log` and `run/test-client/logs/lumi-block-apply.log`. Use the light log for rejoin-only shadow investigations, including dirty-chunk preload, marked chunk counts, and any missing dirty chunks. Use the block-apply log for restore/rollback bottleneck breakdowns by preload, chunk, section path, block entities, and entity operations.

If you want to force a specific JDK:

```powershell
.\scripts\run-test-client.ps1 -JavaHome "C:\Program Files\Java\jdk-21"
```

Existing `JAVA_TOOL_OPTIONS` are preserved for additional JVM diagnostics.

If you only want to verify the Gradle profile without starting Minecraft:

```powershell
.\scripts\run-test-client.ps1 -GradleTasks @("tasks", "--all")
```

Run the alpha release gate wrapper:

```powershell
.\scripts\run-alpha-release-check.ps1
```

The wrapper chains the coverage ratchet, GameTests, focused runtime modes, runtime load comparison, and crash harness. Use `-SkipRuntimeLoad` or `-SkipCrashHarness` only for local iteration, not for release sign-off.

If you want to launch the broader performance-mod profile:

```powershell
.\scripts\run-test-client.ps1 -FullStack
```

You can also call Gradle directly:

```powershell
.\gradlew.bat installTestClientMods runTestClient -Plumi.testUsername=YourNickHere
```

## Installed client mods

The `installTestClientMods` task syncs the supported Fabric 1.21.11 jars into `run/test-client/mods`. The default profile is intentionally small so the dev runtime can validate Lumi with external builder tools without remapping a large unrelated client stack.

Default profile:

- Axiom
- WorldEdit

Axiom is pinned to the exact `Axiom-5.4.1-for-MC1.21.11.jar` Modrinth file because the generic Modrinth Maven coordinate for `5.4.1` can resolve to a jar with older Minecraft metadata.

The default profile also installs Fabric API as a test-client runtime dependency. These jars are not bundled into the final `lumi` release jar.

Full-stack profile, enabled with `-FullStack` or `-Plumi.testClientFullStack=true`:

- Sodium
- Entity Culling
- FerriteCore
- Mod Menu
- Lithium
- ImmediatelyFast
- ETF
- EMF
- Sodium Extra
- Zoomify
- Krypton
- Voxy
- Cubes Without Borders
- Remove Reloading Screen
- FastQuit
- Particle Core

Compatibility replacements used because the exact requested mod has no Fabric 1.21.11 release available on Modrinth:

- `ModernFix` -> `ModernFix-mVUS`

## Not installed

The following requested mods are not installed in this profile because a Fabric 1.21.11-compatible release was not found during setup on April 20, 2026:

- Indium
- Memory Leak Fix
- Starlight
- LazyDFU
- Chloride
- Fastload
- Cull Less Leaves

`Chloride` is also not available as a Fabric mod for this Minecraft version. `LazyDFU Reloaded` was evaluated as a fallback candidate but was not kept in the profile because the available jar was not a valid Fabric mod jar for 1.21.11.

## Full-stack runtime dependencies

The full-stack profile also installs the runtime libraries needed by the selected client mods:

- Fabric Language Kotlin
- YetAnotherConfigLib (YACL)
- Forge Config API Port
- Fzzy Config
- Cloth Config API
- Text Placeholder API

These are test-client runtime dependencies only. They are not bundled into the final `lumi` release jar.
