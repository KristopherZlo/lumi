package io.github.lumi.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ExternalCodeExecutionBoundaryTest {
    private static final List<ForbiddenApi> FORBIDDEN_APIS = List.of(
            forbidden("OS process construction", "\\b(?:ProcessBuilder|ProcessImpl)\\b"),
            forbidden("OS process execution", "\\.\\s*exec\\s*\\("),
            forbidden("desktop application launch", "\\bjava\\.awt\\.Desktop\\b"),
            forbidden("native library loading",
                    "(?:System|Runtime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\))"
                            + "\\s*\\.\\s*(?:load|loadLibrary)\\s*\\("),
            forbidden("foreign or JNA calls", "\\b(?:java\\.lang\\.foreign|com\\.sun\\.jna)\\b"),
            forbidden("JNI or Unsafe LZ4 implementation",
                    "LZ4Factory\\s*\\.\\s*(?:fastestInstance|fastestJavaInstance|"
                            + "native(?:Insecure)?Instance|unsafe(?:Insecure)?Instance)\\s*\\("),
            forbidden("Java object deserialization", "\\bObjectInputStream\\b"),
            forbidden("XML object deserialization", "\\bXMLDecoder\\b"),
            forbidden("script evaluation", "\\b(?:javax\\.script|ScriptEngine|GroovyShell)\\b"),
            forbidden("runtime compilation", "\\b(?:javax\\.tools|JavaCompiler)\\b"),
            forbidden("dynamic class loading",
                    "\\b(?:ClassLoader|URLClassLoader|MethodHandles)\\b|"
                            + "Class\\s*\\.\\s*forName\\s*\\("),
            forbidden("reflective invocation",
                    "\\bjava\\.lang\\.reflect\\b|\\.\\s*setAccessible\\s*\\("),
            forbidden("unsafe JVM access",
                    "\\b(?:sun\\.misc|jdk\\.internal\\.misc)\\.Unsafe\\b"));
    private static final Set<String> EXECUTABLE_RESOURCE_SUFFIXES = Set.of(
            ".bat", ".class", ".cmd", ".dll", ".dylib", ".exe", ".jar",
            ".js", ".ps1", ".sh", ".so");

    @Test
    void productionCodeCannotLaunchOrDynamicallyLoadExternalCode() throws Exception {
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (ForbiddenApi api : FORBIDDEN_APIS) {
                    if (api.pattern().matcher(source).find()) {
                        violations.add(file + ": " + api.capability());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    @Test
    void productionResourcesCannotBundleExecutablePayloads() throws Exception {
        List<Path> violations;
        try (var files = Files.walk(Path.of("src/main/resources"))) {
            violations = files.filter(Files::isRegularFile)
                    .filter(ExternalCodeExecutionBoundaryTest::hasExecutableSuffix)
                    .toList();
        }
        assertTrue(violations.isEmpty(), () -> "Executable resources: " + violations);
    }

    private static ForbiddenApi forbidden(String capability, String expression) {
        return new ForbiddenApi(capability, Pattern.compile(expression));
    }

    private static boolean hasExecutableSuffix(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXECUTABLE_RESOURCE_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    private record ForbiddenApi(String capability, Pattern pattern) { }
}
