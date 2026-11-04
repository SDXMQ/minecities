package com.minecolonies.core.rts;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable data carrier representing a player-designated Cities-like zone in a MineColonies colony.
 */
public record RtsZoneData(
    @NotNull UUID id,
    int colonyId,
    @NotNull RtsZoneType type,
    @NotNull BoundingBox bounds,
    @Nullable BlockPos linkedBuildingPos)
{
    private static final String TAG_ID = "id";
    private static final String TAG_COLONY_ID = "colonyId";
    private static final String TAG_TYPE = "type";
    private static final String TAG_MIN_X = "minX";
    private static final String TAG_MIN_Y = "minY";
    private static final String TAG_MIN_Z = "minZ";
    private static final String TAG_MAX_X = "maxX";
    private static final String TAG_MAX_Y = "maxY";
    private static final String TAG_MAX_Z = "maxZ";
    private static final String TAG_LINKED_BUILDING = "linkedBuilding";

    public RtsZoneData
    {
        Objects.requireNonNull(id, "Zone id must not be null");
        Objects.requireNonNull(type, "Zone type must not be null");
        Objects.requireNonNull(bounds, "Zone bounds must not be null");
        linkedBuildingPos = linkedBuildingPos == null ? null : linkedBuildingPos.immutable();
    }

    public static RtsZoneData fromCorners(
        final int colonyId,
        @NotNull final RtsZoneType type,
        @NotNull final BlockPos firstCorner,
        @NotNull final BlockPos secondCorner,
        @Nullable final BlockPos linkedBuildingPos)
    {
        Objects.requireNonNull(firstCorner, "firstCorner must not be null");
        Objects.requireNonNull(secondCorner, "secondCorner must not be null");
        final BoundingBox box = BoundingBox.fromCorners(firstCorner, secondCorner);
        return new RtsZoneData(UUID.randomUUID(), colonyId, type, box, linkedBuildingPos);
    }

    public RtsZoneData withLinkedBuilding(@Nullable final BlockPos buildingPos)
    {
        return new RtsZoneData(this.id, this.colonyId, this.type, this.bounds, buildingPos);
    }

    public BlockPos center()
    {
        return this.bounds.getCenter();
    }

    public BlockPos groundCenter()
    {
        final BlockPos c = this.bounds.getCenter();
        return new BlockPos(c.getX(), this.bounds.minY(), c.getZ());
    }

    public boolean contains(@Nullable final BlockPos pos)
    {
        return pos != null && this.bounds.isInside(pos);
    }

    public boolean intersects(@Nullable final BoundingBox other)
    {
        return other != null && this.bounds.intersects(other);
    }

    public CompoundTag toNbt()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ID, this.id);
        tag.putInt(TAG_COLONY_ID, this.colonyId);
        tag.putInt(TAG_TYPE, this.type.id());
        tag.putInt(TAG_MIN_X, this.bounds.minX());
        tag.putInt(TAG_MIN_Y, this.bounds.minY());
        tag.putInt(TAG_MIN_Z, this.bounds.minZ());
        tag.putInt(TAG_MAX_X, this.bounds.maxX());
        tag.putInt(TAG_MAX_Y, this.bounds.maxY());
        tag.putInt(TAG_MAX_Z, this.bounds.maxZ());
        if (this.linkedBuildingPos != null)
        {
            tag.put(TAG_LINKED_BUILDING, NbtUtils.writeBlockPos(this.linkedBuildingPos));
        }
        return tag;
    }

    public CompoundTag saveToTag()
    {
        return toNbt();
    }

    public static RtsZoneData fromNbt(@NotNull final CompoundTag tag)
    {
        final UUID id = tag.hasUUID(TAG_ID) ? tag.getUUID(TAG_ID) : UUID.randomUUID();
        final int colonyId = tag.getInt(TAG_COLONY_ID);
        final RtsZoneType type = RtsZoneType.byId(tag.getInt(TAG_TYPE));
        final BoundingBox box = new BoundingBox(
            tag.getInt(TAG_MIN_X),
            tag.getInt(TAG_MIN_Y),
            tag.getInt(TAG_MIN_Z),
            tag.getInt(TAG_MAX_X),
            tag.getInt(TAG_MAX_Y),
            tag.getInt(TAG_MAX_Z)
        );
        final BlockPos linked = tag.contains(TAG_LINKED_BUILDING)
            ? NbtUtils.readBlockPos(tag.getCompound(TAG_LINKED_BUILDING))
            : null;
        return new RtsZoneData(id, colonyId, type, box, linked);
    }

    public static RtsZoneData loadFromTag(@NotNull final CompoundTag tag)
    {
        return fromNbt(tag);
    }

    public void toBytes(@NotNull final FriendlyByteBuf buf)
    {
        buf.writeUUID(this.id);
        buf.writeVarInt(this.colonyId);
        buf.writeVarInt(this.type.id());
        buf.writeVarInt(this.bounds.minX());
        buf.writeVarInt(this.bounds.minY());
        buf.writeVarInt(this.bounds.minZ());
        buf.writeVarInt(this.bounds.maxX());
        buf.writeVarInt(this.bounds.maxY());
        buf.writeVarInt(this.bounds.maxZ());
        buf.writeBoolean(this.linkedBuildingPos != null);
        if (this.linkedBuildingPos != null)
        {
            buf.writeBlockPos(this.linkedBuildingPos);
        }
    }

    public void writeToBuf(@NotNull final FriendlyByteBuf buf)
    {
        toBytes(buf);
    }

    public static RtsZoneData fromBytes(@NotNull final FriendlyByteBuf buf)
    {
        final UUID id = buf.readUUID();
        final int colonyId = buf.readVarInt();
        final RtsZoneType type = RtsZoneType.byId(buf.readVarInt());
        final BoundingBox box = new BoundingBox(
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt()
        );
        final BlockPos linked = buf.readBoolean() ? buf.readBlockPos() : null;
        return new RtsZoneData(id, colonyId, type, box, linked);
    }

    public static RtsZoneData readFromBuf(@NotNull final FriendlyByteBuf buf)
    {
        return fromBytes(buf);
    }
}
