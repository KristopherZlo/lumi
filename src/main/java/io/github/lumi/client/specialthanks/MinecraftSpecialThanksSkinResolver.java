package io.github.lumi.client.specialthanks;

import io.github.lumi.LumiMod;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;

/** Lazily resolves the two bundled credits without blocking the render thread. */
public final class MinecraftSpecialThanksSkinResolver {
    private static final Supplier<PlayerSkin> DEFAULT_SKIN =
            DefaultPlayerSkin::getDefaultSkin;
    private final Minecraft client;
    private final SkinTextureDownloader downloader;
    private final Map<SpecialThanksEntry, CompletableFuture<Supplier<PlayerSkin>>> skins =
            new ConcurrentHashMap<>();
    private final Set<SpecialThanksEntry> loggedFailures =
            ConcurrentHashMap.newKeySet();

    public MinecraftSpecialThanksSkinResolver(Minecraft client) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        downloader = new SkinTextureDownloader(
                client.getProxy(), client.getTextureManager(), client);
    }

    public PlayerSkin skinFor(SpecialThanksEntry entry) {
        if (entry == null) {
            return DefaultPlayerSkin.getDefaultSkin();
        }
        Supplier<PlayerSkin> skin = skins.computeIfAbsent(entry, this::load)
                .getNow(DEFAULT_SKIN);
        try {
            return skin.get();
        } catch (RuntimeException failed) {
            return fallback(entry, failed).get();
        }
    }

    private CompletableFuture<Supplier<PlayerSkin>> load(SpecialThanksEntry entry) {
        Supplier<PlayerSkin> base = entry.profileSkinName().isBlank()
                ? DEFAULT_SKIN : loadProfileSkin(entry.profileSkinName());
        ClientAsset.Texture bundled = bundledTexture(entry.skinAsset());
        if (bundled != null) {
            return CompletableFuture.completedFuture(
                    () -> withBody(base.get(), bundled));
        }
        if (entry.skinUrl().isBlank()) {
            return CompletableFuture.completedFuture(base);
        }
        return download(entry.skinUrl())
                .thenApply(texture -> (Supplier<PlayerSkin>)
                        () -> withBody(base.get(), texture))
                .exceptionally(failed -> fallback(entry, base, failed));
    }

    private static PlayerSkin withBody(PlayerSkin base, ClientAsset.Texture body) {
        return new PlayerSkin(
                body, base.cape(), base.elytra(), base.model(), false);
    }

    private ClientAsset.Texture bundledTexture(String asset) {
        Identifier id = Identifier.tryParse(asset);
        return id == null || client.getResourceManager().getResource(id).isEmpty()
                ? null : new ClientAsset.ResourceTexture(id, id);
    }

    private CompletableFuture<ClientAsset.Texture> download(String value) {
        URI uri;
        try {
            uri = requireHttps(value);
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
        String key = UUID.nameUUIDFromBytes(
                uri.toASCIIString().getBytes(StandardCharsets.UTF_8)).toString();
        Identifier texture = Identifier.fromNamespaceAndPath(
                LumiMod.MOD_ID, "special_thanks/" + key);
        Path cache = client.gameDirectory.toPath()
                .resolve("lumi-special-thanks").resolve(key + ".png");
        return downloader.downloadAndRegisterSkin(
                texture, cache, uri.toASCIIString(), true);
    }

    static URI requireHttps(String value) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException(
                    "Special Thanks skins require an HTTPS host");
        }
        return uri;
    }

    private Supplier<PlayerSkin> loadProfileSkin(String name) {
        var lookup = client.playerSkinRenderCache().createLookup(
                ResolvableProfile.createUnresolved(name));
        return () -> lookup.get().playerSkin();
    }

    private Supplier<PlayerSkin> fallback(
            SpecialThanksEntry entry, Throwable failed) {
        return fallback(entry, DefaultPlayerSkin::getDefaultSkin, failed);
    }

    private Supplier<PlayerSkin> fallback(
            SpecialThanksEntry entry,
            Supplier<PlayerSkin> base,
            Throwable failed) {
        if (loggedFailures.add(entry)) {
            LumiMod.LOGGER.warn(
                    "Failed to load Special Thanks skin for {}",
                    entry.displayName(), failed);
        }
        return base;
    }
}
