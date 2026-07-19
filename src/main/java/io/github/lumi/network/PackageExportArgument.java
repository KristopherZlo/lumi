package io.github.lumi.network;

import io.github.lumi.domain.model.PackageName;
import java.util.Objects;
/** Backward-compatible package name plus optional-preview export choice. */
public record PackageExportArgument(PackageName name, boolean includePreview) {
    public PackageExportArgument {
        Objects.requireNonNull(name, "name");
    }
    public String encode() {
        return (includePreview ? "1," : "0,") + name.value();
    }
    public static PackageExportArgument parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length() >= 3 && encoded.charAt(1) == ',') {
            boolean include = switch (encoded.charAt(0)) {
                case '0' -> false;
                case '1' -> true;
                default -> throw new IllegalArgumentException("Invalid package preview flag");
            };
            return new PackageExportArgument(new PackageName(encoded.substring(2)), include);
        }
        return new PackageExportArgument(new PackageName(encoded), false);
    }
}
