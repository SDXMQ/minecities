package com.citiescolony.common.network;

import com.citiescolony.api.ColonyIds;
import com.citiescolony.api.ZoneType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Objects;

/**
 * C2S payload sent when the player finishes dragging a 3D box in RTS mode to create or delete a city zone.
 */
public record ZoneActionPayload(
        BlockPos startPos,
        BlockPos endPos,
        ZoneType zoneType,
        boolean deleteMode
) implements CustomPacketPayload {

    public static final Type<ZoneActionPayload> TYPE = new Type<>(ColonyIds.id("zone_action"));

    public static final StreamCodec<FriendlyByteBuf, ZoneActionPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ZoneActionPayload::startPos,
            BlockPos.STREAM_CODEC, ZoneActionPayload::endPos,
            ZoneType.STREAM_CODEC, ZoneActionPayload::zoneType,
            ByteBufCodecs.BOOL, ZoneActionPayload::deleteMode,
            ZoneActionPayload::new
    );

    public ZoneActionPayload {
        Objects.requireNonNull(startPos, "startPos must not be null");
        Objects.requireNonNull(endPos, "endPos must not be null");
        Objects.requireNonNull(zoneType, "zoneType must not be null");
    }

    @Override
    public Type<ZoneActionPayload> type() {
        return TYPE;
    }
}
