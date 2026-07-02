package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.debug.LumaLoadLog;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.access.LumaAccessControl;
import io.github.luma.minecraft.capture.AutoCheckpointService;
import io.github.luma.minecraft.capture.EntityCausalContextRegistry;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerMixin {

    @Unique
    private static final EntityCausalContextRegistry LUMA_ENTITY_CAUSAL_CONTEXTS =
            EntityCausalContextRegistry.getInstance();

    @Shadow
    public ServerPlayer player;

    @Unique
    private int luma$playerOperationDepth = 0;

    @WrapMethod(method = "handleChatCommand")
    private void luma$wrapChatCommand(ServerboundChatCommandPacket packet, Operation<Void> original) {
        this.luma$pushPlayerSource();
        AutoCheckpointService.getInstance().checkpointBeforeCommand(this.player, packet.command());
        try {
            original.call(packet);
        } finally {
            this.luma$popPlayerSource();
        }
    }

    @WrapMethod(method = "handleSignedChatCommand")
    private void luma$wrapSignedChatCommand(ServerboundChatCommandSignedPacket packet, Operation<Void> original) {
        this.luma$pushPlayerSource();
        AutoCheckpointService.getInstance().checkpointBeforeCommand(this.player, packet.command());
        try {
            original.call(packet);
        } finally {
            this.luma$popPlayerSource();
        }
    }

    @WrapMethod(method = "handleInteract")
    private void luma$wrapInteract(ServerboundInteractPacket packet, Operation<Void> original) {
        this.luma$pushPlayerSource();
        try {
            this.luma$rememberInteractedEntity(packet);
            original.call(packet);
        } finally {
            this.luma$popPlayerSource();
        }
    }

    @Unique
    private void luma$rememberInteractedEntity(ServerboundInteractPacket packet) {
        if (this.player == null) {
            return;
        }

        if (!(this.player.level() instanceof ServerLevel level)) {
            return;
        }

        Entity target = packet.getTarget(level);
        if (target != null) {
            boolean remembered = LUMA_ENTITY_CAUSAL_CONTEXTS.rememberCurrentPlayerAction(target, level);
            this.luma$logCreeperInteractContext(target, level, remembered);
        }
    }

    @Unique
    private void luma$logCreeperInteractContext(Entity target, ServerLevel level, boolean remembered) {
        if (!(target instanceof Creeper) || level == null) {
            return;
        }
        LumaLoadLog.event("creeper-explosion", "interact-context",
                "remembered=" + remembered
                        + ", player=" + (this.player == null ? "player" : this.player.getName().getString())
                        + ", uuid=" + target.getUUID()
                        + ", source=" + WorldMutationContext.currentSource()
                        + ", action=" + WorldMutationContext.currentActionId()
                        + ", access=" + WorldMutationContext.currentAccessAllowed()
                        + ", time=" + level.getGameTime()
                        + ", pos=" + target.blockPosition());
    }

    @Unique
    private void luma$pushPlayerSource() {
        this.luma$playerOperationDepth += 1;
        WorldMutationContext.pushPlayerSource(
                WorldMutationSource.PLAYER,
                this.player == null ? "player" : this.player.getName().getString(),
                LumaAccessControl.getInstance().canUse(this.player) || WorldMutationContext.currentAccessAllowed()
        );
    }

    @Unique
    private void luma$popPlayerSource() {
        if (this.luma$playerOperationDepth <= 0) {
            return;
        }

        this.luma$playerOperationDepth -= 1;
        WorldMutationContext.popSource();
    }
}
