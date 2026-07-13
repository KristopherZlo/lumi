package io.github.luma.client.specialthanks;

import com.mojang.authlib.GameProfile;
import io.github.luma.LumaMod;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    private final SkinTextureDownloader skinTextureDownloader;
    private final Map<SpecialThanksEntry, CompletableFuture<PlayerSkin>> skins = new ConcurrentHashMap<>();

    public MinecraftSpecialThanksSkinResolver(Minecraft client) {
        this.client = client;
        this.skinTextureDownloader = new SkinTextureDownloader(
                client.getProxy(),
                client.getTextureManager(),
                client
        );
    }

    public PlayerSkin skinFor(SpecialThanksEntry entry) {
        if (entry == null) {
            return DefaultPlayerSkin.getDefaultSkin();
        }
        return this.skins.computeIfAbsent(entry, this::load).getNow(DefaultPlayerSkin.getDefaultSkin());
    }

    private CompletableFuture<PlayerSkin> load(SpecialThanksEntry entry) {
        CompletableFuture<PlayerSkin> baseSkin = entry.profileSkinName().isBlank()
                ? CompletableFuture.completedFuture(DefaultPlayerSkin.getDefaultSkin())
                : CompletableFuture.supplyAsync(
                        () -> this.loadProfileSkin(entry.profileSkinName()),
                        Util.backgroundExecutor()
                ).exceptionally(throwable -> this.fallbackToBase(
                        entry,
                        DefaultPlayerSkin.getDefaultSkin(),
                        throwable
                ));
        if (entry.skinUrl().isBlank()) {
            return baseSkin;
        }
        return baseSkin.thenCompose(base -> this.loadBodyTexture(entry)
                .thenApply(texture -> new PlayerSkin(texture, base.cape(), base.elytra(), base.model(), false))
                .exceptionally(throwable -> this.fallbackToBase(entry, base, throwable)));
    }

    private CompletableFuture<ClientAsset.Texture> loadBodyTexture(SpecialThanksEntry entry) {
        return this.downloadCustomTexture(entry.skinUrl()).handle((texture, throwable) -> {
            if (throwable == null) {
                return texture;
            }
            ClientAsset.Texture fallback = this.bundledTexture(entry.skinAsset());
            if (fallback != null) {
                LumaMod.LOGGER.warn(
                        "Failed to download Special Thanks skin for {}; using bundled texture",
                        entry.displayName(),
                        throwable
                );
                return fallback;
            }
            throw new CompletionException(throwable);
        });
    }

    private CompletableFuture<ClientAsset.Texture> downloadCustomTexture(String skinUrl) {
        URI uri;
        try {
            uri = URI.create(skinUrl);
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Special Thanks skins require HTTPS"));
        }
        String cacheKey = UUID.nameUUIDFromBytes(skinUrl.getBytes(StandardCharsets.UTF_8)).toString();
        Identifier textureId = Identifier.fromNamespaceAndPath("lumi", "special_thanks/" + cacheKey);
        Path cachePath = this.client.gameDirectory.toPath()
                .resolve("lumi-special-thanks")
                .resolve(cacheKey + ".png");
        return this.skinTextureDownloader.downloadAndRegisterSkin(textureId, cachePath, skinUrl, true);
    }

    private ClientAsset.Texture bundledTexture(String asset) {
        Identifier texture = Identifier.tryParse(asset);
        if (texture == null || this.client.getResourceManager().getResource(texture).isEmpty()) {
            return null;
        }
        return new ClientAsset.ResourceTexture(texture, texture);
    }

    private PlayerSkin fallbackToBase(SpecialThanksEntry entry, PlayerSkin base, Throwable throwable) {
        LumaMod.LOGGER.warn("Failed to load Special Thanks skin for {}", entry.displayName(), throwable);
        return base;
    }

    private PlayerSkin loadProfileSkin(String skinName) {
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
