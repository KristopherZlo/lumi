package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.integration.axiom.AxiomSetBlockPacketCaptureService;
import io.github.luma.minecraft.access.LumaAccessControl;
import io.github.luma.minecraft.capture.AutoCheckpointService;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.world.WorldOperationManager;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerMixin {

    @Shadow
    public ServerPlayer player;

    @Unique
    private int luma$playerOperationDepth = 0;

    @Unique
    private static final WorldOperationManager LUMA_WORLD_OPERATIONS = WorldOperationManager.getInstance();

    @WrapMethod(method = "handleCustomPayload")
    private void luma$wrapCustomPayload(ServerboundCustomPayloadPacket packet, Operation<Void> original) {
        if (!luma$isAxiomPayload(packet)) {
            original.call(packet);
            return;
        }
        try (WorldMutationContext.SourceFrame ignored =
                     AxiomSetBlockPacketCaptureService.getInstance().pushPacketSource(this.player)) {
            original.call(packet);
        }
    }

    @Inject(
            method = {
                    "handleSignUpdate",
                    "handleSetCommandBlock",
                    "handleSetCommandMinecart",
                    "handleSetJigsawBlock",
                    "handleSetStructureBlock",
                    "handleJigsawGenerate",
                    "handleTestInstanceBlockAction",
                    "handleSetBeaconPacket"
            },
            at = @At("HEAD"),
            cancellable = true
    )
    private void luma$blockDirectWorldMutationPackets(CallbackInfo ci) {
        if (this.luma$worldMutationBlocked()) {
            ci.cancel();
        }
    }

    @WrapMethod(method = "handleChatCommand")
    private void luma$wrapChatCommand(ServerboundChatCommandPacket packet, Operation<Void> original) {
        if (this.luma$worldMutationBlocked()) {
            return;
        }
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
        if (this.luma$worldMutationBlocked()) {
            return;
        }
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
        this.luma$callWithPlayerSource(packet, original);
    }

    @WrapMethod(method = "handlePlayerAction")
    private void luma$wrapPlayerAction(ServerboundPlayerActionPacket packet, Operation<Void> original) {
        this.luma$callWithPlayerSource(packet, original);
    }

    @WrapMethod(method = "handleUseItemOn")
    private void luma$wrapUseItemOn(ServerboundUseItemOnPacket packet, Operation<Void> original) {
        this.luma$callWithPlayerSource(packet, original);
    }

    @WrapMethod(method = "handleUseItem")
    private void luma$wrapUseItem(ServerboundUseItemPacket packet, Operation<Void> original) {
        this.luma$callWithPlayerSource(packet, original);
    }

    @WrapMethod(method = "handleMovePlayer")
    private void luma$wrapMovePlayer(ServerboundMovePlayerPacket packet, Operation<Void> original) {
        if (!this.luma$worldMutationBlocked()) {
            original.call(packet);
        }
    }

    @WrapMethod(method = "handleMoveVehicle")
    private void luma$wrapMoveVehicle(ServerboundMoveVehiclePacket packet, Operation<Void> original) {
        if (!this.luma$worldMutationBlocked()) {
            original.call(packet);
        }
    }

    @WrapMethod(method = "handleContainerClick")
    private void luma$wrapContainerClick(ServerboundContainerClickPacket packet, Operation<Void> original) {
        this.luma$callWithPlayerSource(packet, original);
    }

    @WrapMethod(method = "handleContainerButtonClick")
    private void luma$wrapContainerButtonClick(ServerboundContainerButtonClickPacket packet, Operation<Void> original) {
        this.luma$callWithPlayerSource(packet, original);
    }

    @WrapMethod(method = "handleContainerSlotStateChanged")
    private void luma$wrapContainerSlotStateChanged(
            ServerboundContainerSlotStateChangedPacket packet,
            Operation<Void> original
    ) {
        this.luma$callWithPlayerSource(packet, original);
    }

    @WrapMethod(method = "handleSetCreativeModeSlot")
    private void luma$wrapSetCreativeModeSlot(ServerboundSetCreativeModeSlotPacket packet, Operation<Void> original) {
        this.luma$callWithPlayerSource(packet, original);
    }

    @Unique
    private void luma$callWithPlayerSource(Object packet, Operation<Void> original) {
        if (this.luma$worldMutationBlocked()) {
            return;
        }
        this.luma$pushPlayerSource();
        try {
            original.call(packet);
        } finally {
            this.luma$popPlayerSource();
        }
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

    @Unique
    private boolean luma$worldMutationBlocked() {
        return this.player != null
                && this.player.level() instanceof ServerLevel level
                && LUMA_WORLD_OPERATIONS.blocksWorldMutations(level);
    }

    @Unique
    private static boolean luma$isAxiomPayload(ServerboundCustomPayloadPacket packet) {
        return packet != null
                && packet.payload() != null
                && packet.payload().type() != null
                && packet.payload().type().id() != null
                && "axiom".equals(packet.payload().type().id().getNamespace());
    }
}
