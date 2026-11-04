package com.minecolonies.core.rts;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * World-persistent SavedData storing Cities-like RTS zones per dimension and colony.
 */
public class RtsZoneSavedData extends SavedData
{
    public static final String DATA_NAME = "minecolonies_rts_zones";
    private static final String TAG_ZONES = "zones";

    private final Map<UUID, RtsZoneData> zones = new LinkedHashMap<>();
    private long revision = 0L;

    public RtsZoneSavedData()
    {
    }

    public static RtsZoneSavedData get(@NotNull final ServerLevel level)
    {
        Objects.requireNonNull(level, "ServerLevel must not be null");
        return level.getDataStorage().computeIfAbsent(RtsZoneSavedData::load, RtsZoneSavedData::new, DATA_NAME);
    }

    public static RtsZoneSavedData load(@NotNull final CompoundTag tag)
    {
        final RtsZoneSavedData data = new RtsZoneSavedData();
        final ListTag list = tag.getList(TAG_ZONES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            final RtsZoneData zone = RtsZoneData.fromNbt(list.getCompound(i));
            data.zones.put(zone.id(), zone);
        }
        return data;
    }

    @NotNull
    @Override
    public CompoundTag save(@NotNull final CompoundTag tag)
    {
        final ListTag list = new ListTag();
        synchronized (this)
        {
            for (final RtsZoneData zone : zones.values())
            {
                list.add(zone.toNbt());
            }
        }
        tag.put(TAG_ZONES, list);
        return tag;
    }

    public synchronized List<RtsZoneData> getAllZones()
    {
        return Collections.unmodifiableList(new ArrayList<>(zones.values()));
    }

    public synchronized List<RtsZoneData> getZones()
    {
        return getAllZones();
    }

    public synchronized List<RtsZoneData> getZonesForColony(final int colonyId)
    {
        if (colonyId <= 0)
        {
            return getAllZones();
        }
        return zones.values().stream()
            .filter(z -> z.colonyId() == colonyId || z.colonyId() == 0)
            .toList();
    }

    public synchronized Optional<RtsZoneData> getZoneAt(@Nullable final BlockPos pos)
    {
        if (pos == null)
        {
            return Optional.empty();
        }
        for (final RtsZoneData zone : zones.values())
        {
            if (zone.contains(pos))
            {
                return Optional.of(zone);
            }
        }
        return Optional.empty();
    }

    public synchronized RtsZoneData createZone(
        final int colonyId,
        @NotNull final RtsZoneType type,
        @NotNull final BlockPos firstCorner,
        @NotNull final BlockPos secondCorner,
        @Nullable final BlockPos linkedBuildingPos)
    {
        final RtsZoneData zone = RtsZoneData.fromCorners(colonyId, type, firstCorner, secondCorner, linkedBuildingPos);
        addOrReplaceZone(zone);
        return zone;
    }

    public synchronized void putZone(@NotNull final RtsZoneData zone)
    {
        Objects.requireNonNull(zone, "zone must not be null");
        zones.put(zone.id(), zone);
        revision++;
        setDirty();
    }

    public synchronized void addOrReplaceZone(@NotNull final RtsZoneData zone)
    {
        Objects.requireNonNull(zone, "zone must not be null");
        removeZonesIntersecting(zone.colonyId(), zone.bounds());
        putZone(zone);
    }

    public synchronized boolean removeZone(@Nullable final UUID zoneId)
    {
        if (zoneId == null)
        {
            return false;
        }
        if (zones.remove(zoneId) != null)
        {
            revision++;
            setDirty();
            return true;
        }
        return false;
    }

    public synchronized int removeZonesIntersecting(@Nullable final BoundingBox selectionBox)
    {
        return removeZonesIntersecting(0, selectionBox);
    }

    public synchronized int removeZonesIntersecting(final int colonyId, @Nullable final BoundingBox selectionBox)
    {
        if (selectionBox == null)
        {
            return 0;
        }
        final List<UUID> toRemove = zones.values().stream()
            .filter(z -> (colonyId <= 0 || z.colonyId() == colonyId || z.colonyId() == 0) && z.intersects(selectionBox))
            .map(RtsZoneData::id)
            .toList();
        int removed = 0;
        for (final UUID id : toRemove)
        {
            if (removeZone(id))
            {
                removed++;
            }
        }
        return removed;
    }

    private long cachedContainerRevision = -1L;
    private long cachedContainerScanTick = -1000L;
    private List<BlockPos> cachedStorageContainers = List.of();

    public synchronized long getRevision()
    {
        return revision;
    }

    /**
     * Returns cached container positions inside STORAGE zones, refreshing only when zones change or TTL (100 ticks) expires.
     */
    @NotNull
    public synchronized List<BlockPos> getCachedStorageContainers(@NotNull final ServerLevel level)
    {
        final long now = level.getGameTime();
        if (cachedContainerRevision == revision && (now - cachedContainerScanTick) < 100L)
        {
            return cachedStorageContainers;
        }
        final List<BlockPos> discovered = new ArrayList<>();
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (final RtsZoneData zone : zones.values())
        {
            if (zone.type() != RtsZoneType.STORAGE)
            {
                continue;
            }
            final BoundingBox box = zone.bounds();
            for (int x = box.minX(); x <= box.maxX(); x++)
            {
                for (int y = box.minY(); y <= box.maxY(); y++)
                {
                    for (int z = box.minZ(); z <= box.maxZ(); z++)
                    {
                        cursor.set(x, y, z);
                        if (!level.isLoaded(cursor))
                        {
                            continue;
                        }
                        final var be = level.getBlockEntity(cursor);
                        if (be instanceof com.minecolonies.core.tileentities.TileEntityRack || be instanceof net.minecraft.world.Container)
                        {
                            discovered.add(cursor.immutable());
                        }
                    }
                }
            }
        }
        cachedStorageContainers = Collections.unmodifiableList(discovered);
        cachedContainerRevision = revision;
        cachedContainerScanTick = now;
        return cachedStorageContainers;
    }
}
