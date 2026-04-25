package io.github.luma.minecraft.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.github.luma.minecraft.animal.AnimalSelector;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

final class AnimalMoveCommandParser {

    private static final String LOOP_FLAG = "-loop";
    private static final SimpleCommandExceptionType LOOP_REQUIRES_RETURN = new SimpleCommandExceptionType(
            Component.literal("-loop requires return coordinates")
    );
    private static final SimpleCommandExceptionType DUPLICATE_LOOP = new SimpleCommandExceptionType(
            Component.literal("Duplicate -loop flag")
    );
    private static final SimpleCommandExceptionType DUPLICATE_SELECTOR = new SimpleCommandExceptionType(
            Component.literal("Duplicate animal selector")
    );
    private static final SimpleCommandExceptionType UNEXPECTED_OPTION = new SimpleCommandExceptionType(
            Component.literal("Expected return coordinates, an animal selector, or -loop")
    );

    AnimalMoveCommandOptions parse(CommandSourceStack source, String rawOptions) throws CommandSyntaxException {
        StringReader reader = new StringReader(rawOptions == null ? "" : rawOptions.trim());
        Optional<Vec3> returnPosition = Optional.empty();
        AnimalSelector selector = AnimalSelector.all();
        boolean hasSelector = false;
        boolean loop = false;

        reader.skipWhitespace();
        if (reader.canRead() && !this.tryReadLoopFlag(reader) && this.isCoordinateStart(reader.peek())) {
            returnPosition = Optional.of(this.readReturnPosition(source, reader));
        } else if (reader.getCursor() > 0) {
            loop = true;
        }

        while (reader.canRead()) {
            reader.skipWhitespace();
            if (!reader.canRead()) {
                break;
            }
            if (this.tryReadLoopFlag(reader)) {
                if (loop) {
                    throw DUPLICATE_LOOP.createWithContext(reader);
                }
                loop = true;
                continue;
            }
            if (hasSelector) {
                throw DUPLICATE_SELECTOR.createWithContext(reader);
            }
            selector = AnimalSelector.parse(this.readSelectorText(reader));
            hasSelector = true;
        }

        if (loop && returnPosition.isEmpty()) {
            throw LOOP_REQUIRES_RETURN.createWithContext(reader);
        }
        return new AnimalMoveCommandOptions(returnPosition, selector, loop);
    }

    AnimalSelector parseSelector(String rawSelector) throws CommandSyntaxException {
        return AnimalSelector.parse(rawSelector);
    }

    private Vec3 readReturnPosition(CommandSourceStack source, StringReader reader) throws CommandSyntaxException {
        int cursor = reader.getCursor();
        try {
            Coordinates coordinates = Vec3Argument.vec3().parse(reader);
            return coordinates.getPosition(source);
        } catch (CommandSyntaxException exception) {
            reader.setCursor(cursor);
            throw exception;
        }
    }

    private String readSelectorText(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        String remaining = reader.getString().substring(start);
        boolean bracketSelector = remaining.startsWith("[");
        if (!bracketSelector && !remaining.startsWith("@")) {
            throw UNEXPECTED_OPTION.createWithContext(reader);
        }

        String selectorInput = bracketSelector ? "@e" + remaining : remaining;
        StringReader selectorReader = new StringReader(selectorInput);
        new EntitySelectorParser(selectorReader, true).parse();
        int consumed = selectorReader.getCursor() - (bracketSelector ? 2 : 0);
        reader.setCursor(start + consumed);
        return reader.getString().substring(start, start + consumed);
    }

    private boolean tryReadLoopFlag(StringReader reader) {
        int start = reader.getCursor();
        String input = reader.getString();
        int end = start + LOOP_FLAG.length();
        if (end > input.length() || !input.startsWith(LOOP_FLAG, start)) {
            return false;
        }
        if (end < input.length() && !Character.isWhitespace(input.charAt(end))) {
            return false;
        }
        reader.setCursor(end);
        return true;
    }

    private boolean isCoordinateStart(char value) {
        return value == '~'
                || value == '^'
                || value == '+'
                || value == '-'
                || value == '.'
                || Character.isDigit(value);
    }
}
