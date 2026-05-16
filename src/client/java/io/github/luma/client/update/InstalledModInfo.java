package io.github.luma.client.update;

public record InstalledModInfo(String modVersion, String minecraftVersion, String loader) {

    public InstalledModInfo {
        modVersion = normalize(modVersion);
        minecraftVersion = normalize(minecraftVersion);
        loader = normalize(loader);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
