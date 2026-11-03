package com.citiescolony.api;

import net.minecraft.core.BlockPos;

import java.util.Optional;
import java.util.UUID;

/**
 * Common interface for colony task work units.
 */
public interface IColonyTask {
    UUID taskId();

    ColonyTask.TaskType type();

    BlockPos targetPos();

    int priority();

    TaskState state();

    Optional<UUID> assignedCitizenId();
}
