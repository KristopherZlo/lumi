package io.github.luma.gbreak.client;

import io.github.luma.gbreak.bug.GameBreakingBug;
import io.github.luma.gbreak.mixin.client.GameRendererAccessor;
import java.lang.reflect.Field;
import io.github.luma.gbreak.state.BugStateController;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.glfw.GLFW;

final class ClientBugRuntime {

    private static final int SINGLE_CHUNK_VIEW_DISTANCE = 2;
    private static final ChunkStorageAccess CHUNK_STORAGE_ACCESS = ChunkStorageAccess.create();

    private final BugStateController bugState = BugStateController.getInstance();
    private final GlobalCorruptionHudRenderer globalCorruptionHudRenderer = new GlobalCorruptionHudRenderer();
    private GameBreakingBug appliedBug = GameBreakingBug.NONE;
    private Integer originalViewDistance;
    private GlobalCorruptionSnapshot globalCorruptionSnapshot;

    void register() {
        ClientTickEvents.START_CLIENT_TICK.register(this::startTick);
        ClientTickEvents.END_CLIENT_TICK.register(this::endTick);
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> this.globalCorruptionHudRenderer.render(
                drawContext,
                MinecraftClient.getInstance()
        ));
    }

    private void startTick(MinecraftClient client) {
        GameBreakingBug activeBug = this.resolveClientBug(client);
        this.transitionBugState(client, activeBug);

        boolean phaseRequested = client != null
                && client.player != null
                && activeBug == GameBreakingBug.GHOST_PLAYER
                && this.isAltHeld(client);
        this.bugState.setAltClipRequested(phaseRequested);
        this.tickGhostPlayer(client == null ? null : client.player, phaseRequested);
    }

    private void endTick(MinecraftClient client) {
        GameBreakingBug activeBug = this.resolveClientBug(client);
        this.transitionBugState(client, activeBug);

        if (activeBug == GameBreakingBug.SINGLE_CHUNK) {
            this.tickSingleChunk(client);
        }
        if (activeBug == GameBreakingBug.GLOBAL_CORRUPTION) {
            this.tickGlobalCorruption(client);
        }
        if (activeBug == GameBreakingBug.PERFORMANCE_COLLAPSE) {
            this.induceClientLag();
        }
    }

    private GameBreakingBug resolveClientBug(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null) {
            return GameBreakingBug.NONE;
        }
        return this.bugState.activeBug();
    }

    private void transitionBugState(MinecraftClient client, GameBreakingBug activeBug) {
        if (activeBug == this.appliedBug) {
            return;
        }

        this.restoreBugState(client, this.appliedBug);
        this.appliedBug = activeBug;
        this.initializeBugState(client, activeBug);
    }

    private void initializeBugState(MinecraftClient client, GameBreakingBug activeBug) {
        if (client == null) {
            return;
        }

        if (activeBug == GameBreakingBug.SINGLE_CHUNK) {
            this.originalViewDistance = client.options.getViewDistance().getValue();
            client.options.getViewDistance().setValue(SINGLE_CHUNK_VIEW_DISTANCE);
            client.worldRenderer.reload();
            return;
        }

        if (activeBug == GameBreakingBug.GLOBAL_CORRUPTION) {
            GameOptions options = client.options;
            this.globalCorruptionSnapshot = new GlobalCorruptionSnapshot(
                    options.getFov().getValue(),
                    options.getGamma().getValue(),
                    options.getFovEffectScale().getValue(),
                    options.getDistortionEffectScale().getValue(),
                    options.getDarknessEffectScale().getValue(),
                    options.getMenuBackgroundBlurriness().getValue(),
                    options.getBobView().getValue(),
                    options.hudHidden,
                    client.gameRenderer.getPostProcessorId()
            );
            this.ensureBlurPostProcessor(client);
        }
    }

    private void restoreBugState(MinecraftClient client, GameBreakingBug previousBug) {
        if (client == null) {
            return;
        }

        if (previousBug == GameBreakingBug.GHOST_PLAYER) {
            this.restoreGhostPlayer(client.player);
            return;
        }

        if (previousBug == GameBreakingBug.SINGLE_CHUNK) {
            if (this.originalViewDistance != null) {
                client.options.getViewDistance().setValue(this.originalViewDistance);
                client.worldRenderer.reload();
                this.originalViewDistance = null;
            }
            return;
        }

        if (previousBug == GameBreakingBug.GLOBAL_CORRUPTION && this.globalCorruptionSnapshot != null) {
            GameOptions options = client.options;
            options.getFov().setValue(this.globalCorruptionSnapshot.fov());
            options.getGamma().setValue(this.globalCorruptionSnapshot.gamma());
            options.getFovEffectScale().setValue(this.globalCorruptionSnapshot.fovEffectScale());
            options.getDistortionEffectScale().setValue(this.globalCorruptionSnapshot.distortionEffectScale());
            options.getDarknessEffectScale().setValue(this.globalCorruptionSnapshot.darknessEffectScale());
            options.getMenuBackgroundBlurriness().setValue(this.globalCorruptionSnapshot.menuBackgroundBlurriness());
            options.getBobView().setValue(this.globalCorruptionSnapshot.bobView());
            options.hudHidden = this.globalCorruptionSnapshot.hudHidden();
            if (this.globalCorruptionSnapshot.postProcessorId() == null) {
                client.gameRenderer.clearPostProcessor();
            } else {
                ((GameRendererAccessor) client.gameRenderer).gbreak$setPostProcessor(this.globalCorruptionSnapshot.postProcessorId());
            }
            this.globalCorruptionSnapshot = null;
        }
    }

    private void tickGhostPlayer(ClientPlayerEntity player, boolean phaseRequested) {
        if (player == null || player.isSpectator()) {
            return;
        }

        boolean enableNoClip = phaseRequested && this.hasSupportBelow(player);
        player.noClip = enableNoClip;
        player.setNoGravity(enableNoClip);
        if (!enableNoClip) {
            return;
        }

        var velocity = player.getVelocity();
        player.setVelocity(velocity.x, 0.0D, velocity.z);
    }

    private void restoreGhostPlayer(ClientPlayerEntity player) {
        if (player == null || player.isSpectator()) {
            return;
        }

        player.noClip = false;
        player.setNoGravity(false);
    }

    private void tickSingleChunk(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null) {
            return;
        }

        ClientChunkManager chunkManager = client.world.getChunkManager();
        AtomicReferenceArray<WorldChunk> chunks = CHUNK_STORAGE_ACCESS.read(chunkManager);
        if (chunks == null) {
            return;
        }
        ChunkPos playerChunk = client.player.getChunkPos();
        List<ChunkPos> unloads = new ArrayList<>();
        for (int index = 0; index < chunks.length(); index++) {
            WorldChunk chunk = chunks.get(index);
            if (chunk == null || chunk.isEmpty()) {
                continue;
            }

            ChunkPos chunkPos = chunk.getPos();
            if (!chunkPos.equals(playerChunk)) {
                unloads.add(chunkPos);
            }
        }

        if (unloads.isEmpty()) {
            return;
        }

        for (ChunkPos chunkPos : unloads) {
            chunkManager.unload(chunkPos);
        }
        client.worldRenderer.scheduleTerrainUpdate();
    }

    private void tickGlobalCorruption(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null || this.globalCorruptionSnapshot == null) {
            return;
        }

        long time = client.world.getTime();
        GameOptions options = client.options;
        options.getFov().setValue(this.clampInt(
                this.globalCorruptionSnapshot.fov() + (int) Math.round((Math.sin(time * 0.43D) * 34.0D) + (Math.cos(time * 0.17D) * 18.0D)),
                30,
                110
        ));
        options.getGamma().setValue(this.clampDouble(
                this.globalCorruptionSnapshot.gamma() + 0.45D + (Math.sin(time * 0.21D) * 0.4D) + (Math.cos(time * 0.09D) * 0.2D),
                0.0D,
                1.0D
        ));
        options.getFovEffectScale().setValue(this.clampDouble(
                this.globalCorruptionSnapshot.fovEffectScale() + 0.35D + (Math.sin(time * 0.35D) * 0.35D),
                0.0D,
                1.0D
        ));
        options.getDistortionEffectScale().setValue(this.clampDouble(
                this.globalCorruptionSnapshot.distortionEffectScale() + 0.55D + (Math.cos(time * 0.16D) * 0.25D),
                0.0D,
                1.0D
        ));
        options.getDarknessEffectScale().setValue(this.clampDouble(
                this.globalCorruptionSnapshot.darknessEffectScale() + 0.35D + (Math.sin(time * 0.14D) * 0.3D),
                0.0D,
                1.0D
        ));
        options.getMenuBackgroundBlurriness().setValue(this.clampInt(
                this.globalCorruptionSnapshot.menuBackgroundBlurriness() + 7 + (int) Math.round(Math.sin(time * 0.28D) * 4.0D),
                0,
                10
        ));
        options.getBobView().setValue(true);
        options.hudHidden = time % 17L < 3L;
        this.ensureBlurPostProcessor(client);
    }

    private void ensureBlurPostProcessor(MinecraftClient client) {
        Identifier blurId = GameRendererAccessor.gbreak$getBlurId();
        if (blurId.equals(client.gameRenderer.getPostProcessorId())) {
            return;
        }
        ((GameRendererAccessor) client.gameRenderer).gbreak$setPostProcessor(blurId);
    }

    private boolean hasSupportBelow(ClientPlayerEntity player) {
        Box box = player.getBoundingBox();
        double sampleY = box.minY - 0.08D;
        double inset = 0.05D;
        double[] sampleXs = {
                player.getX(),
                box.minX + inset,
                box.maxX - inset
        };
        double[] sampleZs = {
                player.getZ(),
                box.minZ + inset,
                box.maxZ - inset
        };
        for (double sampleX : sampleXs) {
            for (double sampleZ : sampleZs) {
                BlockPos supportPos = BlockPos.ofFloored(sampleX, sampleY, sampleZ);
                if (!player.getEntityWorld().getBlockState(supportPos).getCollisionShape(player.getEntityWorld(), supportPos).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isAltHeld(MinecraftClient client) {
        if (client.getWindow() == null) {
            return false;
        }
        long handle = client.getWindow().getHandle();
        return org.lwjgl.glfw.GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || org.lwjgl.glfw.GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
    }

    private void induceClientLag() {
        byte[] garbage = new byte[2_000_000];
        for (int index = 0; index < garbage.length; index += 4096) {
            garbage[index] = (byte) index;
        }

        long stallUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(30L);
        double sink = 0.0D;
        while (System.nanoTime() < stallUntil) {
            sink += Math.sqrt((sink + 1.0D) * 0.5D);
        }
        if (sink == Double.MIN_VALUE) {
            throw new IllegalStateException("Unreachable");
        }
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record GlobalCorruptionSnapshot(
            int fov,
            double gamma,
            double fovEffectScale,
            double distortionEffectScale,
            double darknessEffectScale,
            int menuBackgroundBlurriness,
            boolean bobView,
            boolean hudHidden,
            Identifier postProcessorId
    ) {
    }

    private static final class ChunkStorageAccess {

        private final Field chunkMapField;
        private final Field chunksField;

        private ChunkStorageAccess(Field chunkMapField, Field chunksField) {
            this.chunkMapField = chunkMapField;
            this.chunksField = chunksField;
        }

        private static ChunkStorageAccess create() {
            try {
                MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();
                String runtimeNamespace = resolver.getCurrentRuntimeNamespace();
                String chunkManagerClassName = mapClassName(
                        resolver,
                        runtimeNamespace,
                        "net.minecraft.client.world.ClientChunkManager"
                );
                String chunkMapClassName = mapClassName(
                        resolver,
                        runtimeNamespace,
                        "net.minecraft.client.world.ClientChunkManager$ClientChunkMap"
                );
                String chunkMapFieldName = resolver.mapFieldName(
                        "named",
                        "net.minecraft.client.world.ClientChunkManager",
                        "chunks",
                        "Lnet/minecraft/client/world/ClientChunkManager$ClientChunkMap;"
                );
                String chunksFieldName = resolver.mapFieldName(
                        "named",
                        "net.minecraft.client.world.ClientChunkManager$ClientChunkMap",
                        "chunks",
                        "Ljava/util/concurrent/atomic/AtomicReferenceArray;"
                );

                Class<?> chunkManagerClass = Class.forName(chunkManagerClassName);
                Field chunkMapField = chunkManagerClass.getDeclaredField(chunkMapFieldName);
                chunkMapField.setAccessible(true);
                Class<?> chunkMapType = Class.forName(chunkMapClassName);
                Field chunksField = chunkMapType.getDeclaredField(chunksFieldName);
                chunksField.setAccessible(true);
                return new ChunkStorageAccess(chunkMapField, chunksField);
            } catch (ReflectiveOperationException exception) {
                return new ChunkStorageAccess(null, null);
            }
        }

        private static String mapClassName(MappingResolver resolver, String runtimeNamespace, String namedClassName) {
            if ("named".equals(runtimeNamespace)) {
                return namedClassName;
            }
            return resolver.mapClassName("named", namedClassName).replace('/', '.');
        }

        @SuppressWarnings("unchecked")
        private AtomicReferenceArray<WorldChunk> read(ClientChunkManager chunkManager) {
            if (this.chunkMapField == null || this.chunksField == null) {
                return null;
            }

            try {
                Object chunkMap = this.chunkMapField.get(chunkManager);
                if (chunkMap == null) {
                    return null;
                }
                return (AtomicReferenceArray<WorldChunk>) this.chunksField.get(chunkMap);
            } catch (IllegalAccessException exception) {
                return null;
            }
        }
    }
}
