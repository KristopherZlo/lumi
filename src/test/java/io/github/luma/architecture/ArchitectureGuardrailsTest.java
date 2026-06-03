package io.github.luma.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

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
                MAIN_SOURCES.resolve("io/github/luma/minecraft/world/WorldOperationManager.java"), 1946,
                MAIN_SOURCES.resolve("io/github/luma/domain/service/RestoreService.java"), 1663,
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
        Path service = MAIN_SOURCES.resolve("io/github/luma/domain/service/ProjectIntegrityService.java");
        String source = Files.readString(service);

        assertTrue(
                !source.contains("DataInputStream")
                        && !source.contains("LZ4FrameInputStream")
                        && !source.contains("PATCH_MAGIC")
                        && !source.contains("SNAPSHOT_MAGIC")
                        && !source.contains("Files."),
                "ProjectIntegrityService must delegate raw storage layout and payload header parsing to storage repositories"
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
        Path controller = CLIENT_SOURCES.resolve("io/github/luma/ui/controller/ProjectScreenController.java");
        String source = Files.readString(controller);

        assertTrue(
                !source.contains("previewFile(")
                        && !source.contains("previewRequestFile(")
                        && !source.contains("java.nio.file.Files")
                        && !source.contains("Files.exists("),
                "ProjectScreenController must use services for preview paths and request state"
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
    void undoRedoSelectionDrainsPendingEntitySpawnsFirst() throws IOException {
        Path undoRedoService = MAIN_SOURCES.resolve("io/github/luma/domain/service/UndoRedoService.java");
        String source = Files.readString(undoRedoService);

        String drainCall = "EntityMutationTracker.drainPendingSpawns(level.getServer());";
        int drainIndex = source.indexOf(drainCall);
        int selectUndoIndex = source.indexOf("this.historyManager.selectUndo(project.id().toString())");
        int selectRedoIndex = source.indexOf("this.historyManager.selectRedo(project.id().toString())");

        assertTrue(
                drainIndex >= 0,
                "Undo/redo must drain pending entity spawn captures before selecting a live action"
        );
        assertTrue(
                drainIndex < selectUndoIndex && drainIndex < selectRedoIndex,
                "Undo/redo must not select an action before pending entity spawn captures are attached"
        );
    }

    @Test
    void pendingEntitySpawnQueueKeepsInitialPayloadForImmediateUndo() throws IOException {
        Path queue = MAIN_SOURCES.resolve("io/github/luma/minecraft/capture/EntitySpawnCaptureQueue.java");
        Path tracker = MAIN_SOURCES.resolve("io/github/luma/minecraft/capture/EntityMutationTracker.java");
        String source = Files.readString(queue);
        String trackerSource = Files.readString(tracker);

        assertTrue(
                source.contains("EntityPayload initialPayload"),
                "Pending spawn captures must keep the accepted entity payload for same-tick undo"
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
                trackerSource.contains("drainPendingSpawns(server, false)")
                        && trackerSource.contains("drainPendingSpawns(server, true)"),
                "Normal ticks should wait for stable entity lookup, while undo/redo may force same-tick spawn capture"
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
        String source = Files.readString(bootstrap);

        assertTrue(
                !source.contains("SingleplayerTestingService"),
                "LumaMod must use RuntimeTestingHooks instead of ticking the runtime test runner directly"
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
        assertTrue(
                buildScript.contains("jvmArgs('-Dlumi.testing.enabled=true')"),
                "Client GameTest run must enable runtime testing explicitly"
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
