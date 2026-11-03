package com.citiescolony.api;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;

import java.util.function.IntFunction;

/**
 * Lifecycle state of a ColonyTask in the task queue and execution pipeline.
 */
public enum TaskState implements StringRepresentable {
    PENDING(0, "pending"),
    ASSIGNED(1, "assigned"),
    IN_PROGRESS(2, "in_progress"),
    COMPLETED(3, "completed"),
    CANCELLED(4, "cancelled");

    public static final Codec<TaskState> CODEC = StringRepresentable.fromEnum(TaskState::values);
    private static final IntFunction<TaskState> BY_ID = ByIdMap.continuous(TaskState::id, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
    public static final StreamCodec<ByteBuf, TaskState> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, TaskState::id);

    private final int id;
    private final String serializedName;

    TaskState(int id, String serializedName) {
        this.id = id;
        this.serializedName = serializedName;
    }

    public int id() {
        return this.id;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }

    @Override
    public String getSerializedName() {
        return this.serializedName;
    }
}
