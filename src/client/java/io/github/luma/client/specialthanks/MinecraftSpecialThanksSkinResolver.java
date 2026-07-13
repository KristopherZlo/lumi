package io.github.luma.client.specialthanks;

import com.mojang.authlib.GameProfile;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;

public final class MinecraftSpecialThanksSkinResolver {

    private final Minecraft client;
    private final Runnable onSkinLoaded;
    private final SkinTextureDownloader skinTextureDownloader;
    private final Map<String, PlayerSkin> loaded = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<PlayerSkin>> pending = new ConcurrentHashMap<>();

    public MinecraftSpecialThanksSkinResolver(Minecraft client, Runnable onSkinLoaded) {
        this.client = client;
        this.onSkinLoaded = onSkinLoaded == null ? () -> {
        } : onSkinLoaded;
        this.skinTextureDownloader = new SkinTextureDownloader(
                client.getProxy(),
                client.getTextureManager(),
                client
        );
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

    public PlayerSkin skinFor(SpecialThanksEntry entry) {
        if (entry == null) {
            return DefaultPlayerSkin.getDefaultSkin();
        }
        if (entry.skinUrl().isBlank()) {
            return this.skinFor(entry.skinName());
        }
        String key = "url:" + entry.skinUrl();
        PlayerSkin skin = this.loaded.get(key);
        if (skin != null) {
            return skin;
        }
        this.pending.computeIfAbsent(key, ignored -> this.loadCustomSkin(entry, key));
        return DefaultPlayerSkin.getDefaultSkin();
    }

    private CompletableFuture<PlayerSkin> load(String skinName) {
        CompletableFuture<PlayerSkin> future = CompletableFuture.supplyAsync(() -> this.loadSkin(skinName), Util.backgroundExecutor());
        future.whenComplete((skin, throwable) -> this.rememberLoaded(skinName, skin, throwable));
        return future;
    }

    private CompletableFuture<PlayerSkin> loadCustomSkin(SpecialThanksEntry entry, String key) {
        CompletableFuture<PlayerSkin> baseSkin = entry.profileSkinName().isBlank()
                ? CompletableFuture.completedFuture(DefaultPlayerSkin.getDefaultSkin())
                : CompletableFuture.supplyAsync(() -> this.loadSkin(entry.profileSkinName()), Util.backgroundExecutor());
        CompletableFuture<PlayerSkin> future = baseSkin.thenCompose(base -> this.downloadCustomTexture(entry.skinUrl())
                .thenApply(texture -> new PlayerSkin(texture, base.cape(), base.elytra(), base.model(), false)));
        future.whenComplete((skin, throwable) -> this.rememberLoaded(key, skin, throwable));
        return future;
    }

    private CompletableFuture<ClientAsset.Texture> downloadCustomTexture(String skinUrl) {
        String hash = Integer.toHexString(skinUrl.hashCode());
        Identifier textureId = Identifier.fromNamespaceAndPath("lumi", "special_thanks/" + hash);
        Path cachePath = this.client.gameDirectory.toPath().resolve("lumi-special-thanks").resolve(hash + ".png");
        return this.skinTextureDownloader.downloadAndRegisterSkin(textureId, cachePath, skinUrl, true);
    }

    private void rememberLoaded(String key, PlayerSkin skin, Throwable throwable) {
        this.client.execute(() -> {
            this.pending.remove(key);
            if (throwable == null && skin != null) {
                this.loaded.put(key, skin);
                this.onSkinLoaded.run();
            }
        });
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
