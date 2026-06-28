package io.github.luma.client.selection;

import io.github.luma.LumaMod;
import io.github.luma.client.input.LumiClientKeyBindings;
import io.github.luma.client.onboarding.ClientContextualHelpHint;
import io.github.luma.client.onboarding.ClientContextualHelpService;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.WorkZoneService;
import io.github.luma.ui.controller.ClientProjectAccess;
import io.github.luma.ui.overlay.RoundedHudRenderer;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

/**
 * Shows the crosshair hint for Lumi's wooden-sword region selector.
 */
public final class LumiRegionSelectionTeachingController {

    private static final Identifier HUD_ELEMENT_ID = Identifier.fromNamespaceAndPath(
            LumaMod.MOD_ID,
            "selection_tool_hint"
    );

    private final ProjectService projectService;
    private final WorkZoneService workZoneService;
    private final ClientContextualHelpService helpService;
    private final SelectionToolTeachingState teachingState;
    private boolean hudVisible;
    private KeyMapping cachedActionKey;
    private String activeZoneName = "";

    public LumiRegionSelectionTeachingController() {
        this(new ProjectService(), new WorkZoneService(), new ClientContextualHelpService(), new SelectionToolTeachingState());
    }

    LumiRegionSelectionTeachingController(
            ProjectService projectService,
            WorkZoneService workZoneService,
            ClientContextualHelpService helpService,
            SelectionToolTeachingState teachingState
    ) {
        this.projectService = projectService;
        this.workZoneService = workZoneService;
        this.helpService = helpService;
        this.teachingState = teachingState;
    }

