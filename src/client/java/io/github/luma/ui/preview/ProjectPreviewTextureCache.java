package io.github.luma.ui.preview;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.luma.LumaMod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

public final class ProjectPreviewTextureCache {

    private static final Map<String, Identifier> LOADED_TEXTURES = new HashMap<>();

    private ProjectPreviewTextureCache() {
    }

    public static Identifier load(String projectName, String versionId, Path previewPath) throws IOException {
        String key = cacheKey(projectName, versionId);
        Identifier existing = LOADED_TEXTURES.get(key);
        if (existing != null) {
            return existing;
        }

        NativeImage image;
        try (var stream = Files.newInputStream(previewPath)) {
            image = NativeImage.read(stream);
        }

        Identifier textureId = Identifier.fromNamespaceAndPath(
                LumaMod.MOD_ID,
                "preview/" + sanitize(projectName) + "/" + sanitize(versionId)
        );
        DynamicTexture texture = new DynamicTexture(() -> "luma-preview-" + key, image);
        texture.upload();
        Minecraft.getInstance().getTextureManager().register(textureId, texture);
        LOADED_TEXTURES.put(key, textureId);
        return textureId;
    }

    public static void release(String projectName, String versionId) {
        String key = cacheKey(projectName, versionId);
        Identifier textureId = LOADED_TEXTURES.remove(key);
        if (textureId != null) {
            Minecraft.getInstance().getTextureManager().release(textureId);
        }
    }

    private static String cacheKey(String projectName, String versionId) {
        return sanitize(projectName) + ":" + sanitize(versionId);
    }

    private static String sanitize(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9/_-]+", "-");
    }
}
