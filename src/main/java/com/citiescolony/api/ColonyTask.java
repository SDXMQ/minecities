package com.citiescolony.api;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * Represents an atomic task generated from a city zone or player command.
 */
public final class ColonyTask implements IColonyTask, Comparable<ColonyTask> {

    public enum TaskType implements StringRepresentable {
        CHOP(0, "chop", 50),
        MINE(1, "mine", 45),
        HARVEST(2, "harvest", 65),
        BUILD(3, "build", 70),
        DELIVER(4, "deliver", 80),
        GUARD(5, "guard", 100);

        public static final Codec<TaskType> CODEC = StringRepresentable.fromEnum(TaskType::values);
        private static final IntFunction<TaskType> BY_ID = ByIdMap.continuous(TaskType::id, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
        public static final StreamCodec<ByteBuf, TaskType> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, TaskType::id);

        private final int id;
        private final String serializedName;
        private final int defaultPriority;

        TaskType(int id, String serializedName, int defaultPriority) {
            this.id = id;
            this.serializedName = serializedName;
            this.defaultPriority = defaultPriority;
        }

        public int id() {
            return this.id;
        }

        public int defaultPriority() {
            return this.defaultPriority;
        }

        @Override
        public String getSerializedName() {
            return this.serializedName;
        }
    }

    public static final Codec<ColonyTask> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("task_id").forGetter(ColonyTask::taskId),
            TaskType.CODEC.fieldOf("type").forGetter(ColonyTask::type),
            BlockPos.CODEC.fieldOf("target_pos").forGetter(ColonyTask::targetPos),
            Codec.INT.fieldOf("priority").forGetter(ColonyTask::priority),
            TaskState.CODEC.fieldOf("state").forGetter(ColonyTask::state),
            UUIDUtil.CODEC.optionalFieldOf("assigned_citizen").forGetter(ColonyTask::assignedCitizenId)
    ).apply(instance, (id, type, pos, priority, state, assigned) ->
            new ColonyTask(id, type, pos, priority, state, assigned.orElse(null))));

    public static final StreamCodec<FriendlyByteBuf, ColonyTask> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, ColonyTask::taskId,
            TaskType.STREAM_CODEC, ColonyTask::type,
            BlockPos.STREAM_CODEC, ColonyTask::targetPos,
            ByteBufCodecs.VAR_INT, ColonyTask::priority,
            TaskState.STREAM_CODEC, ColonyTask::state,
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), ColonyTask::assignedCitizenId,
            (id, type, pos, priority, state, assigned) ->
                    new ColonyTask(id, type, pos, priority, state, assigned.orElse(null))
    );

    private final UUID taskId;
    private final TaskType type;
    private final BlockPos targetPos;
    private final int priority;
    private volatile TaskState state;
    private volatile UUID assignedCitizenId;

    public ColonyTask(TaskType type, BlockPos targetPos, int priority) {
        this(UUID.randomUUID(), type, targetPos, priority, TaskState.PENDING, null);
    }

    public ColonyTask(
            UUID taskId,
            TaskType type,
            BlockPos targetPos,
            int priority,
            TaskState state,
            UUID assignedCitizenId
    ) {
        this.taskId = Objects.requireNonNull(taskId, "taskId must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.targetPos = Objects.requireNonNull(targetPos, "targetPos must not be null").immutable();
        this.priority = priority;
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.assignedCitizenId = assignedCitizenId;
    }

    @Override
    public UUID taskId() {
        return this.taskId;
    }

    @Override
    public TaskType type() {
        return this.type;
    }

    @Override
    public BlockPos targetPos() {
        return this.targetPos;
    }

    @Override
    public int priority() {
        return this.priority;
    }

    @Override
    public TaskState state() {
        return this.state;
    }

    @Override
    public Optional<UUID> assignedCitizenId() {
        return Optional.ofNullable(this.assignedCitizenId);
    }

    public synchronized boolean assignTo(UUID citizenId) {
        Objects.requireNonNull(citizenId, "citizenId must not be null");
        if (this.state != TaskState.PENDING) {
            return false;
        }
        this.assignedCitizenId = citizenId;
        this.state = TaskState.ASSIGNED;
        return true;
    }

    public synchronized void markInProgress() {
        if (this.state == TaskState.ASSIGNED || this.state == TaskState.PENDING) {
            this.state = TaskState.IN_PROGRESS;
        }
    }

    public synchronized void markCompleted() {
        this.state = TaskState.COMPLETED;
    }

    public synchronized void markCancelled() {
        this.state = TaskState.CANCELLED;
    }

    public synchronized void resetToPending() {
        this.assignedCitizenId = null;
        this.state = TaskState.PENDING;
    }

    @Override
    public int compareTo(ColonyTask other) {
        int byPriority = Integer.compare(other.priority, this.priority);
        if (byPriority != 0) {
            return byPriority;
        }
        return this.taskId.compareTo(other.taskId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ColonyTask that)) return false;
        return this.taskId.equals(that.taskId);
    }

    @Override
    public int hashCode() {
        return this.taskId.hashCode();
    }

    @Override
    public String toString() {
        return "ColonyTask{" +
                "taskId=" + taskId +
                ", type=" + type +
                ", targetPos=" + targetPos +
                ", priority=" + priority +
                ", state=" + state +
                ", assignedCitizenId=" + assignedCitizenId +
                '}';
    }
}
