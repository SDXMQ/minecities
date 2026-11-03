package com.citiescolony.common.network;

import com.citiescolony.api.ColonyIds;
import com.citiescolony.api.JobType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Objects;

/**
 * C2S payload sent to change a specific ColonyCitizenEntity's profession.
 */
public record JobAssignPayload(
        int citizenEntityId,
        JobType jobType
) implements CustomPacketPayload {

    public static final Type<JobAssignPayload> TYPE = new Type<>(ColonyIds.id("job_assign"));

    public static final StreamCodec<FriendlyByteBuf, JobAssignPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, JobAssignPayload::citizenEntityId,
            JobType.STREAM_CODEC, JobAssignPayload::jobType,
            JobAssignPayload::new
    );

    public JobAssignPayload {
        Objects.requireNonNull(jobType, "jobType must not be null");
    }

    @Override
    public Type<JobAssignPayload> type() {
        return TYPE;
    }
}
