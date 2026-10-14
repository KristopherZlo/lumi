package io.github.lumi.buildconfig;

import groovy.json.JsonSlurper;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Checks the distributable, including libraries that Fabric will actually load. */
public final class MinecraftArtifactVerifier {
    public void verify(Path artifact, MinecraftBuildTarget target, String version) throws IOException {
        try (ZipFile zip = new ZipFile(artifact.toFile())) {
            Map<?, ?> metadata = json(zip, "fabric.mod.json");
            require(version.equals(metadata.get("version")), "Incorrect mod version");
            Map<?, ?> dependencies = (Map<?, ?>) metadata.get("depends");
            require(target.minecraftVersions().equals(dependencies.get("minecraft")), "Incorrect game releases");
            require((">=" + target.javaVersion()).equals(dependencies.get("java")), "Incorrect Java baseline");
            Map<?, ?> mixins = json(zip, "lumi.mixins.json");
            require(Boolean.TRUE.equals(mixins.get("required")), "Critical mixins must be required");
            require(("JAVA_" + target.javaVersion()).equals(mixins.get("compatibilityLevel")),
                    "Incorrect mixin Java baseline");
            verifyClass(zip.getInputStream(required(zip, "io/github/lumi/LumiMod.class")), target.javaVersion());
            boolean core = false;
            boolean compression = false;
            if (metadata.get("jars") instanceof Iterable<?> libraries) {
                for (Object library : libraries) {
                    String name = (String) ((Map<?, ?>) library).get("file");
                    try (ZipInputStream nested = new ZipInputStream(zip.getInputStream(required(zip, name)))) {
                        for (var entry = nested.getNextEntry(); entry != null; entry = nested.getNextEntry()) {
                            if (entry.getName().equals("io/github/lumi/domain/service/SaveService.class")) {
                                verifyClassHeader(nested, 21);
                                core = true;
                            }
                            if (entry.getName().equals("net/jpountz/lz4/LZ4Factory.class")) compression = true;
                        }
                    }
                }
            }
            require(core, "Distributable does not bundle the Java 21 core");
            require(compression, "Distributable does not bundle LZ4");
        } catch (RuntimeException malformed) {
            throw new IOException("Invalid Lumi artifact: " + artifact, malformed);
        }
    }

    private static Map<?, ?> json(ZipFile zip, String name) throws IOException {
        try (InputStream input = zip.getInputStream(required(zip, name))) {
            return (Map<?, ?>) new JsonSlurper().parse(input);
        }
    }

    private static java.util.zip.ZipEntry required(ZipFile zip, String name) throws IOException {
        var entry = zip.getEntry(name);
        require(entry != null, "Missing artifact entry: " + name);
        return entry;
    }

    private static void verifyClass(InputStream input, int javaVersion) throws IOException {
        try (input) { verifyClassHeader(input, javaVersion); }
    }

    private static void verifyClassHeader(InputStream input, int javaVersion) throws IOException {
        DataInputStream data = new DataInputStream(input);
        require(data.readInt() == 0xCAFEBABE, "Invalid class header");
        data.readUnsignedShort();
        require(data.readUnsignedShort() <= javaVersion + 44, "Bytecode exceeds Java baseline");
    }

    private static void require(boolean valid, String message) throws IOException {
        if (!valid) throw new IOException(message);
    }
}
