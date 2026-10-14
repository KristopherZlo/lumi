package io.github.lumi.buildconfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/** Test-only repair: the published annotation uses a field name instead of Rotation.name(). */
public final class LegacyGameTestAnnotationRepair {
    private static final String ANNOTATION = "net/fabricmc/fabric/api/gametest/v1/GameTest.class";

    public void repair(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        boolean repaired = false;
        try (var sourceJar = new JarFile(source.toFile(), true);
                var output = new ZipOutputStream(Files.newOutputStream(target))) {
            var entries = sourceJar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                // A derivative cannot retain its publisher's signature; source reads still verify it.
                if (entry.getName().matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")) continue;
                output.putNextEntry(new ZipEntry(entry.getName()));
                try (var input = sourceJar.getInputStream(entry)) {
                    if (entry.getName().equals("META-INF/MANIFEST.MF")) {
                        var manifest = new Manifest(input);
                        manifest.getEntries().clear();
                        manifest.write(output);
                    } else if (entry.getName().equals(ANNOTATION)) {
                        output.write(repairClass(input.readAllBytes()));
                        repaired = true;
                    } else input.transferTo(output);
                }
                output.closeEntry();
            }
        }
        if (!repaired) throw new IOException("Missing legacy GameTest annotation");
    }

    byte[] repairClass(byte[] original) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var input = new DataInputStream(new ByteArrayInputStream(original));
                var output = new DataOutputStream(bytes)) {
            if (input.readInt() != 0xCAFEBABE) throw new IOException("Invalid GameTest class");
            output.writeInt(0xCAFEBABE);
            output.writeInt(input.readInt());
            int count = input.readUnsignedShort();
            output.writeShort(count);
            int changes = 0;
            for (int index = 1; index < count; index++) {
                int tag = input.readUnsignedByte();
                output.writeByte(tag);
                if (tag == 1) {
                    String text = input.readUTF();
                    if (text.equals("field_11467")) { text = "NONE"; changes++; }
                    output.writeUTF(text);
                } else {
                    int size = switch (tag) {
                        case 3, 4, 9, 10, 11, 12, 17, 18 -> 4;
                        case 5, 6 -> 8;
                        case 7, 8, 16, 19, 20 -> 2;
                        case 15 -> 3;
                        default -> throw new IOException("Unknown class constant tag: " + tag);
                    };
                    byte[] value = input.readNBytes(size);
                    if (value.length != size) throw new IOException("Truncated class constant");
                    output.write(value);
                    if (tag == 5 || tag == 6) index++;
                }
            }
            if (changes != 1) throw new IOException("Legacy annotation changed; review or remove its repair");
            input.transferTo(output);
        }
        return bytes.toByteArray();
    }
}
