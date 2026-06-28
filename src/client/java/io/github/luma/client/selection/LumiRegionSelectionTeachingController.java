package io.github.luma.client.selection;

import io.github.luma.LumaMod;
import io.github.luma.client.input.LumiClientKeyBindings;
import io.github.luma.client.onboarding.ClientContextualHelpHint;
import io.github.luma.client.onboarding.ClientContextualHelpService;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.WorkZoneService;
import io.github.luma.ui.controller.ClientProjectAccess;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
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
    private static final int TITLE_COLOR = 0xAAF3F7FA;
    private static final int KEY_COLOR = 0xAADBE6F2;
    private static final int TEXT_COLOR = 0x99F3F7FA;
    private static final int ROW_HEIGHT = 15;
    private static final int MOUSE_ICON_SIZE = 12;
    private static final int MOUSE_TEXTURE_SIZE = 24;
    private static final int KEY_GAP = 3;
    private static final int TEXT_GAP = 4;
    private static final int SHORTCUT_GAP = 12;

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
        int height = 11 + (rows.size() * ROW_HEIGHT);
        int y = Math.max(8, Math.min(graphics.guiHeight() - height - 8, (graphics.guiHeight() / 2) + 16));

        int titleX = Math.max(8, (graphics.guiWidth() - font.width(hint.title())) / 2);
        graphics.drawString(font, Component.literal(hint.title()), titleX, y, TITLE_COLOR, false);
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            int rowX = Math.max(8, (graphics.guiWidth() - this.rowWidth(font, row)) / 2);
            this.drawRow(graphics, font, row, rowX, y + 12 + (index * ROW_HEIGHT));
        }
    }

    private Hint hint(Minecraft client) {
        if (LumiRegionSelectionController.controlDown(client)) {
            return new Hint(
                    this.activeZoneName.isBlank() ? "Zone edit" : "Zone edit - " + this.activeZoneName,
                    List.of(
                            new Row(List.of(new Shortcut(List.of("Ctrl", "LMB"), "Add selection/box"))),
                            new Row(List.of(new Shortcut(List.of("Ctrl", "RMB"), "Erase selection/box")))
                    )
            );
        }
        if (this.actionKeyDown()) {
            return new Hint(
                    "Selection adjust",
                    List.of(
                            new Row(List.of(new Shortcut(List.of("ACTION", "Wheel"), "Resize looked side"))),
                            new Row(List.of(
                                    new Shortcut(List.of("ACTION", "LMB"), "Switch mode"),
                                    new Shortcut(List.of("ACTION", "RMB"), "Clear")
                            ))
                    )
            );
        }
        LumiRegionSelectionMode mode = LumiRegionSelectionController.getInstance()
                .currentMode(client)
                .orElse(LumiRegionSelectionMode.CORNERS);
        String title = mode == LumiRegionSelectionMode.EXTEND ? "Wooden Sword - Extend" : "Wooden Sword - Corners";
        String primary = mode == LumiRegionSelectionMode.EXTEND ? "Extend to block" : "First corner";
        String secondary = mode == LumiRegionSelectionMode.EXTEND ? "Move to block" : "Second corner";
        return new Hint(
                title,
                List.of(
                        new Row(List.of(
                                new Shortcut(List.of("LMB"), primary),
                                new Shortcut(List.of("RMB"), secondary)
                        )),
                        new Row(List.of(
                                new Shortcut(List.of("ACTION"), "Hold: resize / clear / switch"),
                                new Shortcut(List.of("Ctrl"), "Hold: edit zone")
                        ))
                )
        );
    }

    private int rowWidth(Font font, Row row) {
        int width = 0;
        for (int index = 0; index < row.shortcuts().size(); index++) {
            if (index > 0) {
                width += SHORTCUT_GAP;
            }
            Shortcut shortcut = row.shortcuts().get(index);
            width += this.keyGroupWidth(font, shortcut.keys()) + TEXT_GAP + font.width(shortcut.text());
        }
        return width;
    }

    private int keyGroupWidth(Font font, List<String> keys) {
        int width = 0;
        for (int index = 0; index < keys.size(); index++) {
            if (index > 0) {
                width += KEY_GAP;
            }
            String key = keys.get(index);
            width += usesMouseIcon(key) ? MOUSE_ICON_SIZE : font.width(this.displayKeyLabel(key));
        }
        return width;
    }

    private void drawRow(GuiGraphics graphics, Font font, Row row, int x, int y) {
        int cursor = x;
        for (int shortcutIndex = 0; shortcutIndex < row.shortcuts().size(); shortcutIndex++) {
            if (shortcutIndex > 0) {
                cursor += SHORTCUT_GAP;
            }
            Shortcut shortcut = row.shortcuts().get(shortcutIndex);
            for (int keyIndex = 0; keyIndex < shortcut.keys().size(); keyIndex++) {
                if (keyIndex > 0) {
                    cursor += KEY_GAP;
                }
                cursor += this.drawKey(graphics, font, shortcut.keys().get(keyIndex), cursor, y);
            }
            graphics.drawString(font, Component.literal(shortcut.text()), cursor + TEXT_GAP, y + 2, TEXT_COLOR, false);
            cursor += TEXT_GAP + font.width(shortcut.text());
        }
    }

    private int drawKey(GuiGraphics graphics, Font font, String key, int x, int y) {
        if (usesMouseIcon(key)) {
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    mouseIcon(key),
                    x,
                    y,
                    0,
                    0,
                    MOUSE_ICON_SIZE,
                    MOUSE_ICON_SIZE,
                    MOUSE_TEXTURE_SIZE,
                    MOUSE_TEXTURE_SIZE,
                    MOUSE_TEXTURE_SIZE,
                    MOUSE_TEXTURE_SIZE
            );
            return MOUSE_ICON_SIZE;
        }
        String label = this.displayKeyLabel(key);
        graphics.drawString(font, Component.literal(label), x, y + 2, KEY_COLOR, false);
        return font.width(label);
    }

    static boolean usesMouseIcon(String key) {
        return switch (key == null ? "" : key.toUpperCase(Locale.ROOT)) {
            case "LMB", "MMB", "RMB" -> true;
            default -> false;
        };
    }

    static String keyLabel(String key) {
        return "[" + ("ACTION".equals(key) ? "Alt" : key) + "]";
    }

    private String displayKeyLabel(String key) {
        if ("ACTION".equals(key) && this.cachedActionKey != null) {
            return "[" + this.cachedActionKey.getTranslatedKeyMessage().getString() + "]";
        }
        return keyLabel(key);
    }

    private static Identifier mouseIcon(String key) {
        return Identifier.fromNamespaceAndPath(
                LumaMod.MOD_ID,
                "textures/gui/hints/hint_" + key.toLowerCase(Locale.ROOT) + ".png"
        );
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

    private record Shortcut(List<String> keys, String text) {
    }
}
