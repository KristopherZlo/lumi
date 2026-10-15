package io.github.lumi;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/** Reads the selected adapter's compiled source for source-level UI contracts. */
public final class ProductionJavaSource {
    private ProductionJavaSource() { }

    public static String read(Path path) throws IOException {
        String text = path.toString().replace('\\', '/');
        String roots = System.getProperty("lumi.test.production.java");
        if (roots == null || !text.startsWith("src/main/java/")) return Files.readString(path);
        String relative = text.substring("src/main/java/".length());
        for (String root : roots.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            Path candidate = Path.of(root).resolve(relative);
            if (Files.isRegularFile(candidate)) return Files.readString(candidate);
        }
        throw new NoSuchFileException("No compiled adapter source for " + path);
    }
}
