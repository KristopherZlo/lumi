package io.github.luma.gbreak.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.luma.gbreak.server.animal.AnimalMoveManager;
import io.github.luma.gbreak.server.animal.AnimalMovePlan;
import io.github.luma.gbreak.server.animal.AnimalSelector;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.Vec3ArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

public final class AnimoveCommand {

    private final AnimalMoveManager animalMoveManager;
    private final AnimoveCommandParser parser = new AnimoveCommandParser();

    public AnimoveCommand(AnimalMoveManager animalMoveManager) {
        this.animalMoveManager = animalMoveManager;
    }

    public void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("animove")
                    .then(CommandManager.argument("radii", DoubleArgumentType.doubleArg(0.0D))
                            .then(CommandManager.argument("target", Vec3ArgumentType.vec3())
                                    .executes(context -> this.start(
                                            context.getSource(),
                                            DoubleArgumentType.getDouble(context, "radii"),
                                            Vec3ArgumentType.getVec3(context, "target"),
                                            ""
                                    ))
                                    .then(CommandManager.argument("options", StringArgumentType.greedyString())
                                            .executes(context -> this.start(
                                                    context.getSource(),
                                                    DoubleArgumentType.getDouble(context, "radii"),
                                                    Vec3ArgumentType.getVec3(context, "target"),
                                                    StringArgumentType.getString(context, "options")
                                            ))))));

            dispatcher.register(CommandManager.literal("animovestop")
                    .executes(context -> this.stop(context.getSource(), ""))
                    .then(CommandManager.argument("animal_selector", StringArgumentType.greedyString())
                            .executes(context -> this.stop(
                                    context.getSource(),
                                    StringArgumentType.getString(context, "animal_selector")
                            ))));
        });
    }

    private int start(ServerCommandSource source, double radius, Vec3d destination, String rawOptions) throws CommandSyntaxException {
        ServerWorld world = source.getWorld();
        AnimoveCommandOptions options = this.parser.parse(source, rawOptions);
        AnimalSelector selector = options.selector();
        var animals = selector.selectWithin(source, world, source.getPosition(), radius);
        int started = this.animalMoveManager.start(
                world,
                animals,
                new AnimalMovePlan(destination, options.returnPosition(), options.loop())
        );
        source.sendFeedback(() -> Text.literal("Animove started for "
                + started
                + " animals"
                + (options.loop() ? " in loop" : "")
                + " using "
                + selector), false);
        return started;
    }

    private int stop(ServerCommandSource source, String rawSelector) throws CommandSyntaxException {
        ServerWorld world = source.getWorld();
        int stopped;
        if (rawSelector == null || rawSelector.isBlank()) {
            stopped = this.animalMoveManager.stopAll(world);
        } else {
            AnimalSelector selector = this.parser.parseSelector(rawSelector);
            stopped = this.animalMoveManager.stop(world, selector.selectedAnimalIds(source));
        }
        source.sendFeedback(() -> Text.literal("Animove stopped " + stopped + " animals"), false);
        return stopped;
    }
}