    public void registerHud() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.OVERLAY_MESSAGE,
                HUD_ELEMENT_ID,
                this::render
        );
    }

    public void tick(Minecraft client) {
        boolean inputActive = this.canTeach(client);
        boolean toolHeld = inputActive && this.selectionToolHeld(client.player);
        this.hudVisible = toolHeld;
        this.cachedActionKey = this.actionKey();
        this.activeZoneName = toolHeld ? this.activeZoneName(client) : "";
        if (!toolHeld) {
            return;
        }
        if (this.teachingState.active() && this.teachingState.tickDisplay()) {
            this.helpService.dismissHint(ClientContextualHelpHint.SELECTION_TOOL);
        }

        boolean hintAllowed = this.helpService.shouldShowHint(ClientContextualHelpHint.SELECTION_TOOL);
        this.teachingState.observeHintAllowed(hintAllowed);
        if (!this.teachingState.shouldStart(inputActive, true, hintAllowed)) {
            return;
        }

        this.teachingState.start();
    }

    private boolean canTeach(Minecraft client) {
        if (client == null
                || client.player == null
                || client.level == null
                || client.screen != null
                || client.getOverlay() != null
                || !client.hasSingleplayerServer()) {
            return false;
        }
        try {
            ServerLevel level = ClientProjectAccess.requireSingleplayerServer(client).getLevel(client.level.dimension());
            if (level == null) {
                return false;
            }
            return this.projectService.findWorldProject(level).isPresent();
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean selectionToolHeld(Player player) {
        return player != null
                && (player.getItemInHand(InteractionHand.MAIN_HAND).is(Items.WOODEN_SWORD)
                || player.getItemInHand(InteractionHand.OFF_HAND).is(Items.WOODEN_SWORD));
    }

    private void render(GuiGraphics graphics, net.minecraft.client.DeltaTracker tickCounter) {
        Minecraft client = Minecraft.getInstance();
        if (!this.hudVisible || client == null || client.options.hideGui) {
            return;
        }

        Font font = client.font;
        Hint hint = this.hint(client);
        List<Row> rows = hint.rows();
        int lineHeight = 18;
        int width = Math.max(
                font.width(hint.title()),
                rows.stream()
                .mapToInt(row -> this.rowWidth(font, row))
                .max()
                .orElse(1)
        ) + 14;
        int height = 22 + (rows.size() * lineHeight);
        int x = Math.max(8, (graphics.guiWidth() - width) / 2);
        int y = Math.max(8, Math.min(graphics.guiHeight() - height - 8, (graphics.guiHeight() / 2) + 16));

        RoundedHudRenderer.card(graphics, x, y, width, height);
        graphics.drawString(font, Component.literal(hint.title()), x + 7, y + 6, RoundedHudRenderer.TEXT, false);
        for (int index = 0; index < rows.size(); index++) {
            this.drawRow(graphics, font, rows.get(index), x + 7, y + 19 + (index * lineHeight));
        }
    }

    private Hint hint(Minecraft client) {
        if (LumiRegionSelectionController.controlDown(client)) {
            return new Hint(
                    this.activeZoneName.isBlank() ? "Zone edit" : "Zone edit · " + this.activeZoneName,
                    List.of(
                            new Row(List.of(new Shortcut("hint_zone_add", List.of("Ctrl", "LMB"), "Add selection/box"))),
                            new Row(List.of(new Shortcut("hint_zone_erase", List.of("Ctrl", "RMB"), "Erase selection/box")))
                    )
            );
        }
        if (this.actionKeyDown()) {
            return new Hint(
                    "Selection adjust",
                    List.of(
                            new Row(List.of(new Shortcut("hint_resize", List.of("ACTION", "Wheel"), "Resize looked side"))),
                            new Row(List.of(
                                    new Shortcut("hint_mode", List.of("ACTION", "LMB"), "Switch mode"),
                                    new Shortcut("hint_clear", List.of("ACTION", "RMB"), "Clear")
                            ))
                    )
            );
        }
        LumiRegionSelectionMode mode = LumiRegionSelectionController.getInstance()
                .currentMode(client)
                .orElse(LumiRegionSelectionMode.CORNERS);
        String title = mode == LumiRegionSelectionMode.EXTEND ? "Wooden Sword · Extend" : "Wooden Sword · Corners";
        String primary = mode == LumiRegionSelectionMode.EXTEND ? "Extend to block" : "First corner";
        String secondary = mode == LumiRegionSelectionMode.EXTEND ? "Move to block" : "Second corner";
        return new Hint(
                title,
                List.of(
                        new Row(List.of(
                                new Shortcut("hint_lmb", List.of("LMB"), primary),
                                new Shortcut("hint_rmb", List.of("RMB"), secondary)
                        )),
                        new Row(List.of(
                                new Shortcut("hint_alt", List.of("ACTION"), "Hold: resize / clear / switch"),
                                new Shortcut("hint_ctrl", List.of("Ctrl"), "Hold: edit zone")
                        ))
                )
        );
    }

    private int rowWidth(Font font, Row row) {
        int width = 0;
        for (int index = 0; index < row.shortcuts().size(); index++) {
            if (index > 0) {
                width += 10;
            }
            Shortcut shortcut = row.shortcuts().get(index);
            width += 12 + this.keyGroupWidth(shortcut.keys()) + 3 + font.width(shortcut.text());
        }
        return width;
    }

    private int keyGroupWidth(List<String> keys) {
        int width = 0;
        for (int index = 0; index < keys.size(); index++) {
            if (index > 0) {
                width += 3;
            }
            String key = keys.get(index);
            width += "ACTION".equals(key)
                    ? RoundedHudRenderer.keyWidth(this.cachedActionKey, "Alt", true)
                    : RoundedHudRenderer.textChipWidth(key, true);
        }
        return width;
    }

    private void drawRow(GuiGraphics graphics, Font font, Row row, int x, int y) {
        int cursor = x;
        for (int shortcutIndex = 0; shortcutIndex < row.shortcuts().size(); shortcutIndex++) {
            if (shortcutIndex > 0) {
                cursor += 10;
            }
            Shortcut shortcut = row.shortcuts().get(shortcutIndex);
            this.drawIconSlot(graphics, cursor, y + 3, shortcut.iconName());
            cursor += 12;
            for (int keyIndex = 0; keyIndex < shortcut.keys().size(); keyIndex++) {
                if (keyIndex > 0) {
                    cursor += 2;
                }
                String key = shortcut.keys().get(keyIndex);
                cursor += "ACTION".equals(key)
                        ? RoundedHudRenderer.key(graphics, this.cachedActionKey, cursor, y, "Alt", true)
                        : RoundedHudRenderer.textChip(graphics, key, cursor, y, true);
            }
            graphics.drawString(font, Component.literal(shortcut.text()), cursor + 3, y + 3, RoundedHudRenderer.MUTED, false);
            cursor += 3 + font.width(shortcut.text());
        }
    }

    private void drawIconSlot(GuiGraphics graphics, int x, int y, String iconName) {
        int color = switch (iconName == null ? "" : iconName) {
            case "hint_zone_erase", "hint_clear" -> 0xFFE76868;
            case "hint_zone_add" -> 0xFF4ADE80;
            case "hint_resize" -> 0xFF60A5FA;
            default -> 0xFF98A6B3;
        };
        RoundedHudRenderer.roundedRect(graphics, x, y, 9, 9, 2, 0x45101820, color);
        graphics.fill(x + 3, y + 3, x + 6, y + 6, color);
    }

    private KeyMapping actionKey() {
        return LumiClientKeyBindings.key(LumiClientKeyBindings.Role.ACTION);
    }

    private boolean actionKeyDown() {
        return this.cachedActionKey != null && this.cachedActionKey.isDown();
    }

    private String activeZoneName(Minecraft client) {
        try {
            ServerLevel level = ClientProjectAccess.requireSingleplayerServer(client).getLevel(client.level.dimension());
            if (level == null) {
                return "";
            }
            return this.projectService.findWorldProject(level)
                    .flatMap(project -> {
                        try {
                            return this.workZoneService.activeZone(
                                    this.projectService.resolveLayout(ClientProjectAccess.requireSingleplayerServer(client), project.name()),
                                    client.getUser() == null ? "player" : client.getUser().getName()
                            );
                        } catch (Exception exception) {
                            return java.util.Optional.<WorkZone>empty();
                        }
                    })
                    .map(WorkZone::name)
                    .orElse("");
        } catch (Exception exception) {
            return "";
        }
    }

    private record Hint(String title, List<Row> rows) {
    }

    private record Row(List<Shortcut> shortcuts) {
    }

    private record Shortcut(String iconName, List<String> keys, String text) {
    }
}
