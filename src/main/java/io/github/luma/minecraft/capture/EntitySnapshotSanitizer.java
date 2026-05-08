package io.github.luma.minecraft.capture;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;

final class EntitySnapshotSanitizer {

    boolean sanitize(Entity entity) {
        return this.sanitizeArrowPickup(entity);
    }

    private boolean sanitizeArrowPickup(Entity entity) {
        if (!(entity instanceof AbstractArrow arrow) || arrow.pickup != null) {
            return false;
        }

        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        return true;
    }
}
