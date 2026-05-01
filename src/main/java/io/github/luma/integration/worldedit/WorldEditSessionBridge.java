package io.github.luma.integration.worldedit;

import com.sk89q.worldedit.EmptyClipboardException;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.integration.common.ClipboardBridge;
import io.github.luma.integration.common.ExternalSelectionSnapshot;
import io.github.luma.integration.common.IntegrationCapability;
import io.github.luma.integration.common.SchematicBridge;
import io.github.luma.integration.common.SelectionProvider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class WorldEditSessionBridge implements SelectionProvider, ClipboardBridge, SchematicBridge {

    public static final String TOOL_ID = "worldedit";

    @Override
    public String toolId() {
        return TOOL_ID;
    }

    @Override
    public boolean available() {
        try {
            WorldEdit.getInstance();
            return true;
        } catch (RuntimeException | LinkageError exception) {
            return false;
        }
    }

    @Override
    public Set<IntegrationCapability> capabilities() {
        return EnumSet.of(
                IntegrationCapability.SELECTION,
                IntegrationCapability.CLIPBOARD,
                IntegrationCapability.SCHEMATIC
        );
    }

    @Override
    public Optional<ExternalSelectionSnapshot> currentSelection(ServerPlayer player) {
        SessionContext context = this.sessionContext(player).orElse(null);
        if (context == null) {
            return Optional.empty();
        }
        try {
            if (!(player.level() instanceof ServerLevel level)) {
                return Optional.empty();
            }
            com.sk89q.worldedit.world.World world = FabricAdapter.adapt(level);
            if (!context.session().isSelectionDefined(world)) {
                return Optional.empty();
            }
            Region region = context.session().getSelection(world);
            return Optional.of(new ExternalSelectionSnapshot(
                    this.toolId(),
                    this.actorName(context.actor()),
                    level.dimension().identifier().toString(),
                    this.bounds(region),
                    this.precise(region),
                    this.metadata(region)
            ));
        } catch (IncompleteRegionException | RuntimeException | LinkageError exception) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ExternalSelectionSnapshot> currentSelection(String actor, String dimensionId) {
        return Optional.empty();
    }

    @Override
    public boolean clipboardAvailable(ServerPlayer player) {
        SessionContext context = this.sessionContext(player).orElse(null);
        if (context == null) {
            return false;
        }
        try {
            ClipboardHolder holder = context.session().getClipboard();
            return holder != null && holder.getClipboard() != null;
        } catch (EmptyClipboardException exception) {
            return false;
        } catch (RuntimeException | LinkageError exception) {
            return false;
        }
    }

    @Override
    public boolean clipboardAvailable(String actor) {
        return false;
    }

    @Override
    public List<String> supportedClipboardFormats() {
        return this.clipboardFormats();
    }

    @Override
    public List<String> supportedSchematicFormats() {
        return this.clipboardFormats();
    }

    private Optional<SessionContext> sessionContext(ServerPlayer player) {
        if (player == null) {
            return Optional.empty();
        }
        try {
            Actor actor = FabricAdapter.adaptPlayer(player);
            LocalSession session = WorldEdit.getInstance().getSessionManager().getIfPresent(actor);
            return session == null ? Optional.empty() : Optional.of(new SessionContext(actor, session));
        } catch (RuntimeException | LinkageError exception) {
            return Optional.empty();
        }
    }

    private Bounds3i bounds(Region region) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        return new Bounds3i(
                new BlockPoint(min.x(), min.y(), min.z()),
                new BlockPoint(max.x(), max.y(), max.z())
        );
    }

    private boolean precise(Region region) {
        return region != null && region.getClass().getName().toLowerCase(Locale.ROOT).contains("cuboid");
    }

    private Map<String, String> metadata(Region region) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("api", "worldedit-local-session");
        metadata.put("selectionType", region.getClass().getSimpleName());
        metadata.put("volume", Long.toString(region.getVolume()));
        return metadata;
    }

    private String actorName(Actor actor) {
        if (actor == null || actor.getName() == null || actor.getName().isBlank()) {
            return "worldedit";
        }
        return "worldedit:" + actor.getName().toLowerCase(Locale.ROOT);
    }

    private List<String> clipboardFormats() {
        try {
            List<String> labels = new ArrayList<>();
            for (ClipboardFormat format : ClipboardFormats.getAll()) {
                labels.add(this.formatLabel(format));
            }
            return labels.stream()
                    .distinct()
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (RuntimeException | LinkageError exception) {
            return List.of();
        }
    }

    private String formatLabel(ClipboardFormat format) {
        String name = format.getName() == null || format.getName().isBlank()
                ? "clipboard"
                : format.getName();
        Set<String> extensions = format.getFileExtensions();
        if (extensions == null || extensions.isEmpty()) {
            return name;
        }
        return name + " (." + String.join(", .", extensions) + ")";
    }

    private record SessionContext(Actor actor, LocalSession session) {
    }
}
