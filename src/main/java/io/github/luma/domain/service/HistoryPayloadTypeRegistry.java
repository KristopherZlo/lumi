package io.github.luma.domain.service;

import net.minecraft.core.registries.BuiltInRegistries;

interface HistoryPayloadTypeRegistry {

    boolean knownBlockEntityId(String id);

    boolean knownEntityId(String id);
}

final class MinecraftHistoryPayloadTypeRegistry implements HistoryPayloadTypeRegistry {

    @Override
    public boolean knownBlockEntityId(String id) {
        for (var blockEntityType : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            if (id.equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntityType).toString())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean knownEntityId(String id) {
        for (var entityType : BuiltInRegistries.ENTITY_TYPE) {
            if (id.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entityType).toString())) {
                return true;
            }
        }
        return false;
    }
}
