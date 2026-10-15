package io.github.lumi.minecraft.runtime;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/** Keeps transient builder feedback in the action bar on both native APIs. */
public final class MinecraftPlayerMessages {
    private MinecraftPlayerMessages() { }

    public static void chat(Player player, Component message) {
        //? if >=26.1 {
        /*player.sendSystemMessage(message);
        *///?} else {
        player.displayClientMessage(message, false);
        //?}
    }

    public static void overlay(Player player, Component message) {
        //? if >=26.1 {
        /*player.sendOverlayMessage(message);
        *///?} else {
        player.displayClientMessage(message, true);
        //?}
    }
}
