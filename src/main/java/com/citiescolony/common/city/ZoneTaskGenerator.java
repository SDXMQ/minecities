package com.citiescolony.common.city;

import com.citiescolony.api.ColonyTask;
import com.citiescolony.api.ZoneData;
import com.citiescolony.api.ZoneType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Optional;

/**
 * Generates ColonyTasks for designated zones using O(1) spatial indexing, initial one-time zone scans,
 * bounded periodic chunk sampling, and event-driven block updates.
 * - LOGGING: Detects logs inside the zone bounds -> CHOP tasks
 * - FARMING: Detects fully mature crops -> HARVEST tasks
 * - MINING: Detects topmost mineable blocks while preserving a staircase access ramp -> MINE tasks
 * - RESIDENTIAL: Builds complete floor first, then 3-block high walls (with doorway), then roof -> BUILD tasks
 * - STORAGE: Detects items in production drop-off chests or on the ground for COURIER bulk transport -> DELIVER tasks
 * - DEFENSE: Maintains active outpost guard tasks -> GUARD tasks
 */
public final class ZoneTaskGenerator {
    public static final int SCAN_INTERVAL_TICKS = 20;
    private static final int MAX_NEW_TASKS_PER_ZONE_SCAN = 24;
    private static final int MAX_INSPECTED_BLOCKS_PER_SAMPLE = 192;

    private ZoneTaskGenerator() {
    }

    public static void tick(ServerLevel level) {
        if (level.isClientSide() || level != level.getServer().overworld()) {
            return;
        }
        if ((level.getGameTime() % SCAN_INTERVAL_TICKS) != 0L) {
            return;
        }
        CityZoneManager manager = CityZoneManager.get(level);
        manager.pruneCompletedTasks();

        for (ZoneData zone : manager.getZones()) {
            switch (zone.type()) {
                case LOGGING -> scanLoggingZone(level, manager, zone);
                case FARMING -> scanFarmingZone(level, manager, zone);
                case MINING -> scanMiningZone(level, manager, zone);
                case RESIDENTIAL -> scanResidentialZone(level, manager, zone);
                case DEFENSE -> scanDefenseZone(level, manager, zone);
                case STORAGE -> scanStorageZone(level, manager, zone);
            }
        }
    }

    /**
     * Event-driven hook: immediately evaluates a single changed block position (crop growth, block break/place)
     * in O(1) without scanning the entire zone.
     */
    public static void onBlockStateChanged(ServerLevel level, BlockPos pos, BlockState newState) {
        if (level.isClientSide() || level != level.getServer().overworld() || pos == null) {
            return;
        }
        CityZoneManager manager = CityZoneManager.get(level);
        if (manager.hasActiveTaskAt(pos)) {
            return;
        }
        Optional<ZoneData> zoneOpt = manager.getZoneAt(pos);
        if (zoneOpt.isEmpty()) {
            return;
        }
        ZoneData zone = zoneOpt.get();
        switch (zone.type()) {
            case LOGGING -> {
                if (isLogBlock(newState)) {
                    int priority = ColonyTask.TaskType.CHOP.defaultPriority() + Math.max(0, zone.bounds().maxY() - pos.getY());
                    manager.enqueueTask(new ColonyTask(ColonyTask.TaskType.CHOP, pos.immutable(), priority));
                }
            }
            case FARMING -> {
                if (isMatureCrop(newState)) {
                    manager.enqueueTask(new ColonyTask(
                            ColonyTask.TaskType.HARVEST,
                            pos.immutable(),
                            ColonyTask.TaskType.HARVEST.defaultPriority()
                    ));
                }
            }
            case MINING -> {
                if (isMineableTopBlock(level, pos, newState) && isWithinStaircaseMiningConstraint(zone.bounds(), pos)) {
                    int priority = ColonyTask.TaskType.MINE.defaultPriority() + Math.max(0, pos.getY() - zone.bounds().minY());
                    manager.enqueueTask(new ColonyTask(ColonyTask.TaskType.MINE, pos.immutable(), priority));
                }
            }
            case RESIDENTIAL -> manager.markZoneDirtyForScan(zone.id());
            default -> {
            }
        }
    }

