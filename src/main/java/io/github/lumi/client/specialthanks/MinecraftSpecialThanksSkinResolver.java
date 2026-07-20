package io.github.lumi.client.specialthanks;

import com.mojang.authlib.GameProfile;
import io.github.lumi.LumiMod;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
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
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;

/** Lazily resolves the two bundled credits without blocking the render thread. */
public final class MinecraftSpecialThanksSkinResolver {
    private final Minecraft client;
    private final SkinTextureDownloader downloader;
    private static final Supplier<PlayerSkin> DEFAULT_SKIN =
            DefaultPlayerSkin::getDefaultSkin;
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
        return skins.computeIfAbsent(entry, this::load)
                .getNow(DEFAULT_SKIN).get();
    }

    private CompletableFuture<Supplier<PlayerSkin>> load(
            SpecialThanksEntry entry) {
        CompletableFuture<Supplier<PlayerSkin>> base =
                entry.profileSkinName().isBlank()
                ? CompletableFuture.completedFuture(DEFAULT_SKIN)
                : CompletableFuture.supplyAsync(
                        () -> loadProfile(entry.profileSkinName()),
                        Util.backgroundExecutor())
                        .thenApplyAsync(
                                profile -> client.getSkinManager()
                                        .createLookup(profile, true),
                                client)
                        .exceptionally(failed -> fallback(entry, failed));
        ClientAsset.Texture bundled = bundledTexture(entry.skinAsset());
        if (bundled != null) {
            return base.thenApply(skin ->
                    () -> withBody(skin.get(), bundled));
        }
        if (entry.skinUrl().isBlank()) {
            return base;
        }
        return base.thenCompose(skin -> download(entry.skinUrl())
                .thenApply(texture -> (Supplier<PlayerSkin>)
                        () -> withBody(skin.get(), texture))
                .exceptionally(failed -> fallback(entry, skin, failed)));
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

    private GameProfile loadProfile(String name) {
        Optional<GameProfile> profile = client.services().profileResolver().fetchByName(name);
        GameProfile partial = profile.orElseThrow(() ->
                new IllegalStateException("Minecraft profile was not found"));
        var complete = client.services().sessionService().fetchProfile(
                partial.id(), true);
        GameProfile resolved = complete == null
                ? partial : complete.profile();
        return resolved;
    }

    private Supplier<PlayerSkin> fallback(
            SpecialThanksEntry entry, Throwable failed) {
        return fallback(entry, DEFAULT_SKIN, failed);
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
