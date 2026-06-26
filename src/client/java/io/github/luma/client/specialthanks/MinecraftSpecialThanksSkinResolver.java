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

public final class MinecraftSpecialThanksSkinResolver {

    private final Minecraft client;
    private final Runnable onSkinLoaded;
    private final Map<String, Identifier> loaded = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Identifier>> pending = new ConcurrentHashMap<>();

    public MinecraftSpecialThanksSkinResolver(Minecraft client, Runnable onSkinLoaded) {
        this.client = client;
        this.onSkinLoaded = onSkinLoaded == null ? () -> {
        } : onSkinLoaded;
    }

    public Identifier textureFor(String skinName) {
        String key = skinName == null ? "" : skinName.trim();
        if (key.isBlank()) {
            return DefaultPlayerSkin.getDefaultTexture();
        }
        Identifier texture = this.loaded.get(key);
        if (texture != null) {
            return texture;
        }
        this.pending.computeIfAbsent(key, this::load);
        return DefaultPlayerSkin.getDefaultTexture();
    }

    private CompletableFuture<Identifier> load(String skinName) {
        CompletableFuture<Identifier> future = CompletableFuture.supplyAsync(() -> this.loadTexture(skinName), Util.backgroundExecutor());
        future.whenComplete((texture, throwable) -> this.client.execute(() -> {
            this.pending.remove(skinName);
            if (throwable == null && texture != null) {
                this.loaded.put(skinName, texture);
                this.onSkinLoaded.run();
            }
        }));
        return future;
    }

    private Identifier loadTexture(String skinName) {
        Optional<GameProfile> profile = this.client.services().profileResolver().fetchByName(skinName);
        if (profile.isEmpty()) {
            return DefaultPlayerSkin.getDefaultTexture();
        }
        return this.client.getSkinManager()
                .get(profile.get())
                .join()
                .map(skin -> skin.body().texturePath())
                .orElseGet(DefaultPlayerSkin::getDefaultTexture);
    }
}