    public static int scanLoggingZone(ServerLevel level, CityZoneManager manager, ZoneData zone) {
        BoundingBox box = zone.bounds();
        boolean initialFullScan = manager.consumeInitialScanFlag(zone.id());
        int xSpan = box.getXSpan();
        int ySpan = box.getYSpan();
        int zSpan = box.getZSpan();
        int totalVolume = Math.max(1, xSpan * ySpan * zSpan);
        int inspectLimit = initialFullScan ? totalVolume : Math.min(totalVolume, MAX_INSPECTED_BLOCKS_PER_SAMPLE);
        int startIndex = initialFullScan ? 0 : manager.getAndAdvanceSamplingCursor(zone.id(), inspectLimit, totalVolume);

        int added = 0;
        int matched = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int i = 0; i < inspectLimit && added < MAX_NEW_TASKS_PER_ZONE_SCAN && matched < MAX_NEW_TASKS_PER_ZONE_SCAN; i++) {
            int idx = (startIndex + i) % totalVolume;
            int y = box.minY() + (idx / (xSpan * zSpan));
            int rem = idx % (xSpan * zSpan);
            int x = box.minX() + (rem / zSpan);
            int z = box.minZ() + (rem % zSpan);

            cursor.set(x, y, z);
            if (!level.isLoaded(cursor)) {
                continue;
            }
            BlockPos immutablePos = cursor.immutable();
            if (manager.hasActiveTaskAt(immutablePos)) {
                matched++;
                continue;
            }
            BlockState state = level.getBlockState(cursor);
            if (isLogBlock(state)) {
                matched++;
                int priority = ColonyTask.TaskType.CHOP.defaultPriority() + Math.max(0, box.maxY() - y);
                ColonyTask task = new ColonyTask(ColonyTask.TaskType.CHOP, immutablePos, priority);
                if (manager.enqueueTask(task)) {
                    added++;
                }
            }
        }
        return added;
    }

    public static int scanFarmingZone(ServerLevel level, CityZoneManager manager, ZoneData zone) {
        BoundingBox box = zone.bounds();
        boolean initialFullScan = manager.consumeInitialScanFlag(zone.id());
        int xSpan = box.getXSpan();
        int ySpan = box.getYSpan();
        int zSpan = box.getZSpan();
        int totalVolume = Math.max(1, xSpan * ySpan * zSpan);
        int inspectLimit = initialFullScan ? totalVolume : Math.min(totalVolume, MAX_INSPECTED_BLOCKS_PER_SAMPLE);
        int startIndex = initialFullScan ? 0 : manager.getAndAdvanceSamplingCursor(zone.id(), inspectLimit, totalVolume);

        int added = 0;
        int matched = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int i = 0; i < inspectLimit && added < MAX_NEW_TASKS_PER_ZONE_SCAN && matched < MAX_NEW_TASKS_PER_ZONE_SCAN; i++) {
            int idx = (startIndex + i) % totalVolume;
            int y = box.minY() + (idx / (xSpan * zSpan));
            int rem = idx % (xSpan * zSpan);
            int x = box.minX() + (rem / zSpan);
            int z = box.minZ() + (rem % zSpan);

            cursor.set(x, y, z);
            if (!level.isLoaded(cursor)) {
                continue;
            }
            BlockPos immutablePos = cursor.immutable();
            if (manager.hasActiveTaskAt(immutablePos)) {
                matched++;
                continue;
            }
            BlockState state = level.getBlockState(cursor);
            if (isMatureCrop(state)) {
                matched++;
                ColonyTask task = new ColonyTask(
                        ColonyTask.TaskType.HARVEST,
                        immutablePos,
                        ColonyTask.TaskType.HARVEST.defaultPriority()
                );
                if (manager.enqueueTask(task)) {
                    added++;
                }
            }
        }
        return added;
    }

    /**
     * Scans MINING zones from top to bottom while enforcing a stepped staircase ramp along the X axis
     * (from minX down toward maxX) and capping pit depth so miners never dig themselves into an inescapable hole.
     */
    public static int scanMiningZone(ServerLevel level, CityZoneManager manager, ZoneData zone) {
        BoundingBox box = zone.bounds();
        int added = 0;
        int matched = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = box.minX(); x <= box.maxX() && added < MAX_NEW_TASKS_PER_ZONE_SCAN && matched < MAX_NEW_TASKS_PER_ZONE_SCAN; x++) {
            for (int z = box.minZ(); z <= box.maxZ() && added < MAX_NEW_TASKS_PER_ZONE_SCAN && matched < MAX_NEW_TASKS_PER_ZONE_SCAN; z++) {
                int minAllowedY = computeMinAllowedMiningY(box, x, z);
                for (int y = box.maxY(); y >= minAllowedY; y--) {
                    cursor.set(x, y, z);
                    if (!level.isLoaded(cursor)) {
                        break;
                    }
                    BlockPos immutablePos = cursor.immutable();
                    if (manager.hasActiveTaskAt(immutablePos)) {
                        matched++;
                        break;
                    }
                    BlockState state = level.getBlockState(cursor);
                    if (isMineableTopBlock(level, cursor, state)) {
                        matched++;
                        int priority = ColonyTask.TaskType.MINE.defaultPriority() + Math.max(0, y - box.minY());
                        ColonyTask task = new ColonyTask(ColonyTask.TaskType.MINE, immutablePos, priority);
                        if (manager.enqueueTask(task)) {
                            added++;
                        }
                        break;
                    }
                }
            }
        }
        return added;
    }

    public static boolean isWithinStaircaseMiningConstraint(BoundingBox box, BlockPos pos) {
        return pos.getY() >= computeMinAllowedMiningY(box, pos.getX(), pos.getZ());
    }

    private static int computeMinAllowedMiningY(BoundingBox box, int x, int z) {
        // Reserve a 1-block-per-step staircase along the northern edge (z == box.minZ()) and cap depth
        // so miners can always walk out and stay within vertical reach.
        int stepFromEntry = Math.max(0, x - box.minX());
        int staircaseFloorY = box.maxY() - stepFromEntry;
        if (z == box.minZ()) {
            return Math.max(box.minY(), staircaseFloorY);
        }
        // Interior columns can go down to the staircase level at their X coordinate, preventing sheer cliffs
        return Math.max(box.minY(), staircaseFloorY);
    }

    /**
     * Progressive multi-stage residential construction:
     * Stage 1: Complete the entire foundation floor (floorY, including borders) so walls never float in mid-air.
     * Stage 2: Build 3-block tall perimeter walls (floorY + 1 to floorY + 3), leaving a 2-block high doorway.
     * Stage 3: Cap the house with a roof at floorY + 4 (bounded by box.maxY()).
     */
    public static int scanResidentialZone(ServerLevel level, CityZoneManager manager, ZoneData zone) {
        BoundingBox box = zone.bounds();
        int added = 0;
        int floorY = box.minY();
        int wallTopY = Math.min(box.maxY(), floorY + 3);
        int roofY = Math.min(box.maxY(), floorY + 4);
        int doorX = (box.minX() + box.maxX()) / 2;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // Stage 1: Foundation floor across the entire footprint (including border coordinates)
        boolean floorComplete = true;
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                cursor.set(x, floorY, z);
                if (!level.isLoaded(cursor)) {
                    continue;
                }
                BlockState state = level.getBlockState(cursor);
                if (state.isAir() || state.canBeReplaced()) {
                    floorComplete = false;
                    if (added < 8) {
                        BlockPos target = cursor.immutable();
                        if (!manager.hasActiveTaskAt(target)) {
                            ColonyTask task = new ColonyTask(
                                    ColonyTask.TaskType.BUILD,
                                    target,
                                    ColonyTask.TaskType.BUILD.defaultPriority() + 20
                            );
                            if (manager.enqueueTask(task)) {
                                added++;
                            }
                        }
                    }
                }
            }
        }
        if (!floorComplete) {
            return added;
        }

        // Stage 2: 3-block high perimeter walls (floorY + 1 .. wallTopY), skipping 2-block high doorway
        boolean wallsComplete = true;
        for (int y = floorY + 1; y <= wallTopY; y++) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    boolean isBorder = (x == box.minX() || x == box.maxX() || z == box.minZ() || z == box.maxZ());
                    if (!isBorder) {
                        continue;
                    }
                    boolean isDoorway = (x == doorX && z == box.minZ() && y <= floorY + 2);
                    if (isDoorway) {
                        continue;
                    }
                    cursor.set(x, y, z);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir() || state.canBeReplaced()) {
                        wallsComplete = false;
                        if (added < 8) {
                            BlockPos target = cursor.immutable();
                            if (!manager.hasActiveTaskAt(target)) {
                                int priority = ColonyTask.TaskType.BUILD.defaultPriority() + Math.max(0, 10 - (y - floorY));
                                ColonyTask task = new ColonyTask(ColonyTask.TaskType.BUILD, target, priority);
                                if (manager.enqueueTask(task)) {
                                    added++;
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!wallsComplete || roofY <= wallTopY) {
            return added;
        }

        // Stage 3: Roof layer at roofY (floorY + 4)
        for (int x = box.minX(); x <= box.maxX() && added < 8; x++) {
            for (int z = box.minZ(); z <= box.maxZ() && added < 8; z++) {
                cursor.set(x, roofY, z);
                if (!level.isLoaded(cursor)) {
                    continue;
                }
                BlockState state = level.getBlockState(cursor);
                if (state.isAir() || state.canBeReplaced()) {
                    BlockPos target = cursor.immutable();
                    if (!manager.hasActiveTaskAt(target)) {
                        ColonyTask task = new ColonyTask(
                                ColonyTask.TaskType.BUILD,
                                target,
                                ColonyTask.TaskType.BUILD.defaultPriority()
                        );
                        if (manager.enqueueTask(task)) {
                            added++;
                        }
                    }
                }
            }
        }
        return added;
    }

    /**
     * Generates DELIVER tasks for COURIER citizens:
     * 1) Inspects production drop-off chests (in/around LOGGING, MINING, FARMING zones) that contain items
     *    and queues bulk pickup & transport tasks to STORAGE zones.
     * 2) Inspects loose ItemEntities dropped inside production zones so couriers collect them and deposit into STORAGE.
     */
    public static int scanStorageZone(ServerLevel level, CityZoneManager manager, ZoneData storageZone) {
        if (StorageLogisticsHelper.getCachedChestPositions(level, manager).isEmpty()) {
            return 0;
        }
        int added = 0;

        for (BlockPos dropOffPos : StorageLogisticsHelper.getCachedDropOffPositions(level, manager)) {
            if (added >= MAX_NEW_TASKS_PER_ZONE_SCAN) {
                break;
            }
            if (manager.hasActiveTaskAt(dropOffPos)) {
                continue;
            }
            Optional<Container> containerOpt = StorageLogisticsHelper.getStorageContainerAt(level, dropOffPos);
            if (containerOpt.isPresent() && StorageLogisticsHelper.hasAnyNonEmptyItem(containerOpt.get())) {
                ColonyTask deliverTask = new ColonyTask(
                        ColonyTask.TaskType.DELIVER,
                        dropOffPos.immutable(),
                        ColonyTask.TaskType.DELIVER.defaultPriority() + 10
                );
                if (manager.enqueueTask(deliverTask)) {
                    added++;
                }
            }
        }

        for (ZoneData prodZone : manager.getZones()) {
            if (added >= MAX_NEW_TASKS_PER_ZONE_SCAN) {
                break;
            }
            ZoneType type = prodZone.type();
            if (type != ZoneType.LOGGING && type != ZoneType.MINING && type != ZoneType.FARMING) {
                continue;
            }
            BoundingBox b = prodZone.bounds();
            AABB searchBox = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1);
            List<ItemEntity> droppedItems = level.getEntitiesOfClass(
                    ItemEntity.class,
                    searchBox,
                    item -> item.isAlive() && !item.getItem().isEmpty()
            );
            for (ItemEntity itemEntity : droppedItems) {
                if (added >= MAX_NEW_TASKS_PER_ZONE_SCAN) {
                    break;
                }
                BlockPos itemPos = itemEntity.blockPosition().immutable();
                if (!manager.hasActiveTaskAt(itemPos)) {
                    ColonyTask deliverTask = new ColonyTask(
                            ColonyTask.TaskType.DELIVER,
                            itemPos,
                            ColonyTask.TaskType.DELIVER.defaultPriority()
                    );
                    if (manager.enqueueTask(deliverTask)) {
                        added++;
                    }
                }
            }
        }
        return added;
    }

    public static int scanDefenseZone(ServerLevel level, CityZoneManager manager, ZoneData zone) {
        BlockPos outpostCenter = manager.findNearestGuardOutpost(zone.center()).orElse(zone.center());
        if (!level.isLoaded(outpostCenter) || manager.hasActiveTaskAt(outpostCenter)) {
            return 0;
        }
        ColonyTask guardTask = new ColonyTask(
                ColonyTask.TaskType.GUARD,
                outpostCenter,
                ColonyTask.TaskType.GUARD.defaultPriority()
        );
        return manager.enqueueTask(guardTask) ? 1 : 0;
    }

    public static boolean isLogBlock(BlockState state) {
        return state != null && state.is(BlockTags.LOGS);
    }

    public static boolean isMatureCrop(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        if (state.getBlock() instanceof CropBlock cropBlock) {
            return cropBlock.isMaxAge(state);
        }
        if (state.getBlock() instanceof SweetBerryBushBlock) {
            return state.getValue(SweetBerryBushBlock.AGE) >= SweetBerryBushBlock.MAX_AGE;
        }
        return state.is(Blocks.MELON) || state.is(Blocks.PUMPKIN);
    }

    public static boolean isMineableTopBlock(ServerLevel level, BlockPos pos, BlockState state) {
        if (state == null || state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER)) {
            return false;
        }
        return state.getDestroySpeed(level, pos) >= 0.0f;
    }
}
