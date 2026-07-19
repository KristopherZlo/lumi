package io.github.lumi.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.LumiMod;
import io.github.lumi.client.state.ClientSelection;
import io.github.lumi.client.state.SelectionMode;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Crosshair teaching HUD using live bindings and legacy mouse glyphs. */
public final class LumiSelectionHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(
            LumiMod.MOD_ID, "selection_tool_hint");
    private static final int ROW_HEIGHT = 14;
    private static final int MOUSE_ICON_SIZE = 12;
    private final ClientSelection selection;

    public LumiSelectionHud(ClientSelection selection) {
        this.selection = Objects.requireNonNull(selection, "selection");
    }

    public void register() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.OVERLAY_MESSAGE, ID, this::render);
    }

    private void render(
            GuiGraphics graphics, net.minecraft.client.DeltaTracker ignored) {
        Minecraft client = Minecraft.getInstance();
        if (!LumiSelectionTool.held(client) || client.screen != null
                || client.options.hideGui) {
            return;
        }
        Hint hint = hint(client);
        Font font = client.font;
        int height = 11 + hint.rows().size() * ROW_HEIGHT;
        int y = Math.min(
                graphics.guiHeight() - height - 8,
                graphics.guiHeight() / 2 + 16);
        graphics.drawCenteredString(font, hint.title(),
                graphics.guiWidth() / 2, y, 0xaaf3f7fa);
        for (int index = 0; index < hint.rows().size(); index++) {
            Row row = hint.rows().get(index);
            int rowWidth = rowWidth(font, row);
            drawRow(graphics, font, row,
                    (graphics.guiWidth() - rowWidth) / 2,
                    y + 12 + index * ROW_HEIGHT);
        }
    }

    private Hint hint(Minecraft client) {
        String action = LumiHotkeys.bindingLabel(
                client.options.keyMappings, "key.lumi.action_modifier");
        String attack = client.options.keyAttack
                .getTranslatedKeyMessage().getString();
        String use = client.options.keyUse
                .getTranslatedKeyMessage().getString();
        if (controlDown(client)) {
            return new Hint(
                    Component.translatable("luma.selection.hud_zone_edit"),
                    List.of(
                            row(List.of("LMB", attack),
                                    "luma.selection.hud_zone_add"),
                            row(List.of("RMB", use),
                                    "luma.selection.hud_zone_erase")));
        }
        if (LumiHotkeys.actionModifierDown(client.options.keyMappings)) {
            String undo = LumiHotkeys.bindingLabel(
                    client.options.keyMappings, "key.lumi.undo");
            String redo = LumiHotkeys.bindingLabel(
                    client.options.keyMappings, "key.lumi.redo");
            return new Hint(
                    Component.translatable("luma.selection.hud_adjust"),
                    List.of(
                            row(List.of(action, "MMB"),
                                    "luma.selection.hud_resize_side"),
                            row(List.of(action, "LMB", attack),
                                    "luma.selection.hud_switch_mode"),
                            row(List.of(action, "RMB", use),
                                    "luma.selection.hud_clear_selection"),
                            row(List.of(action, undo),
                                    "luma.selection.hud_undo_selection"),
                            row(List.of(action, redo),
                                    "luma.selection.hud_redo_selection")));
        }
        boolean extend = selection.mode() == SelectionMode.EXTEND;
        return new Hint(
                Component.translatable(extend
                        ? "luma.selection.hud_mode_extend_title"
                        : "luma.selection.hud_mode_corners_title"),
                List.of(
                        row(List.of("LMB", attack), extend
                                ? "luma.selection.hud_extend_to_block"
                                : "luma.selection.hud_first_corner"),
                        row(List.of("RMB", use), extend
                                ? "luma.selection.hud_move_to_block"
                                : "luma.selection.hud_second_corner"),
                        row(List.of(action),
                                "luma.selection.hud_hold_adjust"),
                        row(List.of("Ctrl"),
                                "luma.selection.hud_hold_zone")));
    }

    private static Row row(List<String> keys, String translation) {
        return new Row(keys, Component.translatable(translation));
    }

    private static int rowWidth(Font font, Row row) {
        int width = font.width(row.text()) + 4;
        for (String key : row.keys()) {
            width += mouseKey(key) ? MOUSE_ICON_SIZE : font.width("[" + key + "]");
            width += 3;
        }
        return width;
    }

    private static void drawRow(
            GuiGraphics graphics, Font font, Row row, int x, int y) {
        int cursor = x;
        for (String key : row.keys()) {
            if (mouseKey(key)) {
                graphics.blit(
                        RenderPipelines.GUI_TEXTURED, mouseIcon(key),
                        cursor, y, 0, 0,
                        MOUSE_ICON_SIZE, MOUSE_ICON_SIZE,
                        24, 24, 24, 24);
                cursor += MOUSE_ICON_SIZE + 3;
            } else {
                String label = "[" + key + "]";
                graphics.drawString(
                        font, label, cursor, y + 2, 0xaadbe6f2, false);
                cursor += font.width(label) + 3;
            }
        }
        graphics.drawString(
                font, row.text(), cursor + 1, y + 2, 0x99f3f7fa, false);
    }

    private static boolean mouseKey(String key) {
        return "LMB".equals(key) || "MMB".equals(key) || "RMB".equals(key);
    }

    private static Identifier mouseIcon(String key) {
        return Identifier.fromNamespaceAndPath(
                LumiMod.MOD_ID, "textures/gui/hints/hint_"
                        + key.toLowerCase(Locale.ROOT) + ".png");
    }

    private static boolean controlDown(Minecraft client) {
        return InputConstants.isKeyDown(
                client.getWindow(), InputConstants.KEY_LCONTROL)
                || InputConstants.isKeyDown(
                        client.getWindow(), InputConstants.KEY_RCONTROL);
    }

    private record Hint(Component title, List<Row> rows) {
    }

    private record Row(List<String> keys, Component text) {
    }
}
