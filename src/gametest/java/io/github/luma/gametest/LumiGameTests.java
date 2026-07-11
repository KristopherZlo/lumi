package io.github.luma.gametest;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.QuickRollbackService;
import io.github.luma.domain.service.VersionService;
import io.github.luma.domain.service.WorkZoneService;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

public final class LumiGameTests implements CustomTestMethodInvoker {

    private static final int MULTIPLAYER_SMOKE_MAX_TICKS = 200_000;

    @GameTest
    public void serverHarnessBoots(GameTestHelper context) {
        context.assertBlockPresent(Blocks.AIR, 0, 0, 0);
        context.succeed();
    }

    @GameTest
    public void fallingBlockSpawnDoesNotCrashEntityCapture(GameTestHelper context) {
        context.setBlock(0, 0, 0, Blocks.STONE);
        context.setBlock(0, 1, 0, Blocks.AIR);
        context.setBlock(0, 2, 0, Blocks.SAND);
        context.tickBlock(new BlockPos(0, 2, 0));
        context.succeedWhenBlockPresent(Blocks.SAND, 0, 1, 0);
    }

    @GameTest
    public void cropRandomTickDoesNotCrashGrowthCapture(GameTestHelper context) {
        BlockPos farmland = new BlockPos(0, 0, 0);
        BlockPos crop = farmland.above();
        context.setBlock(farmland, Blocks.FARMLAND);
        context.setBlock(crop, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 0));
        context.randomTick(crop);
        context.succeed();
    }

    @GameTest(maxTicks = MULTIPLAYER_SMOKE_MAX_TICKS)
    public void multiplayerWorkZonesRollbackSmoke(GameTestHelper context) {
        new MultiplayerWorkZoneSmoke(context).start();
    }

    @Override
    public void invokeTestMethod(GameTestHelper context, Method method) throws ReflectiveOperationException {
        context.setBlock(0, 0, 0, Blocks.AIR);
        method.invoke(this, context);
    }

    private static final class MultiplayerWorkZoneSmoke {

        private static final String ALICE = "Lumi smoke Alice";
        private static final String BOB = "Lumi smoke Bob";
        private static final long ALICE_SEED = 0x5104E5L;
        private static final long BOB_SEED = 0xB0B51L;
        private static final int BLOCKS_PER_PLAYER = 12;
        private static final long OPERATION_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(45);
        private static final Block[] PALETTE = {
                Blocks.STONE, Blocks.OAK_PLANKS, Blocks.COPPER_BLOCK, Blocks.GLASS,
                Blocks.GOLD_BLOCK, Blocks.EMERALD_BLOCK, Blocks.LAPIS_BLOCK, Blocks.AMETHYST_BLOCK
        };

        private final GameTestHelper context;
        private final ServerLevel level;
        private final MinecraftServer server;
        private final ProjectService projectService = new ProjectService();
        private final VersionService versionService = new VersionService();
        private final QuickRollbackService rollbackService = new QuickRollbackService();
        private final WorkZoneService zoneService = new WorkZoneService();
        private final HistoryCaptureManager captureManager = HistoryCaptureManager.getInstance();
        private final WorldOperationManager operations = WorldOperationManager.getInstance();
        private final List<String> behavior = new ArrayList<>();

        private ProjectLayout layout;
        private String projectName = "";
        private String projectId = "";
        private List<BlockPos> alice = List.of();
        private List<BlockPos> bob = List.of();
        private OperationHandle pending;
        private String pendingLabel = "";
        private Step afterOperation = () -> { };
        private boolean failed;
        private long pendingStartedAtNanos;

        private MultiplayerWorkZoneSmoke(GameTestHelper context) {
            this.context = context;
            this.level = context.getLevel();
            this.server = this.level.getServer();
        }

        private void start() {
            try {
                this.setup();
                this.saveActor(ALICE, this.alice, ALICE_SEED, "alice-save", () -> {
                    this.verifyRandom(this.alice, ALICE_SEED, "Alice saved blocks");
                    this.saveActor(BOB, this.bob, BOB_SEED, "bob-save", this::afterBobSave);
                });
            } catch (Throwable exception) {
                this.fail(exception);
            }
        }

        private void setup() throws IOException {
            BlockPos base = this.context.absolutePos(new BlockPos(2, 2, 2));
            this.alice = this.randomPositions(this.cellAnchor(base, 0), 0xA11CEL);
            this.bob = this.randomPositions(this.cellAnchor(base, 2), 0xB0B5L);
            this.clearTargets();

            var project = this.projectService.createProject(
                    this.level,
                    "Lumi Multiplayer Smoke " + System.currentTimeMillis(),
                    corner(this.allPositions(), false),
                    corner(this.allPositions(), true),
                    ALICE
            );
            this.projectName = project.name();
            this.projectId = project.id().toString();
            this.layout = this.projectService.resolveLayout(this.server, this.projectName);
            this.zoneService.createZone(this.layout, this.projectId, "Alice zone", ALICE, Instant.now());
            this.zoneService.createZone(this.layout, this.projectId, "Bob zone", BOB, Instant.now());
            this.record("project=" + this.projectName);
            this.record("players=" + ALICE + "," + BOB);
        }

        private void saveActor(String actor, List<BlockPos> positions, long seed, String label, Step after) throws Exception {
            this.placeRandom(actor, positions, seed);
            this.waitFor(this.versionService.startSaveVersion(this.level, this.projectName, label, actor), label, after);
        }

        private void afterBobSave() throws Exception {
            this.verifyRandom(this.alice, ALICE_SEED, "Alice blocks after Bob save");
            this.verifyRandom(this.bob, BOB_SEED, "Bob saved blocks");
            this.requireWorkZoneVersions(2);
            this.verifyAmbientMutationDoesNotOpenDraft();
            this.overwriteFromAttributedRoot(ALICE, this.alice, Blocks.REDSTONE_BLOCK.defaultBlockState());
            this.verifyBlock(this.alice, Blocks.REDSTONE_BLOCK, "Alice pending rollback blocks");
            this.waitFor(
                    this.rollbackService.quickRollback(this.level, this.projectName, Bounds3i.of(corner(this.alice, false), corner(this.alice, true))),
                    "alice-zone-rollback",
                    this::afterRollback
            );
        }

        private void afterRollback() throws Exception {
            this.verifyRandom(this.alice, ALICE_SEED, "Alice blocks after zone rollback");
            this.verifyRandom(this.bob, BOB_SEED, "Bob blocks after Alice rollback");
            this.requireActiveZoneCells(ALICE);
            this.requireActiveZoneCells(BOB);
            this.record("rollback=alice-zone-only");
            this.writeLog("PASSED");
            this.context.succeed();
        }

        private void waitFor(OperationHandle handle, String label, Step after) {
            this.pending = handle;
            this.pendingLabel = label;
            this.afterOperation = after;
            this.pendingStartedAtNanos = System.nanoTime();
            this.record("queued " + label + " operation=" + handle.id());
            this.context.runAfterDelay(1L, this::poll);
        }

        private void poll() {
            if (this.failed) {
                return;
            }
            try {
                OperationSnapshot snapshot = this.operations.snapshot(this.server, this.pending).orElse(null);
                boolean active = this.operations.hasActiveOperation(this.server);
                if (snapshot == null || !snapshot.terminal() || active) {
                    if (System.nanoTime() - this.pendingStartedAtNanos > OPERATION_TIMEOUT_NANOS) {
                        throw new AssertionError(this.pendingLabel + " timed out: " + describe(snapshot, active));
                    }
                    this.context.runAfterDelay(1L, this::poll);
                    return;
                }
                this.record("completed " + this.pendingLabel + " stage=" + snapshot.stage() + " detail=" + snapshot.detail());
                if (snapshot.failed()) {
                    throw new AssertionError(this.pendingLabel + " failed: " + snapshot.detail());
                }
                Step next = this.afterOperation;
                this.pending = null;
                this.afterOperation = () -> { };
                next.run();
            } catch (Throwable exception) {
                this.fail(exception);
            }
        }

        private void placeRandom(String actor, List<BlockPos> positions, long seed) throws IOException {
            Random random = new Random(seed);
            try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, actor, true)) {
                for (BlockPos pos : positions) {
                    this.place(actor, pos, nextState(random));
                }
            }
            this.record(actor + " placed " + positions.size() + " randomized blocks");
        }

        private void verifyAmbientMutationDoesNotOpenDraft() throws IOException {
            BlockPos probe = this.alice.getFirst();
            BlockState original = this.level.getBlockState(probe);
            this.level.setBlock(probe, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            if (this.captureManager.snapshotDraft(this.server, this.projectId).isPresent()) {
                throw new AssertionError("Ambient world mutation opened a recovery draft");
            }
            this.level.setBlock(probe, original, 3);
        }

        private void overwriteFromAttributedRoot(String actor, List<BlockPos> positions, BlockState state) throws IOException {
            try (WorldMutationContext.SourceFrame ignored =
                         WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, actor, true)) {
                this.place(actor, positions.getFirst(), state);
            }
            if (WorldMutationContext.currentSource() != WorldMutationSource.SYSTEM
                    || !WorldMutationContext.currentActionId().isBlank()) {
                throw new AssertionError("Regression mutation must run without causal attribution");
            }
            positions.subList(1, positions.size()).forEach(pos -> this.level.setBlock(pos, state, 3));
            this.record("player root caused " + (positions.size() - 1)
                    + " world mutations without an action id using " + state.getBlock());
        }

        private void place(String actor, BlockPos pos, BlockState state) throws IOException {
            this.level.setBlock(pos, state, 3);
            this.zoneService.touchBlock(this.layout, actor, BlockPoint.from(pos), Instant.now());
        }

        private void verifyRandom(List<BlockPos> positions, long seed, String label) {
            Random random = new Random(seed);
            for (BlockPos pos : positions) {
                this.requireBlock(pos, nextState(random).getBlock(), label);
            }
            this.record("verified " + label + " count=" + positions.size());
        }

        private void verifyBlock(List<BlockPos> positions, Block block, String label) {
            positions.forEach(pos -> this.requireBlock(pos, block, label));
            this.record("verified " + label + " count=" + positions.size());
        }

        private void requireBlock(BlockPos pos, Block expected, String label) {
            Block actual = this.level.getBlockState(pos).getBlock();
            if (actual != expected) {
                throw new AssertionError(label + " mismatch at " + pos + ": expected=" + expected + " actual=" + actual);
            }
        }

        private void requireWorkZoneVersions(int minimum) throws IOException {
            int count = this.projectService.loadWorkZoneVersions(this.server, this.projectName).size();
            if (count < minimum) {
                throw new AssertionError("Expected at least " + minimum + " work-zone versions, found " + count);
            }
            this.record("workZoneVersions=" + count);
        }

        private void requireActiveZoneCells(String actor) throws IOException {
            int cells = this.zoneService.activeZone(this.layout, actor)
                    .map(zone -> zone.cells().size())
                    .orElse(0);
            if (cells <= 0) {
                throw new AssertionError("Active zone has no persisted cells for " + actor);
            }
            this.record(actor + " zoneCells=" + cells);
        }

        private void clearTargets() {
            try (WorldMutationContext.SuppressionFrame ignored = WorldMutationContext.pushCaptureSuppression()) {
                for (BlockPos pos : this.allPositions()) {
                    this.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }

        private BlockPos cellAnchor(BlockPos base, int cellOffsetX) {
            int cellX = Math.floorDiv(base.getX(), WorkZoneCell.SIZE) + cellOffsetX;
            int cellZ = Math.floorDiv(base.getZ(), WorkZoneCell.SIZE);
            return new BlockPos(cellX * WorkZoneCell.SIZE + 1, base.getY(), cellZ * WorkZoneCell.SIZE + 1);
        }

        private List<BlockPos> randomPositions(BlockPos anchor, long seed) {
            Random random = new Random(seed);
            List<BlockPos> positions = new ArrayList<>();
            while (positions.size() < BLOCKS_PER_PLAYER) {
                BlockPos pos = anchor.offset(random.nextInt(7), random.nextInt(3), random.nextInt(7));
                if (!positions.contains(pos)) {
                    positions.add(pos);
                }
            }
            return List.copyOf(positions);
        }

        private List<BlockPos> allPositions() {
            List<BlockPos> positions = new ArrayList<>(this.alice);
            positions.addAll(this.bob);
            return positions;
        }

        private void record(String line) {
            this.behavior.add(Instant.now() + " " + line);
        }

        private void writeLog(String status) throws IOException {
            Path root = this.server.getWorldPath(LevelResource.ROOT).resolve("lumi").resolve("test-logs");
            Files.createDirectories(root);
            List<String> lines = new ArrayList<>();
            lines.add("Lumi multiplayer work-zone smoke: " + status);
            lines.addAll(this.behavior);
            Files.write(root.resolve("multiplayer-work-zone-smoke-" + System.currentTimeMillis() + ".log"), lines);
        }

        private void fail(Throwable exception) {
            this.failed = true;
            this.record("failed " + message(exception));
            try {
                this.writeLog("FAILED");
            } catch (IOException ignored) {
                // Best-effort smoke log; the GameTest failure below is authoritative.
            }
            this.context.fail(message(exception));
        }

        private static BlockState nextState(Random random) {
            return PALETTE[random.nextInt(PALETTE.length)].defaultBlockState();
        }

        private static BlockPos corner(List<BlockPos> positions, boolean max) {
            int x = positions.getFirst().getX();
            int y = positions.getFirst().getY();
            int z = positions.getFirst().getZ();
            for (BlockPos pos : positions) {
                x = max ? Math.max(x, pos.getX()) : Math.min(x, pos.getX());
                y = max ? Math.max(y, pos.getY()) : Math.min(y, pos.getY());
                z = max ? Math.max(z, pos.getZ()) : Math.min(z, pos.getZ());
            }
            return new BlockPos(x, y, z);
        }

        private static String message(Throwable exception) {
            String message = exception.getMessage();
            return message == null || message.isBlank()
                    ? exception.getClass().getSimpleName()
                    : message;
        }

        private static String describe(OperationSnapshot snapshot, boolean active) {
            if (snapshot == null) {
                return "no operation snapshot, active=" + active;
            }
            return "stage=" + snapshot.stage() + ", active=" + active + ", detail=" + snapshot.detail();
        }

        @FunctionalInterface
        private interface Step {
            void run() throws Exception;
        }
    }
}
