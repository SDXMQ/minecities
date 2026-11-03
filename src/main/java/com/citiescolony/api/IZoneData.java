package com.citiescolony.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;
import java.util.UUID;

/**
 * Read-only view contract for a designated city zone.
 */
public interface IZoneData {
    UUID id();

    ZoneType type();

    BoundingBox bounds();

    List<UUID> assignedCitizens();

    default boolean contains(BlockPos pos) {
        return pos != null && bounds().isInside(pos);
    }

    default BlockPos center() {
        return bounds().getCenter();
    }
}
