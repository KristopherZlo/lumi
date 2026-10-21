package io.github.lumi.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientAccentRepositoryTest {
    @TempDir Path directory;

    @Test
    void acceptsOnlyDefaultOrAnOpaqueRgbHex() {
        assertEquals(ClientAccentRepository.DEFAULT, ClientAccentRepository.parse("DeFaUlT"));
        assertEquals(0xff12abef, ClientAccentRepository.parse("#12aBeF"));
        assertEquals(0xff000000, ClientAccentRepository.parse("000000"));
        assertEquals(0xffffffff, ClientAccentRepository.parse("FFFFFF"));
        for (String invalid : new String[]{"", "abc", "#AABBCCDD", "-12345", "red", "12 3456"}) {
            assertThrows(IllegalArgumentException.class, () -> ClientAccentRepository.parse(invalid));
        }
    }

    @Test
    void persistsTheChoiceAndFallsBackFromInvalidFiles() throws Exception {
        Path file = directory.resolve("config/lumi-accent");
        var repository = new ClientAccentRepository(file);
        assertEquals(ClientAccentRepository.DEFAULT, repository.load());
        repository.save(0xffabcdef);
        assertEquals(0xffabcdef, new ClientAccentRepository(file).load());
        repository.save(ClientAccentRepository.DEFAULT);
        assertEquals(ClientAccentRepository.DEFAULT, repository.load());
        Files.writeString(file, "invalid");
        assertEquals(ClientAccentRepository.DEFAULT, repository.load());
        try (var files = Files.list(file.getParent())) {
            assertEquals(1, files.count());
        }
    }
}
