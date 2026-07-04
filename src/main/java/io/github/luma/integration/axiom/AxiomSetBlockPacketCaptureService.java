package io.github.luma.integration.axiom;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.access.LumaAccessControl;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Gives Axiom single block packets an explicit Lumi source frame.
 */
public final class AxiomSetBlockPacketCaptureService {

    private static final String ACTION_PREFIX = "axiom-set-block-";
    private static final AxiomSetBlockPacketCaptureService INSTANCE =
            new AxiomSetBlockPacketCaptureService(UUID::randomUUID);

    private final Supplier<UUID> uuidSupplier;

    public static AxiomSetBlockPacketCaptureService getInstance() {
        return INSTANCE;
    }

    AxiomSetBlockPacketCaptureService(Supplier<UUID> uuidSupplier) {
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier");
    }

    public WorldMutationContext.SourceFrame pushPacketSource(Object playerCandidate) {
        SourceIdentity identity = this.sourceIdentity(playerCandidate);
        return WorldMutationContext.pushExternalSource(
                identity.source(),
                identity.actor(),
                identity.actionId(),
                identity.accessAllowed()
        );
    }

    SourceIdentity sourceIdentity(Object playerCandidate) {
        if (playerCandidate instanceof ServerPlayer player) {
            ServerLevel level = player.level() instanceof ServerLevel serverLevel ? serverLevel : null;
            return this.sourceIdentity(
                    player.getName() == null ? "" : player.getName().getString(),
                    level != null && level.getServer().isDedicatedServer(),
                    LumaAccessControl.getInstance().canUse(player)
            );
        }
        return this.sourceIdentity("", false, true);
    }

    SourceIdentity sourceIdentity(String playerName, boolean dedicatedServer, boolean playerAllowed) {
        return new SourceIdentity(
                WorldMutationSource.AXIOM,
                actorName(playerName),
                ACTION_PREFIX + this.nextActionSuffix(),
                !dedicatedServer || playerAllowed
        );
    }

    private String nextActionSuffix() {
        UUID uuid = this.uuidSupplier.get();
        return uuid == null ? UUID.randomUUID().toString() : uuid.toString();
    }

    private static String actorName(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return "axiom";
        }
        return "axiom:" + playerName;
    }

    record SourceIdentity(
            WorldMutationSource source,
            String actor,
            String actionId,
            boolean accessAllowed
    ) {
    }
}
