package com.citiescolony.common.network;

import com.citiescolony.api.ColonyIds;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C payload periodically synchronizing storage resource totals and citizen population statistics
 * to the client's RtsTopBarHudOverlay.
 */
public record ResourceHudSyncPayload(
        int food,
        int wood,
        int stone,
        int minerals,
        int totalCitizens,
        int idleCitizens
) implements CustomPacketPayload {

    public static final Type<ResourceHudSyncPayload> TYPE = new Type<>(ColonyIds.id("resource_hud_sync"));

    public static final StreamCodec<FriendlyByteBuf, ResourceHudSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ResourceHudSyncPayload::food,
            ByteBufCodecs.VAR_INT, ResourceHudSyncPayload::wood,
            ByteBufCodecs.VAR_INT, ResourceHudSyncPayload::stone,
            ByteBufCodecs.VAR_INT, ResourceHudSyncPayload::minerals,
            ByteBufCodecs.VAR_INT, ResourceHudSyncPayload::totalCitizens,
            ByteBufCodecs.VAR_INT, ResourceHudSyncPayload::idleCitizens,
            ResourceHudSyncPayload::new
    );

    @Override
    public Type<ResourceHudSyncPayload> type() {
        return TYPE;
    }
}
