package io.github.luma.client.specialthanks;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;

public final class SpecialThanksClientCache {

    private static final SpecialThanksClientCache INSTANCE = new SpecialThanksClientCache();

    private final SpecialThanksCatalogSource catalogSource = new SpecialThanksCatalogSource();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile List<SpecialThanksEntry> entries = this.catalogSource.loadBundled();
    private MinecraftSpecialThanksSkinResolver skinResolver;
    private CompletableFuture<List<SpecialThanksEntry>> remoteCatalog;

    private SpecialThanksClientCache() {
    }

    public static SpecialThanksClientCache getInstance() {
        return INSTANCE;
    }

    public void preload(Minecraft client) {
        if (client == null) {
            return;
        }
        this.ensureSkinResolver(client);
        this.preloadSkins();
        this.loadRemoteCatalog(client);
    }

    public List<SpecialThanksEntry> entries() {
        return this.entries;
    }

    public Identifier textureFor(Minecraft client, String skinName) {
        return this.skinFor(client, skinName).body().texturePath();
    }

    public PlayerSkin skinFor(Minecraft client, String skinName) {
        if (client == null) {
            return DefaultPlayerSkin.getDefaultSkin();
        }
        this.ensureSkinResolver(client);
        MinecraftSpecialThanksSkinResolver resolver = this.skinResolver;
        return resolver == null ? DefaultPlayerSkin.getDefaultSkin() : resolver.skinFor(skinName);
    }

    public void addListener(Runnable listener) {
        if (listener != null) {
            this.listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Runnable listener) {
        if (listener != null) {
            this.listeners.remove(listener);
        }
    }

    private synchronized void ensureSkinResolver(Minecraft client) {
        if (this.skinResolver == null) {
            this.skinResolver = new MinecraftSpecialThanksSkinResolver(client, this::notifyListeners);
        }
    }

    private synchronized void loadRemoteCatalog(Minecraft client) {
        if (this.remoteCatalog != null) {
            return;
        }
        this.remoteCatalog = CompletableFuture.supplyAsync(this.catalogSource::loadRemoteOrBundled, Util.backgroundExecutor());
        this.remoteCatalog.thenAccept(entries -> client.execute(() -> {
            if (!entries.equals(this.entries)) {
                this.entries = List.copyOf(entries);
                this.preloadSkins();
                this.notifyListeners();
            }
        }));
    }

    private void preloadSkins() {
        MinecraftSpecialThanksSkinResolver resolver = this.skinResolver;
        if (resolver == null) {
            return;
        }
        for (SpecialThanksEntry entry : this.entries) {
            resolver.skinFor(entry.skinName());
        }
    }

    private void notifyListeners() {
        for (Runnable listener : this.listeners) {
            listener.run();
        }
    }
}
