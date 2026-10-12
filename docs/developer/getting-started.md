# Getting started

[Documentation home](README.md) / Getting started

## Requirements

- A JDK 21 installation.
- Git.
- Internet access for the first Gradle dependency download.
- A graphical session for the development client and client GameTests.

Use the Gradle wrapper included in the repository.
You do not need a separate Gradle installation.
The wrapper selects Gradle 9.4.0 from [gradle-wrapper.properties](../../gradle/wrapper/gradle-wrapper.properties).

[gradle.properties](../../gradle.properties) selects Minecraft 1.21.11, Fabric Loader 0.19.2, and Fabric API 0.141.3+1.21.11.
[build.gradle](../../build.gradle) selects the Java 21 toolchain and official Mojang mappings.
Mappings give readable names to Minecraft classes and methods.

WorldEdit and Axiom are optional player dependencies.
The development build downloads them for compilation and integration tests.
The build also extracts Axiom's nested API libraries.
Do not copy these libraries into the source tree.

## Open the project

1. Clone the repository.
2. Open its root directory in your IDE.
3. Import the project as a Gradle project.
4. Select JDK 21 as the Gradle JVM.
5. Open a terminal in the repository root.

If the checkout already exists, use it directly.
Check the active branch before you edit files.

```powershell
git status --short
git branch --show-current
java -version
.\gradlew.bat --version
```

On macOS or Linux, use `./gradlew` instead of `.\gradlew.bat` in every command.
If the wrapper lacks execute permission, run `chmod +x gradlew` once.

## Compile without starting Minecraft

```powershell
.\gradlew.bat compileJava compileTestJava compileGametestJava --no-daemon
```

This command compiles production code, unit tests, and Minecraft GameTests.
It does not execute tests or open a Minecraft window.

## Run the unit tests

```powershell
.\gradlew.bat test --no-daemon
```

Read the HTML report at `build/reports/tests/test/index.html`.
This task excludes tests with the `performance` tag.
See [Testing](testing.md) for the separate performance task and Minecraft suites.

## Start the development client

```powershell
.\gradlew.bat runClient --no-daemon
```

The development client uses `run/` for game files.
Create a disposable Creative world for experiments.
Use the [player controls](../../README.md#default-controls) to Save, Compare, and Restore a small edit.
Read `run/logs/latest.log` if an action fails.

The first run downloads Minecraft and mod dependencies.
Later runs reuse the Gradle cache.

## Build the distributable mod

```powershell
.\gradlew.bat build --no-daemon
```

The build includes unit tests and server GameTests.
It does not include the client GameTest suites or the real process-crash matrix.
The [Testing guide](testing.md) explains these separate gates.

Artifacts appear in `build/libs/`.
Use the remapped `lumi-<version>.jar` for a normal Fabric installation.
The sources JAR contains source code.

## Your first code-reading session

1. Read the [Project map](project-map.md).
2. Open `SaveScreenController.submit` from the [Save walkthrough](workflows.md#save).
3. Follow that walkthrough until `SaveService.publishSave`.
4. Read one matching test before you change behavior.

For setup failures, use [Troubleshooting](troubleshooting.md#build-and-setup).
