package io.github.luma.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureGuardrailsTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final Path CLIENT_SOURCES = Path.of("src/client/java");

    @Test
    void domainAndStorageDoNotImportClientOrUiLayers() throws IOException {
        List<Path> offenders = javaFiles(
                MAIN_SOURCES.resolve("io/github/luma/domain"),
                MAIN_SOURCES.resolve("io/github/luma/storage")
        ).stream()
                .filter(path -> importsAny(path, "io.github.luma.client", "io.github.luma.ui"))
                .toList();

        assertTrue(offenders.isEmpty(), "Core layers must not import client/UI code: " + offenders);
    }

    @Test
    void mixinsDoNotReachIntoStorageRepositories() throws IOException {
        List<Path> offenders = javaFiles(MAIN_SOURCES.resolve("io/github/luma/mixin")).stream()
                .filter(path -> importsAny(path, "io.github.luma.storage"))
                .toList();

        assertTrue(offenders.isEmpty(), "Mixins must delegate instead of touching storage directly: " + offenders);
    }

    @Test
    void hotPathClassesDoNotGrowBeforeTheyAreSplit() throws IOException {
        Map<Path, Integer> limits = Map.of(
                MAIN_SOURCES.resolve("io/github/luma/minecraft/world/WorldOperationManager.java"), 1740,
                MAIN_SOURCES.resolve("io/github/luma/domain/service/RestoreService.java"), 1375,
                MAIN_SOURCES.resolve("io/github/luma/minecraft/capture/HistoryCaptureManager.java"), 1810,
                MAIN_SOURCES.resolve("io/github/luma/storage/repository/PatchDataRepository.java"), 178
        );

        List<String> offenders = limits.entrySet().stream()
                .filter(entry -> lineCount(entry.getKey()) > entry.getValue())
                .map(entry -> entry.getKey() + " has " + lineCount(entry.getKey()) + " lines, limit " + entry.getValue())
                .toList();

        assertTrue(offenders.isEmpty(), "Hot-path classes must shrink during stabilization, not grow: " + offenders);
    }

    @Test
    void coreCodeAvoidsNewHelperOrUtilsDumpClasses() throws IOException {
        List<Path> offenders = javaFiles(
                MAIN_SOURCES.resolve("io/github/luma/domain"),
                MAIN_SOURCES.resolve("io/github/luma/storage/repository"),
                MAIN_SOURCES.resolve("io/github/luma/minecraft/capture"),
                MAIN_SOURCES.resolve("io/github/luma/minecraft/world")
        ).stream()
                .filter(path -> {
                    String fileName = path.getFileName().toString();
                    return fileName.endsWith("Helper.java") || fileName.endsWith("Utils.java");
                })
                .toList();

        assertTrue(offenders.isEmpty(), "Use owner classes with domain names instead of Helper/Utils dumps: " + offenders);
    }

    @Test
    void tickHotPathsDoNotSampleJavaStacks() throws IOException {
        List<Path> offenders = javaFiles(
                MAIN_SOURCES.resolve("io/github/luma/mixin"),
                MAIN_SOURCES.resolve("io/github/luma/minecraft/capture"),
                MAIN_SOURCES.resolve("io/github/luma/minecraft/world")
        ).stream()
                .filter(path -> sourceContainsAny(path, ".getStackTrace(", "StackWalker", "new Throwable("))
                .toList();

        assertTrue(offenders.isEmpty(), "Tick hot paths must not allocate stack traces: " + offenders);
    }

    @Test
    void worldApplyTickPathDoesNotImportStorageDecoders() throws IOException {
        List<Path> offenders = javaFiles(MAIN_SOURCES.resolve("io/github/luma/minecraft/world")).stream()
                .filter(path -> importsAny(
                        path,
                        "io.github.luma.storage",
                        "com.google.gson",
                        "net.jpountz.lz4",
                        "net.minecraft.nbt.NbtIo"
                ))
                .toList();

        assertTrue(offenders.isEmpty(), "World apply tick path must receive prepared work, not decode storage payloads: " + offenders);
    }

    @Test
    void projectIntegrityServiceDoesNotParseStoragePayloadHeaders() throws IOException {
        assertSourceExcludes(
                MAIN_SOURCES.resolve("io/github/luma/domain/service/ProjectIntegrityService.java"),
                "ProjectIntegrityService must delegate raw storage layout and payload header parsing to storage repositories",
                "DataInputStream", "LZ4FrameInputStream", "PATCH_MAGIC", "SNAPSHOT_MAGIC", "Files."
        );
    }

    @Test
    void projectIntegrityRepositoryReusesStoragePayloadReadersForHeaderChecks() throws IOException {
        assertSourceIncludesAndExcludes(
                MAIN_SOURCES.resolve("io/github/luma/storage/repository/ProjectIntegrityRepository.java"),
                "ProjectIntegrityRepository should reuse storage readers instead of duplicating payload header parsers",
                List.of("PatchPayloadReader", "SnapshotReader"),
                List.of("DataInputStream", "LZ4FrameInputStream", "PATCH_MAGIC", "SNAPSHOT_MAGIC")
        );
    }

    @Test
    void clientCodeDoesNotImportStorageRepositoriesDirectly() throws IOException {
        List<Path> offenders = javaFiles(CLIENT_SOURCES.resolve("io/github/luma")).stream()
                .filter(path -> importsAny(path, "io.github.luma.storage.repository"))
                .filter(path -> !isAllowedClientStorageAdapter(path))
                .toList();

        assertTrue(offenders.isEmpty(), "Client code must reach storage through controllers/services: " + offenders);
    }

    @Test
    void projectScreenControllerDoesNotReachIntoPreviewStorageLayout() throws IOException {
        assertSourceExcludes(
                CLIENT_SOURCES.resolve("io/github/luma/ui/controller/ProjectScreenController.java"),
                "ProjectScreenController must use services for preview paths and request state",
                "previewFile(", "previewRequestFile(", "java.nio.file.Files", "Files.exists("
        );
    }

    @Test
    void domainModelDoesNotAddMinecraftImportsBeyondLegacyPayloadTypes() throws IOException {
        Set<String> allowed = Set.of(
                "src/main/java/io/github/luma/domain/model/BlockPoint.java",
                "src/main/java/io/github/luma/domain/model/Bounds3i.java",
                "src/main/java/io/github/luma/domain/model/ChunkPoint.java",
                "src/main/java/io/github/luma/domain/model/ChunkSnapshotPayload.java",
                "src/main/java/io/github/luma/domain/model/ChunkSectionSnapshotPayload.java",
                "src/main/java/io/github/luma/domain/model/EntityPayload.java",
                "src/main/java/io/github/luma/domain/model/PatchSectionFrame.java",
                "src/main/java/io/github/luma/domain/model/SnapshotChunkData.java",
                "src/main/java/io/github/luma/domain/model/SnapshotSectionData.java",
                "src/main/java/io/github/luma/domain/model/StatePayload.java",
                "src/main/java/io/github/luma/domain/model/TrackedChangeBuffer.java"
        );
        List<Path> offenders = javaFiles(MAIN_SOURCES.resolve("io/github/luma/domain/model")).stream()
                .filter(path -> importsAny(path, "net.minecraft"))
                .filter(path -> !allowed.contains(normalize(path)))
                .toList();

        assertTrue(offenders.isEmpty(), "Do not add Minecraft imports to domain model beyond documented legacy payload types: " + offenders);
    }

    @Test
    void restoreCompletionWorkflowHasDedicatedCoordinator() throws IOException {
        Path restoreService = MAIN_SOURCES.resolve("io/github/luma/domain/service/RestoreService.java");
        Path coordinator = MAIN_SOURCES.resolve("io/github/luma/domain/service/RestoreCompletionCoordinator.java");
        String restoreSource = Files.readString(restoreService);
        String coordinatorSource = Files.readString(coordinator);

        assertTrue(
                restoreSource.contains("RestoreCompletionCoordinator"),
                "RestoreService should delegate post-apply metadata publication to a coordinator"
        );
        assertTrue(
                !restoreSource.contains("private void completeRestore("),
                "RestoreService should not own full restore completion workflow"
        );
        assertTrue(
                coordinatorSource.contains("savePendingRestoreCompletion")
                        && coordinatorSource.contains("deletePendingRestoreCompletion"),
                "RestoreCompletionCoordinator must own pending completion journal lifecycle"
        );
    }

    @Test
    void partialRestoreDoesNotCreateOrPublishSavedVersions() throws IOException {
        Path restoreService = MAIN_SOURCES.resolve("io/github/luma/domain/service/RestoreService.java");
        Path coordinator = MAIN_SOURCES.resolve("io/github/luma/domain/service/RestoreCompletionCoordinator.java");
        String restoreSource = Files.readString(restoreService);
        String coordinatorSource = Files.readString(coordinator);

        assertTrue(
                !restoreSource.contains("stagePartialRestoreVersion("),
                "Partial restore should apply target state to the world and draft, not stage a saved version"
        );
        assertTrue(
                !coordinatorSource.contains("publishStagedVersion("),
                "Partial restore completion must not publish a staged version or move branch head"
        );
    }

    @Test
    void saveVersionDraftIsolationDrainsPendingEntitySpawnsFirst() throws IOException {
        Path versionService = MAIN_SOURCES.resolve("io/github/luma/domain/service/VersionService.java");
        String source = Files.readString(versionService);

        String drainCall = "EntityMutationTracker.drainPendingSpawns(level.getServer());";
        int drainIndex = source.indexOf(drainCall);
        int consumeDraftIndex = source.indexOf("consumeWorkingDraft(level.getServer(), project.id().toString())");

        assertTrue(
                drainIndex >= 0,
                "Save must drain pending entity spawn captures before isolating the durable draft"
        );
        assertTrue(
                drainIndex < consumeDraftIndex,
                "Save must not consume the working draft before pending entity spawns are attached"
        );
    }

    @Test
    void pendingEntitySpawnQueueKeepsInitialPayloadForImmediateSave() throws IOException {
        Path queue = MAIN_SOURCES.resolve("io/github/luma/minecraft/capture/EntitySpawnCaptureQueue.java");
        Path tracker = MAIN_SOURCES.resolve("io/github/luma/minecraft/capture/EntityMutationTracker.java");
        String source = Files.readString(queue);
        String trackerSource = Files.readString(tracker);

        assertTrue(
                source.contains("EntityPayload initialPayload"),
                "Pending spawn captures must keep the accepted entity payload for a same-tick save"
        );
        assertTrue(
                source.contains("Entity acceptedEntity"),
                "Pending spawn captures must keep the accepted entity reference so forced drains record current state"
        );
        assertTrue(
                source.contains("allowInitialPayloadFallback"),
                "Spawn drain must use the queued payload only for explicit same-tick fallback"
        );
        assertTrue(
                trackerSource.contains("MAX_SPAWN_CAPTURES_PER_TICK")
                        && trackerSource.contains("true, Integer.MAX_VALUE"),
                "Normal ticks should wait for stable entity lookup, while save may force same-tick spawn capture"
        );
    }

    @Test
    void restoreEntityStateWorkflowHasDedicatedResolver() throws IOException {
        Path restoreService = MAIN_SOURCES.resolve("io/github/luma/domain/service/RestoreService.java");
        Path resolver = MAIN_SOURCES.resolve("io/github/luma/domain/service/RestoreEntityStateResolver.java");
        String restoreSource = Files.readString(restoreService);
        String resolverSource = Files.readString(resolver);

        assertTrue(
                restoreSource.contains("RestoreEntityStateResolver"),
                "RestoreService should delegate entity target reconstruction to a resolver"
        );
        assertTrue(
                !restoreSource.contains("private Map<String, EntityPayload> targetEntityStates("),
                "RestoreService should not own entity target-state reconstruction"
        );
        assertTrue(
                resolverSource.contains("alignPendingEntityRollbackWithTarget")
                        && resolverSource.contains("authoritativeEntityReplacementBatches"),
                "RestoreEntityStateResolver must own rollback alignment and authoritative replacement batches"
        );
    }

    @Test
    void productionBootstrapDoesNotReferenceRuntimeTestRunnerDirectly() throws IOException {
        Path bootstrap = MAIN_SOURCES.resolve("io/github/luma/LumaMod.java");
        Path runtimeHarnessPackage = MAIN_SOURCES.resolve("io/github/luma/minecraft/testing");
        String source = Files.readString(bootstrap);
        List<Path> runtimeHarnessSources = javaFiles(runtimeHarnessPackage).stream()
                .filter(path -> !normalize(path).endsWith(
                        "src/main/java/io/github/luma/minecraft/testing/RuntimeTestingConfig.java"
                ))
                .toList();

        assertTrue(
                !source.contains("SingleplayerTestingService") && !source.contains("RuntimeTestingHooks"),
                "LumaMod must not carry runtime testing hooks into the gameplay bootstrap"
        );
        assertTrue(
                runtimeHarnessSources.isEmpty(),
                "Runtime regression harness belongs in GameTest/support source sets, not main gameplay sources; "
                        + "only the production testing flag gate may remain in main: "
                        + runtimeHarnessSources
        );
    }

    @Test
    void clientRuntimeLoadSamplerIsRegisteredOnlyWhenConfigured() throws IOException {
        String clientSource = Files.readString(CLIENT_SOURCES.resolve("io/github/luma/LumaClient.java"));
        String samplerSource = Files.readString(CLIENT_SOURCES.resolve(
                "io/github/luma/client/diagnostics/ClientRuntimeLoadSampler.java"
        ));

        assertTrue(
                clientSource.contains("ClientRuntimeLoadSampler.configuredEnabled()"),
                "LumaClient must check the client load-log flag before registering sampler callbacks"
        );
        assertTrue(
                samplerSource.contains("private static final class Holder"),
                "ClientRuntimeLoadSampler singleton must be lazy so disabled production runs do not allocate probes"
        );
    }

    @Test
    void gradleRuntimeHarnessRunsEnableRuntimeTestingFlag() throws IOException {
        String buildScript = Files.readString(Path.of("build.gradle"));

        assertTrue(
                buildScript.contains("vmArg '-Dlumi.testing.enabled=true'"),
                "Loom testClient run must enable runtime testing explicitly"
        );
        assertFalse(
                buildScript.contains("vmArg '-Dlumi.debug=true'")
                        || buildScript.contains("vmArg '-Dlumi.loadLog=true'")
                        || buildScript.contains("vmArg '-Dlumi.lightLog=true'")
                        || buildScript.contains("vmArg '-Dlumi.blockApplyLog=true'"),
                "Loom testClient run must keep broad diagnostics opt-in"
        );
        assertTrue(
                buildScript.contains("jvmArgs('-Dlumi.testing.enabled=true')"),
                "Client GameTest run must enable runtime testing explicitly"
        );
    }

    @Test
    void gametestRuntimeSuiteHasDedicatedServerTickHook() throws IOException {
        Path metadata = Path.of("src/gametest/resources/fabric.mod.json");
        Path hook = Path.of("src/gametest/java/io/github/luma/gametest/LumiGameTestRuntimeHooks.java");
        String metadataSource = Files.readString(metadata);

        assertTrue(
                metadataSource.contains("\"main\"")
                        && metadataSource.contains("io.github.luma.gametest.LumiGameTestRuntimeHooks"),
                "The GameTest mod must load a main entrypoint for server tick wiring"
        );

        assertTrue(Files.exists(hook), "The runtime suite tick hook must live in the GameTest source set");
        String hookSource = Files.readString(hook);
        assertTrue(
                hookSource.contains("ServerTickEvents.END_SERVER_TICK.register")
                        && hookSource.contains("SingleplayerTestingService.getInstance().tick(server)"),
                "The singleplayer runtime suite must advance on integrated-server ticks"
        );
    }

    @Test
    void worldBootstrapCreatesPlayerWorkspaceOnWorldEntry() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/domain/service/ProjectService.java"));

        assertTrue(
                source.contains("server.getPlayerList().getPlayers()")
                        && source.contains("this.ensureWorldProject(player.level(), \"Lumi\")"),
                "World bootstrap must initialize the current player's Lumi workspace after world entry"
        );
    }

    @Test
    void generatedRedstoneFixtureComparisonPoliciesCoverKnownPhaseProperties() throws IOException {
        String snapshotSource = Files.readString(Path.of(
                "src/runtimeGametestSupport/java/io/github/luma/minecraft/testing/StructureFixtureSnapshot.java"
        ));
        String generatedSource = Files.readString(Path.of(
                "src/gametest/java/io/github/luma/minecraft/testing/GeneratedRedstoneStructureFixtures.java"
        ));
        String scenarioSource = Files.readString(Path.of(
                "src/gametest/java/io/github/luma/minecraft/testing/SingleplayerStructureFixtureScenario.java"
        ));

        assertTrue(
                snapshotSource.contains("ignoringRedstoneTorchLitAt")
                        && snapshotSource.contains("ignoringRedstoneLampLitAt")
                        && snapshotSource.contains("differsOnlyByBlockProperty"),
                "Structure fixture snapshots must support exact-position redstone phase-property policies"
        );
        assertTrue(
                generatedSource.contains("comparisonPolicy(")
                        && !generatedSource.contains("undoComparisonPolicy(")
                        && generatedSource.contains("withRedstoneTorchLitAt(List.of(torchInverterBlockPos(volume).above()))")
                        && generatedSource.contains("withRedstoneLampLitAt(List.of(observerPulseObserverPos(volume).east()))"),
                "Generated redstone fixtures must own the volatile phase-property policy for undo and redo"
        );
        assertTrue(
                scenarioSource.contains("this.comparisonPolicy()")
                        && scenarioSource.contains("this.changedSnapshot.matches(redone, comparisonPolicy)")
                        && scenarioSource.contains("this.changedSnapshot.diff(redone, comparisonPolicy)"),
                "Structure fixture redo verification must apply the same comparison policy as undo"
        );
    }

    private static List<Path> javaFiles(Path... roots) throws IOException {
        try (Stream<Path> stream = Stream.of(roots)
                .filter(Files::exists)
                .flatMap(root -> {
                    try {
                        return Files.walk(root);
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                })) {
            return stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        } catch (IllegalStateException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }

    private static boolean importsAny(Path path, String... packagePrefixes) {
        String source;
        try {
            source = Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        for (String packagePrefix : packagePrefixes) {
            if (source.contains("import " + packagePrefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean sourceContainsAny(Path path, String... patterns) {
        String source;
        try {
            source = Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        for (String pattern : patterns) {
            if (source.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static void assertSourceExcludes(Path path, String message, String... forbiddenPatterns) throws IOException {
        String source = Files.readString(path);
        List<String> offenders = Stream.of(forbiddenPatterns)
                .filter(source::contains)
                .toList();

        assertTrue(offenders.isEmpty(), message + ": " + offenders);
    }

    private static void assertSourceIncludesAndExcludes(
            Path path,
            String message,
            List<String> requiredPatterns,
            List<String> forbiddenPatterns
    ) throws IOException {
        String source = Files.readString(path);
        List<String> missing = requiredPatterns.stream()
                .filter(pattern -> !source.contains(pattern))
                .toList();
        List<String> offenders = forbiddenPatterns.stream()
                .filter(source::contains)
                .toList();

        assertTrue(missing.isEmpty() && offenders.isEmpty(), message + "; missing=" + missing + ", offenders=" + offenders);
    }

    private static boolean isAllowedClientStorageAdapter(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.endsWith("src/client/java/io/github/luma/client/preview/PreviewCaptureCoordinator.java");
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static long lineCount(Path path) {
        try (Stream<String> lines = Files.lines(path)) {
            return lines.count();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
