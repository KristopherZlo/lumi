package io.github.lumi.client.preview;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.storage.repository.DimensionRepositoryLayout;
import io.github.lumi.storage.repository.VersionPreviewRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;

/** Owns bounded client texture state and asynchronous local preview publication. */
public final class ClientVersionPreviewStore {
    private static final int MAX_TEXTURES = 32;
    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(
                runnable, "Lumi-Preview-" + THREAD_NUMBER.incrementAndGet());
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
        return thread;
    });
    private final Map<String, CachedPreview> textures =
            new LinkedHashMap<>(16, 0.75F, true);
    private final Set<String> missing = new HashSet<>();

    public void save(String dimensionId, CommitId commit, NativeImage image) {
        Optional<VersionPreviewRepository> repository = repository(dimensionId);
        if (repository.isEmpty()) {
            image.close();
            return;
        }
        String key = key(dimensionId, commit);
        CompletableFuture.runAsync(() -> {
            Path temporary = null;
            try (image) {
                temporary = Files.createTempFile("lumi-preview-", ".png");
                image.writeToFile(temporary);
                repository.orElseThrow().save(commit, Files.readAllBytes(temporary));
                synchronized (this) {
                    missing.remove(key);
                }
            } catch (Exception failed) {
                LumiMod.LOGGER.warn("Failed to store preview for {}", commit.hex(), failed);
            } finally {
                try {
                    if (temporary != null) Files.deleteIfExists(temporary);
                } catch (IOException failed) {
                    LumiMod.LOGGER.warn("Failed to remove temporary Lumi preview", failed);
                }
            }
        }, writer);
    }

    public synchronized Optional<PreviewTexture> texture(
            String dimensionId, CommitId commit) {
        String key = key(dimensionId, commit);
        CachedPreview cached = textures.get(key);
        if (cached != null) {
            return Optional.of(cached.view());
        }
        if (missing.contains(key)) {
            return Optional.empty();
        }
        try {
            Optional<VersionPreviewRepository> repository = repository(dimensionId);
            Optional<byte[]> png = repository.isEmpty()
                    ? Optional.empty() : repository.orElseThrow().load(commit);
            if (png.isEmpty()) {
                missing.add(key);
                return Optional.empty();
            }
            NativeImage image = NativeImage.read(png.orElseThrow());
            Identifier id = Identifier.fromNamespaceAndPath(
                    LumiMod.MOD_ID, "preview/" + sanitize(dimensionId) + "/" + commit.hex());
            DynamicTexture texture = new DynamicTexture(
                    () -> "lumi-preview-" + commit.hex(), image);
            texture.upload();
            Minecraft.getInstance().getTextureManager().register(id, texture);
            CachedPreview loaded = new CachedPreview(
                    id, image.getWidth(), image.getHeight());
            textures.put(key, loaded);
            trim();
            return Optional.of(loaded.view());
        } catch (Exception failed) {
            missing.add(key);
            LumiMod.LOGGER.warn("Failed to load preview for {}", commit.hex(), failed);
            return Optional.empty();
        }
    }

    public synchronized void releaseAll() {
        textures.values().forEach(this::release);
        textures.clear();
        missing.clear();
    }

    private void trim() {
        Iterator<CachedPreview> iterator = textures.values().iterator();
        while (textures.size() > MAX_TEXTURES && iterator.hasNext()) {
            CachedPreview preview = iterator.next();
            iterator.remove();
            release(preview);
        }
    }

    private void release(CachedPreview preview) {
        Minecraft.getInstance().getTextureManager().release(preview.id());
    }

    private static Optional<VersionPreviewRepository> repository(String dimensionId) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            return Optional.empty();
        }
        var layout = new DimensionRepositoryLayout(
                server.getWorldPath(LevelResource.ROOT));
        return Optional.of(new VersionPreviewRepository(layout.resolve(dimensionId)));
    }

    private static String key(String dimensionId, CommitId commit) {
        return dimensionId + ':' + commit.hex();
    }

    private static String sanitize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
    }

    public record PreviewTexture(Identifier id, int width, int height) { }

    private record CachedPreview(Identifier id, int width, int height) {
        private PreviewTexture view() {
            return new PreviewTexture(id, width, height);
        }
    }
}
