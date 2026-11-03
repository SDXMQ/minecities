package com.citiescolony.common.city;

import com.citiescolony.api.ColonyIds;
import com.citiescolony.api.ColonyTask;
import com.citiescolony.api.JobType;
import com.citiescolony.api.TaskState;
import com.citiescolony.api.ZoneData;
import com.citiescolony.api.ZoneType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistent world data manager for city zones, town hall center coordinates,
 * and the active ColonyTask queue, built on Minecraft 26.2 SavedDataType + Codec.
 */
public class CityZoneManager extends SavedData {

    public static final Codec<CityZoneManager> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ZoneData.CODEC.listOf().optionalFieldOf("zones", List.of()).forGetter(CityZoneManager::getZoneList),
            BlockPos.CODEC.optionalFieldOf("town_hall_pos").forGetter(CityZoneManager::getTownHallPos),
            ColonyTask.CODEC.listOf().optionalFieldOf("tasks", List.of()).forGetter(CityZoneManager::getTaskList)
    ).apply(instance, CityZoneManager::new));

    public static final SavedDataType<CityZoneManager> TYPE = new SavedDataType<>(
            ColonyIds.id("city_zone_manager"),
            CityZoneManager::new,
            CODEC,
            null
    );

    private final Map<UUID, ZoneData> zones = new LinkedHashMap<>();
    private final Map<UUID, ColonyTask> taskQueue = new LinkedHashMap<>();
    private BlockPos townHallPos;
    private long zoneRevision = 0L;

    public CityZoneManager() {
        this(List.of(), Optional.empty(), List.of());
    }

    public CityZoneManager(
            List<ZoneData> initialZones,
            Optional<BlockPos> townHallPos,
            List<ColonyTask> initialTasks
    ) {
        if (initialZones != null) {
            for (ZoneData zone : initialZones) {
                if (zone != null) {
                    this.zones.put(zone.id(), zone);
                }
            }
        }
        this.townHallPos = townHallPos.map(BlockPos::immutable).orElse(null);
        if (initialTasks != null) {
            for (ColonyTask task : initialTasks) {
                if (task != null && !task.state().isTerminal()) {
                    this.taskQueue.put(task.taskId(), task);
                }
            }
        }
    }

    public static CityZoneManager get(ServerLevel level) {
        Objects.requireNonNull(level, "ServerLevel must not be null");
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    public synchronized List<ZoneData> getZoneList() {
        return List.copyOf(this.zones.values());
    }

    public synchronized Collection<ZoneData> getZones() {
        return Collections.unmodifiableCollection(new ArrayList<>(this.zones.values()));
    }

    public synchronized List<ZoneData> getZonesByType(ZoneType type) {
        if (type == null) {
            return List.of();
        }
        return this.zones.values().stream()
                .filter(zone -> zone.type() == type)
                .toList();
    }

    public synchronized Optional<ZoneData> getZone(UUID zoneId) {
        if (zoneId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.zones.get(zoneId));
    }

    public synchronized Optional<ZoneData> getZoneAt(BlockPos pos) {
        if (pos == null) {
            return Optional.empty();
        }
        for (ZoneData zone : this.zones.values()) {
            if (zone.contains(pos)) {
                return Optional.of(zone);
            }
        }
        return Optional.empty();
    }

    public synchronized ZoneData createZone(ZoneType type, BlockPos firstCorner, BlockPos secondCorner) {
        ZoneData created = ZoneData.fromCorners(type, firstCorner, secondCorner);
        this.zones.put(created.id(), created);
        if (this.townHallPos == null) {
            this.townHallPos = created.center().immutable();
        }
        this.zoneRevision++;
        this.setDirty();
        return created;
    }

    public synchronized void putZone(ZoneData zone) {
        Objects.requireNonNull(zone, "zone must not be null");
        this.zones.put(zone.id(), zone);
        if (this.townHallPos == null) {
            this.townHallPos = zone.center().immutable();
        }
        this.zoneRevision++;
        this.setDirty();
    }

    public synchronized boolean removeZone(UUID zoneId) {
        if (zoneId == null) {
            return false;
        }
        ZoneData removed = this.zones.remove(zoneId);
        if (removed != null) {
            this.taskQueue.values().removeIf(task -> removed.contains(task.targetPos()));
            this.zoneRevision++;
            this.setDirty();
            return true;
        }
        return false;
    }

    public synchronized int removeZonesIntersecting(BoundingBox selectionBox) {
        if (selectionBox == null) {
            return 0;
        }
        List<UUID> toRemove = this.zones.values().stream()
                .filter(zone -> zone.intersects(selectionBox))
                .map(ZoneData::id)
                .toList();
        int removedCount = 0;
        for (UUID id : toRemove) {
            if (removeZone(id)) {
                removedCount++;
            }
        }
        return removedCount;
    }

    public synchronized Optional<BlockPos> getTownHallPos() {
        return Optional.ofNullable(this.townHallPos);
    }

    public synchronized void setTownHallPos(BlockPos pos) {
        this.townHallPos = pos == null ? null : pos.immutable();
        this.setDirty();
    }

    public synchronized boolean assignCitizenToZone(UUID zoneId, UUID citizenId) {
        if (zoneId == null || citizenId == null) {
            return false;
        }
        ZoneData current = this.zones.get(zoneId);
        if (current == null) {
            return false;
        }
        unassignCitizen(citizenId);
        ZoneData updated = this.zones.get(zoneId).withAssignedCitizen(citizenId);
        this.zones.put(zoneId, updated);
        this.zoneRevision++;
        this.setDirty();
        return true;
    }

    public synchronized void unassignCitizen(UUID citizenId) {
        if (citizenId == null) {
            return;
        }
        boolean modified = false;
        for (Map.Entry<UUID, ZoneData> entry : new ArrayList<>(this.zones.entrySet())) {
            ZoneData zone = entry.getValue();
            if (zone.assignedCitizens().contains(citizenId)) {
                this.zones.put(entry.getKey(), zone.withoutAssignedCitizen(citizenId));
                modified = true;
            }
        }
        if (modified) {
            this.zoneRevision++;
            this.setDirty();
        }
    }

    public synchronized long getZoneRevision() {
        return this.zoneRevision;
    }

    public synchronized List<ColonyTask> getTaskList() {
        return this.taskQueue.values().stream()
                .filter(task -> !task.state().isTerminal())
                .toList();
    }

    public synchronized boolean hasActiveTaskAt(BlockPos targetPos, ColonyTask.TaskType type) {
        if (targetPos == null) {
            return false;
        }
        for (ColonyTask task : this.taskQueue.values()) {
            if (!task.state().isTerminal()
                    && task.targetPos().equals(targetPos)
                    && (type == null || task.type() == type)) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean enqueueTask(ColonyTask task) {
        Objects.requireNonNull(task, "task must not be null");
        if (hasActiveTaskAt(task.targetPos(), task.type())) {
            return false;
        }
        this.taskQueue.put(task.taskId(), task);
        this.setDirty();
        return true;
    }

    public synchronized Optional<ZoneData> getAssignedZoneForCitizen(UUID citizenId) {
        if (citizenId == null) {
            return Optional.empty();
        }
        for (ZoneData zone : this.zones.values()) {
            if (zone.assignedCitizens().contains(citizenId)) {
                return Optional.of(zone);
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<ColonyTask> pollNextTaskFor(JobType jobType, UUID citizenId, BlockPos citizenPos) {
        if (jobType == null || jobType == JobType.NONE || citizenId == null) {
            return Optional.empty();
        }
        Optional<ColonyTask> existingAssigned = this.taskQueue.values().stream()
                .filter(task -> (task.state() == TaskState.ASSIGNED || task.state() == TaskState.IN_PROGRESS)
                        && citizenId.equals(task.assignedCitizenId().orElse(null))
                        && jobType.canExecute(task.type()))
                .findFirst();
        if (existingAssigned.isPresent()) {
            return existingAssigned;
        }

        Optional<ZoneData> assignedZoneOpt = getAssignedZoneForCitizen(citizenId);
        Comparator<ColonyTask> comparator = Comparator.comparingInt((ColonyTask task) ->
                assignedZoneOpt.map(zone -> zone.contains(task.targetPos()) ? 0 : 1).orElse(0)
        ).thenComparing(Comparator.comparingInt(ColonyTask::priority).reversed());

        if (citizenPos != null) {
            comparator = comparator.thenComparingDouble(task -> task.targetPos().distSqr(citizenPos));
        }

        Optional<ColonyTask> candidate = this.taskQueue.values().stream()
                .filter(task -> task.state() == TaskState.PENDING && jobType.canExecute(task.type()))
                .sorted(comparator)
                .findFirst();

        candidate.ifPresent(task -> {
            if (task.assignTo(citizenId)) {
                this.setDirty();
            }
        });
        return candidate;
    }

    public synchronized void completeTask(UUID taskId) {
        if (taskId == null) {
            return;
        }
        ColonyTask removed = this.taskQueue.remove(taskId);
        if (removed != null) {
            removed.markCompleted();
            this.setDirty();
        }
    }

    public synchronized void cancelTask(UUID taskId) {
        if (taskId == null) {
            return;
        }
        ColonyTask removed = this.taskQueue.remove(taskId);
        if (removed != null) {
            removed.markCancelled();
            this.setDirty();
        }
    }

    public synchronized void releaseCitizenTasks(UUID citizenId) {
        if (citizenId == null) {
            return;
        }
        boolean changed = false;
        for (ColonyTask task : this.taskQueue.values()) {
            if (citizenId.equals(task.assignedCitizenId().orElse(null)) && !task.state().isTerminal()) {
                task.resetToPending();
                changed = true;
            }
        }
        if (changed) {
            this.setDirty();
        }
    }

    public synchronized void pruneCompletedTasks() {
        if (this.taskQueue.values().removeIf(task -> task.state().isTerminal())) {
            this.setDirty();
        }
    }

    public synchronized BoundingBox computeCityEnvelope() {
        if (this.zones.isEmpty()) {
            BlockPos center = this.townHallPos != null ? this.townHallPos : BlockPos.ZERO;
            return new BoundingBox(
                    center.getX() - 16, center.getY() - 4, center.getZ() - 16,
                    center.getX() + 16, center.getY() + 8, center.getZ() + 16
            );
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (ZoneData zone : this.zones.values()) {
            BoundingBox b = zone.bounds();
            minX = Math.min(minX, b.minX());
            minY = Math.min(minY, b.minY());
            minZ = Math.min(minZ, b.minZ());
            maxX = Math.max(maxX, b.maxX());
            maxY = Math.max(maxY, b.maxY());
            maxZ = Math.max(maxZ, b.maxZ());
        }
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
