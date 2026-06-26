package io.github.luma.client.specialthanks;

import com.mojang.authlib.GameProfile;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;

public final class MinecraftSpecialThanksSkinResolver {

    private final Minecraft client;
    private final Runnable onSkinLoaded;
    private final Map<String, PlayerSkin> loaded = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<PlayerSkin>> pending = new ConcurrentHashMap<>();

    public MinecraftSpecialThanksSkinResolver(Minecraft client, Runnable onSkinLoaded) {
        this.client = client;
        this.onSkinLoaded = onSkinLoaded == null ? () -> {
        } : onSkinLoaded;
    }

    public Identifier textureFor(String skinName) {
        return this.skinFor(skinName).body().texturePath();
    }

    public PlayerSkin skinFor(String skinName) {
        String key = skinName == null ? "" : skinName.trim();
        if (key.isBlank()) {
            return DefaultPlayerSkin.getDefaultSkin();
        }
        PlayerSkin skin = this.loaded.get(key);
        if (skin != null) {
            return skin;
        }
        this.pending.computeIfAbsent(key, this::load);
        return DefaultPlayerSkin.getDefaultSkin();
    }

    private CompletableFuture<PlayerSkin> load(String skinName) {
        CompletableFuture<PlayerSkin> future = CompletableFuture.supplyAsync(() -> this.loadSkin(skinName), Util.backgroundExecutor());
        future.whenComplete((skin, throwable) -> this.client.execute(() -> {
            this.pending.remove(skinName);
            if (throwable == null && skin != null) {
                this.loaded.put(skinName, skin);
                this.onSkinLoaded.run();
            }
        }));
        return future;
    }

    private PlayerSkin loadSkin(String skinName) {
        Optional<GameProfile> profile = this.client.services().profileResolver().fetchByName(skinName);
        if (profile.isEmpty()) {
            return DefaultPlayerSkin.getDefaultSkin();
        }
        return this.client.getSkinManager()
                .get(profile.get())
                .join()
                .orElseGet(DefaultPlayerSkin::getDefaultSkin);
    }
}
