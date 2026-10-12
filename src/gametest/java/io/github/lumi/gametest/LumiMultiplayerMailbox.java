package io.github.lumi.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Out-of-band test scheduling only; all Lumi actions still cross Minecraft TCP. */
final class LumiMultiplayerMailbox {
    private final Path directory;
    private int sequence;

    LumiMultiplayerMailbox() throws IOException {
        directory = Path.of(System.getProperty("lumi.multiplayer.mailbox")).toAbsolutePath();
        Files.createDirectories(directory);
    }

    void publish(String file, Properties values) throws IOException {
        Path temporary = Files.createTempFile(directory, "mail-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) { values.store(output, null); }
            Files.move(temporary, directory.resolve(file), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    Properties await(ClientGameTestContext context, String file) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
        while (!Files.exists(directory.resolve(file))) {
            if (System.nanoTime() > deadline) throw new AssertionError("Peer timed out: " + file);
            context.waitTick();
        }
        Properties values = new Properties();
        try (var input = Files.newInputStream(directory.resolve(file))) { values.load(input); }
        if (values.containsKey("failure")) throw new AssertionError(values.getProperty("failure"));
        return values;
    }

    Properties request(ClientGameTestContext context, String action, String argument)
            throws IOException {
        int id = ++sequence;
        Properties command = new Properties();
        command.setProperty("action", action);
        command.setProperty("argument", argument);
        publish("request-" + id, command);
        return await(context, "reply-" + id);
    }
}
