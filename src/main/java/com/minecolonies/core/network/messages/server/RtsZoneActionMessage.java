package com.minecolonies.core.network.messages.server;

import com.minecolonies.api.configuration.RtsZonePlacementMode;
import com.minecolonies.api.network.IMessage;
import com.minecolonies.core.rts.RtsResourceAggregator;
import com.minecolonies.core.rts.RtsZoneType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Client-to-Server packet for creating or deleting an RTS zone (supports Mode A auto-place and Mode B preview).
 */
public class RtsZoneActionMessage implements IMessage
{
    private BlockPos startPos;
    private BlockPos endPos;
    private RtsZoneType zoneType;
    private boolean deleteMode;
    private RtsZonePlacementMode placementMode;

    public RtsZoneActionMessage()
    {
        this.startPos = BlockPos.ZERO;
        this.endPos = BlockPos.ZERO;
        this.zoneType = RtsZoneType.RESIDENTIAL;
        this.deleteMode = false;
        this.placementMode = RtsZonePlacementMode.MODE_A_AUTO_PLACE;
    }

    public RtsZoneActionMessage(
        @NotNull final BlockPos startPos,
        @NotNull final BlockPos endPos,
        @NotNull final RtsZoneType zoneType,
        final boolean deleteMode,
        @NotNull final RtsZonePlacementMode placementMode)
    {
        this.startPos = startPos;
        this.endPos = endPos;
        this.zoneType = zoneType;
        this.deleteMode = deleteMode;
        this.placementMode = placementMode;
    }

    @Override
    public void toBytes(@NotNull final FriendlyByteBuf buf)
    {
        buf.writeBlockPos(startPos);
        buf.writeBlockPos(endPos);
        buf.writeEnum(zoneType);
        buf.writeBoolean(deleteMode);
        buf.writeEnum(placementMode);
    }

    @Override
    public void fromBytes(@NotNull final FriendlyByteBuf buf)
    {
        this.startPos = buf.readBlockPos();
        this.endPos = buf.readBlockPos();
        this.zoneType = buf.readEnum(RtsZoneType.class);
        this.deleteMode = buf.readBoolean();
        this.placementMode = buf.readEnum(RtsZonePlacementMode.class);
    }

    @Nullable
    @Override
    public LogicalSide getExecutionSide()
    {
        return LogicalSide.SERVER;
    }

    @Override
    public void onExecute(final NetworkEvent.Context ctxIn, final boolean isLogicalServer)
    {
        final ServerPlayer player = ctxIn.getSender();
        if (isLogicalServer && player != null)
        {
            RtsResourceAggregator.handleZoneActionOnServer(player, startPos, endPos, zoneType, deleteMode, placementMode);
        }
    }
}
