package com.citiescolony.api;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;

import java.util.Optional;
import java.util.function.IntFunction;

/**
 * Defines colony citizen professions and their associated zone and task types.
 */
public enum JobType implements StringRepresentable {
    NONE(0, "none", "무직 (Idle)", null),
    LUMBERJACK(1, "lumberjack", "나무꾼 (Lumberjack)", ZoneType.LOGGING),
    MINER(2, "miner", "광부 (Miner)", ZoneType.MINING),
    FARMER(3, "farmer", "농부 (Farmer)", ZoneType.FARMING),
    COURIER(4, "courier", "물류운반원 (Courier)", ZoneType.STORAGE),
    BUILDER(5, "builder", "건축가 (Builder)", ZoneType.RESIDENTIAL),
    GUARD(6, "guard", "경비병 (Guard)", ZoneType.DEFENSE);

    public static final Codec<JobType> CODEC = StringRepresentable.fromEnum(JobType::values);
    private static final IntFunction<JobType> BY_ID = ByIdMap.continuous(JobType::id, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
    public static final StreamCodec<ByteBuf, JobType> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, JobType::id);

    private final int id;
    private final String serializedName;
    private final String displayName;
    private final ZoneType workZoneType;

    JobType(int id, String serializedName, String displayName, ZoneType workZoneType) {
        this.id = id;
        this.serializedName = serializedName;
        this.displayName = displayName;
        this.workZoneType = workZoneType;
    }

    public int id() {
        return this.id;
    }

    public String displayName() {
        return this.displayName;
    }

    public Optional<ZoneType> workZoneType() {
        return Optional.ofNullable(this.workZoneType);
    }

    public boolean canExecute(ColonyTask.TaskType taskType) {
        if (taskType == null) {
            return false;
        }
        return switch (this) {
            case NONE -> false;
            case LUMBERJACK -> taskType == ColonyTask.TaskType.CHOP || taskType == ColonyTask.TaskType.DELIVER;
            case MINER -> taskType == ColonyTask.TaskType.MINE || taskType == ColonyTask.TaskType.DELIVER;
            case FARMER -> taskType == ColonyTask.TaskType.HARVEST || taskType == ColonyTask.TaskType.DELIVER;
            case COURIER -> taskType == ColonyTask.TaskType.DELIVER;
            case BUILDER -> taskType == ColonyTask.TaskType.BUILD || taskType == ColonyTask.TaskType.DELIVER;
            case GUARD -> taskType == ColonyTask.TaskType.GUARD;
        };
    }

    public static JobType forZoneType(ZoneType zoneType) {
        if (zoneType == null) {
            return NONE;
        }
        return switch (zoneType) {
            case RESIDENTIAL -> BUILDER;
            case LOGGING -> LUMBERJACK;
            case MINING -> MINER;
            case FARMING -> FARMER;
            case STORAGE -> COURIER;
            case DEFENSE -> GUARD;
        };
    }

    @Override
    public String getSerializedName() {
        return this.serializedName;
    }
}
