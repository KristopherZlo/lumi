# Test Client Profile

This repository ships a dedicated Fabric development profile for a local singleplayer test client.

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

If you want to force a specific JDK:

```powershell
.\scripts\run-test-client.ps1 -JavaHome "C:\Program Files\Java\jdk-21"
```

If you only want to verify the Gradle profile without starting Minecraft:

```powershell
.\scripts\run-test-client.ps1 -GradleTasks tasks --all
```

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
