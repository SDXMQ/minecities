package com.minecolonies.core.network.messages.server;

import com.minecolonies.api.network.IMessage;
import com.minecolonies.core.rts.RtsResourceAggregator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-to-Server packet for commanding selected MineColonies citizens to move or take a job.
 */
public class RtsCitizenCommandMessage implements IMessage
{
    private int colonyId;
    private List<Integer> citizenIds;
    @Nullable
    private BlockPos targetPos;
    @Nullable
    private String jobId;

    public RtsCitizenCommandMessage()
    {
        this.colonyId = 0;
        this.citizenIds = List.of();
        this.targetPos = null;
        this.jobId = null;
    }

    public RtsCitizenCommandMessage(
        final int colonyId,
        @NotNull final List<Integer> citizenIds,
        @Nullable final BlockPos targetPos,
        @Nullable final String jobId)
    {
        this.colonyId = colonyId;
        this.citizenIds = List.copyOf(citizenIds);
        this.targetPos = targetPos;
        this.jobId = jobId;
    }

    @Override
    public void toBytes(@NotNull final FriendlyByteBuf buf)
    {
        buf.writeInt(colonyId);
        buf.writeInt(citizenIds.size());
        for (final int id : citizenIds)
        {
            buf.writeInt(id);
        }
        buf.writeBoolean(targetPos != null);
        if (targetPos != null)
        {
            buf.writeBlockPos(targetPos);
        }
        buf.writeBoolean(jobId != null && !jobId.isEmpty());
        if (jobId != null && !jobId.isEmpty())
        {
            buf.writeUtf(jobId, 128);
        }
    }

    @Override
    public void fromBytes(@NotNull final FriendlyByteBuf buf)
    {
        this.colonyId = buf.readInt();
        final int count = Math.min(256, Math.max(0, buf.readInt()));
        final List<Integer> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            ids.add(buf.readInt());
        }
        this.citizenIds = List.copyOf(ids);
        this.targetPos = buf.readBoolean() ? buf.readBlockPos() : null;
        this.jobId = buf.readBoolean() ? buf.readUtf(128) : null;
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
            RtsResourceAggregator.handleCitizenCommandOnServer(player, colonyId, citizenIds, targetPos, jobId);
        }
    }
}
