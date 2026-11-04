package com.minecolonies.core.network.messages.client;

import com.minecolonies.api.network.IMessage;
import com.minecolonies.core.rts.RtsResourceAggregator;
import com.minecolonies.core.rts.RtsZoneData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-Client packet syncing RTS resource dashboard metrics and active zones.
 */
public class RtsSyncMessage implements IMessage
{
    private RtsResourceAggregator.ColonySnapshot snapshot;

    public RtsSyncMessage()
    {
        this.snapshot = new RtsResourceAggregator.ColonySnapshot(0, 0, 0, 0, 0, 0, 0, 0, 5.0D, List.of());
    }

    public RtsSyncMessage(@NotNull final RtsResourceAggregator.ColonySnapshot snapshot)
    {
        this.snapshot = snapshot;
    }

    @Override
    public void toBytes(@NotNull final FriendlyByteBuf buf)
    {
        buf.writeInt(snapshot.colonyId());
        buf.writeInt(snapshot.food());
        buf.writeInt(snapshot.wood());
        buf.writeInt(snapshot.stone());
        buf.writeInt(snapshot.minerals());
        buf.writeInt(snapshot.totalCitizens());
        buf.writeInt(snapshot.maxCitizens());
        buf.writeInt(snapshot.idleCitizens());
        buf.writeDouble(snapshot.overallHappiness());

        final List<RtsZoneData> zones = snapshot.zones();
        buf.writeInt(zones.size());
        for (final RtsZoneData zone : zones)
        {
            zone.writeToBuf(buf);
        }
    }

    @Override
    public void fromBytes(@NotNull final FriendlyByteBuf buf)
    {
        final int colonyId = buf.readInt();
        final int food = buf.readInt();
        final int wood = buf.readInt();
        final int stone = buf.readInt();
        final int minerals = buf.readInt();
        final int totalCitizens = buf.readInt();
        final int maxCitizens = buf.readInt();
        final int idleCitizens = buf.readInt();
        final double overallHappiness = buf.readDouble();

        final int zoneCount = Math.min(4096, Math.max(0, buf.readInt()));
        final List<RtsZoneData> zones = new ArrayList<>(zoneCount);
        for (int i = 0; i < zoneCount; i++)
        {
            zones.add(RtsZoneData.readFromBuf(buf));
        }

        this.snapshot = new RtsResourceAggregator.ColonySnapshot(
            colonyId,
            food,
            wood,
            stone,
            minerals,
            totalCitizens,
            maxCitizens,
            idleCitizens,
            overallHappiness,
            List.copyOf(zones)
        );
    }

    @Nullable
    @Override
    public LogicalSide getExecutionSide()
    {
        return LogicalSide.CLIENT;
    }

    @Override
    public void onExecute(final NetworkEvent.Context ctxIn, final boolean isLogicalServer)
    {
        if (!isLogicalServer && snapshot != null)
        {
            RtsResourceAggregator.updateClientSnapshot(snapshot);
        }
    }
}
