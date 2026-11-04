package com.citiescolony.common.city;

import com.citiescolony.api.ColonyIds;
import com.citiescolony.api.ColonyTask;
import com.citiescolony.api.JobType;
import com.citiescolony.api.TaskState;
import com.citiescolony.api.ZoneData;
import com.citiescolony.api.ZoneType;
import com.citiescolony.common.entity.ColonyCitizenEntity;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Persistent world data manager for city zones, town hall & outpost coordinates,
 * wave defense state, active citizen set, and the O(1) spatially-indexed ColonyTask queue.
 */
public class CityZoneManager extends SavedData {

    public static final Codec<CityZoneManager> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ZoneData.CODEC.listOf().optionalFieldOf("zones", List.of()).forGetter(CityZoneManager::getZoneList),
            BlockPos.CODEC.optionalFieldOf("town_hall_pos").forGetter(CityZoneManager::getTownHallPos),
            ColonyTask.CODEC.listOf().optionalFieldOf("tasks", List.of()).forGetter(CityZoneManager::getTaskList),
            BlockPos.CODEC.listOf().optionalFieldOf("outposts", List.of()).forGetter(CityZoneManager::getOutpostList),
            Codec.LONG.optionalFieldOf("last_spawned_wave_day", -1L).forGetter(CityZoneManager::getLastSpawnedWaveDay),
            UUIDUtil.CODEC.listOf().optionalFieldOf("active_wave_mobs", List.of()).forGetter(CityZoneManager::getActiveWaveMobIds)
    ).apply(instance, CityZoneManager::new));

    public static final SavedDataType<CityZoneManager> TYPE = new SavedDataType<>(
            ColonyIds.id("city_zone_manager"),
            CityZoneManager::new,
            CODEC,
            null
    );

    private final Map<UUID, ZoneData> zones = new LinkedHashMap<>();
    private final Map<UUID, ColonyTask> taskQueue = new LinkedHashMap<>();
    /**
     * Spatial index mapping target BlockPos -> taskId for O(1) active task lookup.
     */
    private final Map<BlockPos, UUID> activeTaskByPos = new HashMap<>();
    /**
     * Zones awaiting their initial full scan; subsequent ticks use event-driven updates and bounded sampling.
     */
    private final Set<UUID> pendingInitialScanZones = new LinkedHashSet<>();
    private final Map<UUID, Integer> zoneSamplingCursors = new HashMap<>();

    private final Set<BlockPos> guardOutposts = new LinkedHashSet<>();
    private BlockPos townHallPos;
    private long zoneRevision = 0L;

    /**
     * World-scoped night wave state (replaces static fields to prevent cross-world pollution in singleplayer).
     */
    private long lastSpawnedWaveDay = -1L;
    private final List<UUID> activeWaveMobIds = new ArrayList<>();

    /**
     * Dedicated live citizen set so server ticks never call level.getAllEntities().
     */
    private final Set<ColonyCitizenEntity> activeCitizens = Collections.newSetFromMap(new WeakHashMap<>());

    public CityZoneManager() {
        this(List.of(), Optional.empty(), List.of(), List.of(), -1L, List.of());
    }

    public CityZoneManager(
            List<ZoneData> initialZones,
            Optional<BlockPos> townHallPos,
            List<ColonyTask> initialTasks
    ) {
        this(initialZones, townHallPos, initialTasks, List.of(), -1L, List.of());
    }

    public CityZoneManager(
            List<ZoneData> initialZones,
            Optional<BlockPos> townHallPos,
            List<ColonyTask> initialTasks,
            List<BlockPos> initialOutposts,
            long lastSpawnedWaveDay,
            List<UUID> initialWaveMobs
    ) {
        if (initialZones != null) {
            for (ZoneData zone : initialZones) {
                if (zone != null) {
                    this.zones.put(zone.id(), zone);
                    this.pendingInitialScanZones.add(zone.id());
                }
            }
        }
        this.townHallPos = townHallPos.map(BlockPos::immutable).orElse(null);
        if (initialTasks != null) {
            for (ColonyTask task : initialTasks) {
                if (task != null && !task.state().isTerminal()) {
                    this.taskQueue.put(task.taskId(), task);
                    this.activeTaskByPos.put(task.targetPos(), task.taskId());
                }
            }
        }
        if (initialOutposts != null) {
            for (BlockPos pos : initialOutposts) {
                if (pos != null) {
                    this.guardOutposts.add(pos.immutable());
                }
            }
        }
        this.lastSpawnedWaveDay = lastSpawnedWaveDay;
        if (initialWaveMobs != null) {
            this.activeWaveMobIds.addAll(initialWaveMobs);
        }
    }

    public static CityZoneManager get(ServerLevel level) {
        Objects.requireNonNull(level, "ServerLevel must not be null");
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    public synchronized void registerCitizen(ColonyCitizenEntity citizen) {
        if (citizen != null && citizen.isAlive()) {
            this.activeCitizens.add(citizen);
        }
    }

    public synchronized void unregisterCitizen(ColonyCitizenEntity citizen) {
        if (citizen != null) {
            this.activeCitizens.remove(citizen);
        }
    }

    public synchronized List<ColonyCitizenEntity> getActiveCitizens() {
        List<ColonyCitizenEntity> result = new ArrayList<>(this.activeCitizens.size());
        Iterator<ColonyCitizenEntity> it = this.activeCitizens.iterator();
        while (it.hasNext()) {
            ColonyCitizenEntity citizen = it.next();
            if (citizen == null || !citizen.isAlive() || citizen.isRemoved()) {
                it.remove();
            } else {
                result.add(citizen);
            }
        }
        return result;
    }

    public synchronized int countAliveCitizens() {
        return getActiveCitizens().size();
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
        this.pendingInitialScanZones.add(created.id());
        if (this.townHallPos == null && (type == ZoneType.RESIDENTIAL || type == ZoneType.STORAGE)) {
            this.townHallPos = created.center().immutable();
        }
        this.zoneRevision++;
        this.setDirty();
        return created;
    }

    public synchronized void putZone(ZoneData zone) {
        Objects.requireNonNull(zone, "zone must not be null");
        this.zones.put(zone.id(), zone);
        this.pendingInitialScanZones.add(zone.id());
        if (this.townHallPos == null && (zone.type() == ZoneType.RESIDENTIAL || zone.type() == ZoneType.STORAGE)) {
            this.townHallPos = zone.center().immutable();
        }
        this.zoneRevision++;
        this.setDirty();
    }

    public synchronized boolean consumeInitialScanFlag(UUID zoneId) {
        return zoneId != null && this.pendingInitialScanZones.remove(zoneId);
    }

    public synchronized void markZoneDirtyForScan(UUID zoneId) {
        if (zoneId != null && this.zones.containsKey(zoneId)) {
            this.pendingInitialScanZones.add(zoneId);
        }
    }

    public synchronized int getAndAdvanceSamplingCursor(UUID zoneId, int step, int maxVolume) {
        if (maxVolume <= 0) {
            return 0;
        }
        int current = this.zoneSamplingCursors.getOrDefault(zoneId, 0);
        int next = (current + Math.max(1, step)) % maxVolume;
        this.zoneSamplingCursors.put(zoneId, next);
        return current;
    }

    public synchronized boolean removeZone(UUID zoneId) {
        if (zoneId == null) {
            return false;
        }
        ZoneData removed = this.zones.remove(zoneId);
        if (removed != null) {
            this.pendingInitialScanZones.remove(zoneId);
            this.zoneSamplingCursors.remove(zoneId);
            Iterator<Map.Entry<UUID, ColonyTask>> it = this.taskQueue.entrySet().iterator();
            while (it.hasNext()) {
                ColonyTask task = it.next().getValue();
                if (removed.contains(task.targetPos())) {
                    this.activeTaskByPos.remove(task.targetPos(), task.taskId());
                    it.remove();
                }
            }
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

    public synchronized void registerGuardOutpost(BlockPos pos) {
        if (pos != null) {
            BlockPos immutable = pos.immutable();
            this.guardOutposts.add(immutable);
            this.setDirty();
        }
    }

    public synchronized void unregisterGuardOutpost(BlockPos pos) {
        if (pos != null && this.guardOutposts.remove(pos)) {
            this.setDirty();
        }
    }

    public synchronized List<BlockPos> getOutpostList() {
        return List.copyOf(this.guardOutposts);
    }

    public synchronized Optional<BlockPos> findNearestGuardOutpost(BlockPos origin) {
        if (this.guardOutposts.isEmpty()) {
            return Optional.empty();
        }
        if (origin == null) {
            return Optional.of(this.guardOutposts.iterator().next());
        }
        return this.guardOutposts.stream()
                .min(Comparator.comparingDouble(p -> p.distSqr(origin)));
    }

    public synchronized long getLastSpawnedWaveDay() {
        return this.lastSpawnedWaveDay;
    }

    public synchronized void setLastSpawnedWaveDay(long day) {
        this.lastSpawnedWaveDay = day;
        this.setDirty();
    }

    public synchronized List<UUID> getActiveWaveMobIds() {
        return List.copyOf(this.activeWaveMobIds);
    }

    public synchronized List<UUID> getMutableActiveWaveMobIds() {
        return this.activeWaveMobIds;
    }

    public synchronized void addActiveWaveMob(UUID mobId) {
        if (mobId != null && !this.activeWaveMobIds.contains(mobId)) {
            this.activeWaveMobIds.add(mobId);
            this.setDirty();
        }
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

    public synchronized Optional<ColonyTask> getTaskById(UUID taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.taskQueue.get(taskId));
    }

    /**
     * Rebinds a deserialized citizen task to the canonical instance stored inside taskQueue
     * so state transitions on the citizen's task are reflected in CityZoneManager.
     */
    public synchronized ColonyTask resolveCanonicalTask(ColonyTask task) {
        if (task == null) {
            return null;
        }
        ColonyTask existingById = this.taskQueue.get(task.taskId());
        if (existingById != null) {
            return existingById;
        }
        UUID existingAtPosId = this.activeTaskByPos.get(task.targetPos());
        if (existingAtPosId != null) {
            ColonyTask existingAtPos = this.taskQueue.get(existingAtPosId);
            if (existingAtPos != null && !existingAtPos.state().isTerminal()) {
                return existingAtPos;
            }
        }
        if (!task.state().isTerminal()) {
            this.taskQueue.put(task.taskId(), task);
            this.activeTaskByPos.put(task.targetPos(), task.taskId());
            this.setDirty();
        }
        return task;
    }

    /**
     * O(1) spatial index lookup checking whether an active task already exists at targetPos.
     */
    public synchronized boolean hasActiveTaskAt(BlockPos targetPos) {
        return hasActiveTaskAt(targetPos, null);
    }

    /**
     * O(1) spatial index lookup checking whether an active task (optionally of a specific type) exists at targetPos.
     */
    public synchronized boolean hasActiveTaskAt(BlockPos targetPos, ColonyTask.TaskType type) {
        if (targetPos == null) {
            return false;
        }
        UUID taskId = this.activeTaskByPos.get(targetPos);
        if (taskId == null) {
            return false;
        }
        ColonyTask task = this.taskQueue.get(taskId);
        if (task == null || task.state().isTerminal()) {
            this.activeTaskByPos.remove(targetPos);
            return false;
        }
        return type == null || task.type() == type;
    }

    public synchronized boolean enqueueTask(ColonyTask task) {
        Objects.requireNonNull(task, "task must not be null");
        if (hasActiveTaskAt(task.targetPos(), null)) {
            return false;
        }
        this.taskQueue.put(task.taskId(), task);
        this.activeTaskByPos.put(task.targetPos(), task.taskId());
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
            this.activeTaskByPos.remove(removed.targetPos(), taskId);
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
            this.activeTaskByPos.remove(removed.targetPos(), taskId);
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
        boolean removedAny = false;
        Iterator<Map.Entry<UUID, ColonyTask>> it = this.taskQueue.entrySet().iterator();
        while (it.hasNext()) {
            ColonyTask task = it.next().getValue();
            if (task.state().isTerminal()) {
                this.activeTaskByPos.remove(task.targetPos(), task.taskId());
                it.remove();
                removedAny = true;
            }
        }
        if (removedAny) {
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
