package com.citiescolony.common.network;

import com.citiescolony.api.ColonyIds;
import com.citiescolony.api.ZoneData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
import java.util.Optional;

/**
 * S2C payload synchronizing the server's full list of ZoneData and Town Hall position to clients.
 */
public record CitySyncPayload(
        List<ZoneData> zones,
        Optional<BlockPos> townHallPos
) implements CustomPacketPayload {

    public static final Type<CitySyncPayload> TYPE = new Type<>(ColonyIds.id("city_sync"));

    public static final StreamCodec<FriendlyByteBuf, CitySyncPayload> STREAM_CODEC = StreamCodec.composite(
            ZoneData.STREAM_CODEC.apply(ByteBufCodecs.list()), CitySyncPayload::zones,
            ByteBufCodecs.optional(BlockPos.STREAM_CODEC), CitySyncPayload::townHallPos,
            CitySyncPayload::new
    );

    public CitySyncPayload {
        zones = zones == null ? List.of() : List.copyOf(zones);
        townHallPos = townHallPos == null ? Optional.empty() : townHallPos.map(BlockPos::immutable);
    }

    @Override
    public Type<CitySyncPayload> type() {
        return TYPE;
    }
}
