package io.github.lumi.client.ui;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import io.github.lumi.client.state.ClientAccentRepository;
import java.io.IOException;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;

/** Colors and simple surfaces shared by the Lumi V2 presentation. */
public final class LumiTheme {
    static final int PAGE_HEADER_HEIGHT = 42;
    static final int TEXT = 0xfff4f1ea;
    static final int MUTED = 0xffa9a39a;
    private static int accent = ClientAccentRepository.DEFAULT;
    static final int DANGER = 0xffff8585;
    static final int BACKDROP = 0xd608090a;
    static final int WINDOW = 0xff141517;
    static final int WINDOW_BORDER = 0xff45413a;
    static final int SIDEBAR = 0xff111214;
    static final int TITLEBAR = 0xff1c1d20;
    static final int PANEL = 0xef1a1b1e;
    static final int PANEL_BORDER = 0xff343238;
    static final int INSET = 0xea101113;
    static final int INSET_BORDER = 0xff2b2a2f;
    static final int CHIP = 0xff242326;
    static final int CHIP_BORDER = 0xff3c3830;

    private LumiTheme() {
    }

    public static void registerCommands() {
        var preferences = new ClientAccentRepository(
                FabricLoader.getInstance().getConfigDir().resolve("lumi-accent"));
        accent = preferences.load();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            // Forward other /lumi subcommands without re-entering the client dispatcher.
            dispatcher.register(ClientCommandManager.literal("lumi").then(ClientCommandManager
                    .argument("serverCommand", StringArgumentType.greedyString()).executes(context -> {
                        context.getSource().getClient().getConnection().send(
                                new ServerboundChatCommandPacket(context.getInput()));
                        return 1;
                    })));
            dispatcher.register(ClientCommandManager.literal("lumi").then(
                        ClientCommandManager.literal("accent")
                                .executes(context -> {
                                    context.getSource().sendFeedback(Component.translatable(
                                            "lumi.accent.current", ClientAccentRepository.hex(accent)));
                                    return 1;
                                })
                                .then(ClientCommandManager.argument("color", StringArgumentType.greedyString())
                                        .suggests((context, builder) -> builder.suggest("default")
                                                .suggest("#D9B86C").buildFuture())
                                        .executes(context -> {
                                            try {
                                                int color = ClientAccentRepository.parse(
                                                        StringArgumentType.getString(context, "color"));
                                                preferences.save(color);
                                                accent = color;
                                                context.getSource().sendFeedback(Component.translatable(
                                                        "lumi.accent.changed", ClientAccentRepository.hex(color)));
                                                return 1;
                                            } catch (IllegalArgumentException invalid) {
                                                context.getSource().sendError(Component.translatable("lumi.accent.invalid"));
                                            } catch (IOException failed) {
                                                context.getSource().sendError(Component.translatable("lumi.accent.save_failed"));
                                            }
                                            return 0;
                                        }))));
            dispatcher.register(ClientCommandManager.literal("lumi").then(
                    ClientCommandManager.literal("motion").then(ClientCommandManager
                            .argument("enabled", BoolArgumentType.bool()).executes(context -> {
                                LumiMotion.setEnabled(BoolArgumentType.getBool(context, "enabled"));
                                return 1;
                            }))));
        });
    }

    static int accent() {
        return accent;
    }

    static int status() { return accentSurface(.08F); }

    static int statusBorder() { return accentSurface(.3F); }

    static int accentSurface(float strength) {
        int result = 0xff000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int base = WINDOW >>> shift & 255;
            result |= Math.round(base + ((accent >>> shift & 255) - base) * strength) << shift;
        }
        return result;
    }

    static void outlined(
            GuiGraphics graphics, int x, int y, int width, int height,
            int fill, int border) {
        graphics.fill(x, y, x + width, y + height, border);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
    }

    static int withOpacity(int color, float opacity) {
        return withAlpha(color, Math.round(opacity * 255.0F));
    }

    static int withAlpha(int color, int alpha) {
        return alpha << 24 | color & 0x00ffffff;
    }
}
