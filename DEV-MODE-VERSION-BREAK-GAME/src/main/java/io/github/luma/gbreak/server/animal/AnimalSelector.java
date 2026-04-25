package io.github.luma.gbreak.server.animal;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.EntitySelectorReader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public final class AnimalSelector {

    private static final SimpleCommandExceptionType INVALID_SELECTOR = new SimpleCommandExceptionType(
            Text.literal("Expected an animal selector like [type=cow,name=Bob]")
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
        EntitySelector parsedSelector = new EntitySelectorReader(reader, true).read();
        reader.skipWhitespace();
        if (reader.canRead()) {
            throw INVALID_SELECTOR.createWithContext(reader);
        }
        return new AnimalSelector(selectorText, parsedSelector);
    }

    public List<AnimalEntity> selectWithin(ServerCommandSource source, ServerWorld world, Vec3d center, double radius)
            throws CommandSyntaxException {
        double radiusSquared = radius * radius;
        if (this.selector == null) {
            Box bounds = Box.of(center, radius * 2.0D, radius * 2.0D, radius * 2.0D);
            return world.getEntitiesByType(
                    TypeFilter.instanceOf(AnimalEntity.class),
                    bounds,
                    animal -> animal.isAlive() && animal.squaredDistanceTo(center) <= radiusSquared
            );
        }

        List<AnimalEntity> animals = new ArrayList<>();
        for (Entity entity : this.selector.getEntities(source)) {
            if (entity instanceof AnimalEntity animal
                    && animal.getEntityWorld() == world
                    && animal.isAlive()
                    && animal.squaredDistanceTo(center) <= radiusSquared) {
                animals.add(animal);
            }
        }
        return animals;
    }

    public Set<UUID> selectedAnimalIds(ServerCommandSource source) throws CommandSyntaxException {
        if (this.selector == null) {
            return Set.of();
        }
        return this.selector.getEntities(source).stream()
                .filter(AnimalEntity.class::isInstance)
                .map(Entity::getUuid)
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
