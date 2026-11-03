package com.citiescolony.common.network;

import com.citiescolony.api.ColonyIds;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
import java.util.Objects;

/**
 * C2S payload sent when the player right-clicks a world block in RTS mode to command selected citizens.
 */
public record CitizenCommandPayload(
        List<Integer> citizenEntityIds,
        BlockPos targetPos
) implements CustomPacketPayload {

    public static final Type<CitizenCommandPayload> TYPE = new Type<>(ColonyIds.id("citizen_command"));

    public static final StreamCodec<FriendlyByteBuf, CitizenCommandPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(64)), CitizenCommandPayload::citizenEntityIds,
            BlockPos.STREAM_CODEC, CitizenCommandPayload::targetPos,
            CitizenCommandPayload::new
    );

    public CitizenCommandPayload {
        citizenEntityIds = citizenEntityIds == null ? List.of() : List.copyOf(citizenEntityIds);
        Objects.requireNonNull(targetPos, "targetPos must not be null");
    }

    @Override
    public Type<CitizenCommandPayload> type() {
        return TYPE;
    }
}
