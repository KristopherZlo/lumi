package io.github.luma.gbreak.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.github.luma.gbreak.server.animal.AnimalSelector;
import java.util.Optional;
import net.minecraft.command.EntitySelectorReader;
import net.minecraft.command.argument.PosArgument;
import net.minecraft.command.argument.Vec3ArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

final class AnimoveCommandParser {

    private static final String LOOP_FLAG = "-loop";
    private static final SimpleCommandExceptionType LOOP_REQUIRES_RETURN = new SimpleCommandExceptionType(
            Text.literal("-loop requires return coordinates")
    );
    private static final SimpleCommandExceptionType DUPLICATE_LOOP = new SimpleCommandExceptionType(
            Text.literal("Duplicate -loop flag")
    );
    private static final SimpleCommandExceptionType DUPLICATE_SELECTOR = new SimpleCommandExceptionType(
            Text.literal("Duplicate animal selector")
    );
    private static final SimpleCommandExceptionType UNEXPECTED_OPTION = new SimpleCommandExceptionType(
            Text.literal("Expected return coordinates, an animal selector, or -loop")
    );

    AnimoveCommandOptions parse(ServerCommandSource source, String rawOptions) throws CommandSyntaxException {
        StringReader reader = new StringReader(rawOptions == null ? "" : rawOptions.trim());
        Optional<Vec3d> returnPosition = Optional.empty();
        AnimalSelector selector = AnimalSelector.all();
        boolean hasSelector = false;
        boolean loop = false;

        reader.skipWhitespace();
        if (reader.canRead() && this.tryReadLoopFlag(reader)) {
            loop = true;
        } else if (reader.canRead() && this.isCoordinateStart(reader.peek())) {
            returnPosition = Optional.of(this.readReturnPosition(source, reader));
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
        return new AnimoveCommandOptions(returnPosition, selector, loop);
    }

    AnimalSelector parseSelector(String rawSelector) throws CommandSyntaxException {
        return AnimalSelector.parse(rawSelector);
    }

    private Vec3d readReturnPosition(ServerCommandSource source, StringReader reader) throws CommandSyntaxException {
        int cursor = reader.getCursor();
        try {
            PosArgument argument = Vec3ArgumentType.vec3().parse(reader);
            return argument.getPos(source);
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
        new EntitySelectorReader(selectorReader, true).read();
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
