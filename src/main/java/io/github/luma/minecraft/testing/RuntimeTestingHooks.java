package io.github.luma.minecraft.testing;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.luma.minecraft.command.LumaTestingCommands;
import java.util.Objects;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/**
 * Runtime-test integration point kept out of normal production execution.
 */
public interface RuntimeTestingHooks {

    void registerCommands(LiteralArgumentBuilder<CommandSourceStack> root);

    void tick(MinecraftServer server);

    boolean enabled();

    static RuntimeTestingHooks load() {
        return RuntimeTestingConfig.load().enabled()
                ? enabled(SingleplayerTestingService.getInstance())
                : disabled();
    }

    static RuntimeTestingHooks disabled() {
        return DisabledRuntimeTestingHooks.INSTANCE;
    }

    static RuntimeTestingHooks enabled(SingleplayerTestingService service) {
        return new EnabledRuntimeTestingHooks(Objects.requireNonNull(service, "service"));
    }

    final class DisabledRuntimeTestingHooks implements RuntimeTestingHooks {

        private static final DisabledRuntimeTestingHooks INSTANCE = new DisabledRuntimeTestingHooks();

        private DisabledRuntimeTestingHooks() {
        }

        @Override
        public void registerCommands(LiteralArgumentBuilder<CommandSourceStack> root) {
        }

        @Override
        public void tick(MinecraftServer server) {
        }

        @Override
        public boolean enabled() {
            return false;
        }
    }

    final class EnabledRuntimeTestingHooks implements RuntimeTestingHooks {

        private final SingleplayerTestingService service;
        private final LumaTestingCommands commands;

        private EnabledRuntimeTestingHooks(SingleplayerTestingService service) {
            this.service = service;
            this.commands = new LumaTestingCommands(service);
        }

        @Override
        public void registerCommands(LiteralArgumentBuilder<CommandSourceStack> root) {
            this.commands.register(root);
        }

        @Override
        public void tick(MinecraftServer server) {
            this.service.tick(server);
        }

        @Override
        public boolean enabled() {
            return true;
        }
    }
}
