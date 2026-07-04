package io.github.luma.integration.axiom;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.access.LumaAccessControl;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.lang.reflect.Field;
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
    private static final String INFINITE_REACH_ACTION_PREFIX = "axiom-infinite-reach-";
    private static final String FORCE_PLACE_ACTION_PREFIX = "axiom-force-place-";
    private static final String NO_UPDATES_ACTION_PREFIX = "axiom-no-updates-";
    private static final String SPECIAL_PLACE_ACTION_PREFIX = "axiom-special-place-";
    private static final String ANGEL_ACTION_PREFIX = "axiom-angel-place-";
    private static final String SYMMETRY_ACTION_PREFIX = "axiom-symmetry-place-";
    private static final String LIQUID_PLACE_ACTION_PREFIX = "axiom-liquid-place-";
    private static final int REASON_FORCE_PLACE = 4;
    private static final int REASON_NO_UPDATES = 8;
    private static final int REASON_CUSTOM_SHAPE_UPDATE = 16;
    private static final int REASON_CUSTOM_PLACEMENT = 32;
    private static final int REASON_INFINITE_REACH = 64;
    private static final int REASON_ANGEL = 128;
    private static final int REASON_SYMMETRY = 256;
    private static final int REASON_LIQUID_PLACE = 512;
    private static final int REASON_SKIP_INTERACTION = 1024;
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
        return this.pushPacketSource(null, playerCandidate);
    }

    public WorldMutationContext.SourceFrame pushPacketSource(Object packetCandidate, Object playerCandidate) {
        SourceIdentity identity = this.sourceIdentity(playerCandidate, this.actionPrefix(packetCandidate));
        return WorldMutationContext.pushExternalSource(
                identity.source(),
                identity.actor(),
                identity.actionId(),
                identity.accessAllowed()
        );
    }

    SourceIdentity sourceIdentity(Object playerCandidate) {
        return this.sourceIdentity(playerCandidate, ACTION_PREFIX);
    }

    private SourceIdentity sourceIdentity(Object playerCandidate, String actionPrefix) {
        if (playerCandidate instanceof ServerPlayer player) {
            ServerLevel level = player.level() instanceof ServerLevel serverLevel ? serverLevel : null;
            return this.sourceIdentity(
                    player.getName() == null ? "" : player.getName().getString(),
                    level != null && level.getServer().isDedicatedServer(),
                    LumaAccessControl.getInstance().canUse(player),
                    actionPrefix
            );
        }
        return this.sourceIdentity("", false, true, actionPrefix);
    }

    SourceIdentity sourceIdentity(String playerName, boolean dedicatedServer, boolean playerAllowed) {
        return this.sourceIdentity(playerName, dedicatedServer, playerAllowed, ACTION_PREFIX);
    }

    private SourceIdentity sourceIdentity(
            String playerName,
            boolean dedicatedServer,
            boolean playerAllowed,
            String actionPrefix
    ) {
        return new SourceIdentity(
                WorldMutationSource.AXIOM,
                actorName(playerName),
                (actionPrefix == null || actionPrefix.isBlank() ? ACTION_PREFIX : actionPrefix) + this.nextActionSuffix(),
                !dedicatedServer || playerAllowed
        );
    }

    String actionPrefix(Object packetCandidate) {
        int reason = this.intField(packetCandidate, "reason");
        if ((reason & REASON_INFINITE_REACH) != 0) {
            return INFINITE_REACH_ACTION_PREFIX;
        }
        if ((reason & REASON_FORCE_PLACE) != 0) {
            return FORCE_PLACE_ACTION_PREFIX;
        }
        if ((reason & REASON_NO_UPDATES) != 0) {
            return NO_UPDATES_ACTION_PREFIX;
        }
        if ((reason & REASON_LIQUID_PLACE) != 0) {
            return LIQUID_PLACE_ACTION_PREFIX;
        }
        if ((reason & REASON_ANGEL) != 0) {
            return ANGEL_ACTION_PREFIX;
        }
        if ((reason & REASON_SYMMETRY) != 0) {
            return SYMMETRY_ACTION_PREFIX;
        }
        if ((reason & (REASON_CUSTOM_SHAPE_UPDATE | REASON_CUSTOM_PLACEMENT | REASON_SKIP_INTERACTION)) != 0) {
            return SPECIAL_PLACE_ACTION_PREFIX;
        }
        return ACTION_PREFIX;
    }

    private int intField(Object target, String name) {
        if (target == null) {
            return 0;
        }
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
            return 0;
        }
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
