package io.github.luma.minecraft.animal;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class AnimalSelector {

    private static final SimpleCommandExceptionType INVALID_SELECTOR = new SimpleCommandExceptionType(
            Component.literal("Expected an animal selector like [type=cow,name=Bob]")
    );
    private static final AnimalSelector ALL = new AnimalSelector("", null);

    private final String raw;
    private final EntitySelector selector;

    private AnimalSelector(String raw, EntitySelector selector) {
        this.raw = raw;
        this.selector = selector;
    }

    public static AnimalSelector all() {
        return ALL;
    }

    public static AnimalSelector parse(String raw) throws CommandSyntaxException {
        String selectorText = raw == null ? "" : raw.trim();
        if (selectorText.isEmpty()) {
            return all();
        }
        String normalized = normalize(selectorText);
        StringReader reader = new StringReader(normalized);
        EntitySelector parsedSelector = new EntitySelectorParser(reader, true).parse();
        reader.skipWhitespace();
        if (reader.canRead()) {
            throw INVALID_SELECTOR.createWithContext(reader);
        }
        return new AnimalSelector(selectorText, parsedSelector);
    }

    public List<Animal> selectWithin(CommandSourceStack source, ServerLevel level, Vec3 center, double radius)
            throws CommandSyntaxException {
        double radiusSqr = radius * radius;
        if (this.selector == null) {
            AABB bounds = AABB.ofSize(center, radius * 2.0D, radius * 2.0D, radius * 2.0D);
            return level.getEntitiesOfClass(Animal.class, bounds, animal -> animal.isAlive()
                    && animal.distanceToSqr(center) <= radiusSqr);
        }

        List<Animal> animals = new ArrayList<>();
        for (Entity entity : this.selector.findEntities(source)) {
            if (entity instanceof Animal animal
                    && animal.level() == level
                    && animal.isAlive()
                    && animal.distanceToSqr(center) <= radiusSqr) {
                animals.add(animal);
            }
        }
        return animals;
    }

    public Set<UUID> selectedAnimalIds(CommandSourceStack source) throws CommandSyntaxException {
        if (this.selector == null) {
            return Set.of();
        }
        return this.selector.findEntities(source).stream()
                .filter(Animal.class::isInstance)
                .map(Entity::getUUID)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String toString() {
        return this.raw.isEmpty() ? "[all animals]" : this.raw;
    }

    private static String normalize(String selectorText) throws CommandSyntaxException {
        if (selectorText.startsWith("[")) {
            return "@e" + selectorText;
        }
        if (selectorText.startsWith("@")) {
            return selectorText;
        }
        StringReader reader = new StringReader(selectorText);
        throw INVALID_SELECTOR.createWithContext(reader);
    }
}
