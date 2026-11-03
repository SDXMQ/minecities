package com.citiescolony.common.city;

import com.citiescolony.api.ColonyTask;
import com.citiescolony.api.ZoneData;
import com.citiescolony.api.ZoneType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Periodically scans all designated zones every 20 ticks and populates the ColonyTask queue.
 * - LOGGING: Detects logs inside the zone bounds -> CHOP tasks
 * - FARMING: Detects fully mature crops -> HARVEST tasks
 * - MINING: Detects the topmost non-air mineable block per (X, Z) column -> MINE tasks
 * - RESIDENTIAL: Detects incomplete housing perimeter floor/walls -> BUILD tasks
 * - DEFENSE: Maintains active outpost guard tasks -> GUARD tasks
 */
public final class ZoneTaskGenerator {
    public static final int SCAN_INTERVAL_TICKS = 20;
    private static final int MAX_NEW_TASKS_PER_ZONE_SCAN = 24;

    private ZoneTaskGenerator() {
    }

    public static void tick(ServerLevel level) {
        if (level.isClientSide() || (level.getGameTime() % SCAN_INTERVAL_TICKS) != 0L) {
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
                case STORAGE -> {
                    // Storage zones are managed by StorageLogisticsHelper
                }
            }
        }
    }

    public static int scanLoggingZone(ServerLevel level, CityZoneManager manager, ZoneData zone) {
        BoundingBox box = zone.bounds();
        int added = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int y = box.minY(); y <= box.maxY() && added < MAX_NEW_TASKS_PER_ZONE_SCAN; y++) {
            for (int x = box.minX(); x <= box.maxX() && added < MAX_NEW_TASKS_PER_ZONE_SCAN; x++) {
                for (int z = box.minZ(); z <= box.maxZ() && added < MAX_NEW_TASKS_PER_ZONE_SCAN; z++) {
                    cursor.set(x, y, z);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(cursor);
                    if (isLogBlock(state)) {
                        int priority = ColonyTask.TaskType.CHOP.defaultPriority() + Math.max(0, box.maxY() - y);
                        ColonyTask task = new ColonyTask(ColonyTask.TaskType.CHOP, cursor.immutable(), priority);
                        if (manager.enqueueTask(task)) {
                            added++;
                        }
                    }
                }
            }
        }
        return added;
    }

    public static int scanFarmingZone(ServerLevel level, CityZoneManager manager, ZoneData zone) {
        BoundingBox box = zone.bounds();
        int added = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int y = box.minY(); y <= box.maxY() && added < MAX_NEW_TASKS_PER_ZONE_SCAN; y++) {
            for (int x = box.minX(); x <= box.maxX() && added < MAX_NEW_TASKS_PER_ZONE_SCAN; x++) {
                for (int z = box.minZ(); z <= box.maxZ() && added < MAX_NEW_TASKS_PER_ZONE_SCAN; z++) {
                    cursor.set(x, y, z);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(cursor);
                    if (isMatureCrop(state)) {
                        ColonyTask task = new ColonyTask(
                                ColonyTask.TaskType.HARVEST,
                                cursor.immutable(),
                                ColonyTask.TaskType.HARVEST.defaultPriority()
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

    public static int scanMiningZone(ServerLevel level, CityZoneManager manager, ZoneData zone) {
        BoundingBox box = zone.bounds();
        int added = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = box.minX(); x <= box.maxX() && added < MAX_NEW_TASKS_PER_ZONE_SCAN; x++) {
            for (int z = box.minZ(); z <= box.maxZ() && added < MAX_NEW_TASKS_PER_ZONE_SCAN; z++) {
                for (int y = box.maxY(); y >= box.minY(); y--) {
                    cursor.set(x, y, z);
                    if (!level.isLoaded(cursor)) {
                        break;
                    }
                    BlockState state = level.getBlockState(cursor);
                    if (isMineableTopBlock(level, cursor, state)) {
                        int priority = ColonyTask.TaskType.MINE.defaultPriority() + Math.max(0, y - box.minY());
                        ColonyTask task = new ColonyTask(ColonyTask.TaskType.MINE, cursor.immutable(), priority);
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

    public static int scanResidentialZone(ServerLevel level, CityZoneManager manager, ZoneData zone) {
        BoundingBox box = zone.bounds();
        int added = 0;
        int floorY = box.minY();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = box.minX(); x <= box.maxX() && added < 8; x++) {
            for (int z = box.minZ(); z <= box.maxZ() && added < 8; z++) {
                boolean isBorder = (x == box.minX() || x == box.maxX() || z == box.minZ() || z == box.maxZ());
                boolean isDoorway = (x == (box.minX() + box.maxX()) / 2 && z == box.minZ());
                int targetY = isBorder && !isDoorway ? Math.min(box.maxY(), floorY + 1) : floorY;
                cursor.set(x, targetY, z);
                if (!level.isLoaded(cursor)) {
                    continue;
                }
                BlockState state = level.getBlockState(cursor);
                if (state.isAir() || state.canBeReplaced()) {
                    ColonyTask task = new ColonyTask(
                            ColonyTask.TaskType.BUILD,
                            cursor.immutable(),
                            ColonyTask.TaskType.BUILD.defaultPriority()
                    );
                    if (manager.enqueueTask(task)) {
                        added++;
                    }
                }
            }
        }
        return added;
    }

    public static int scanDefenseZone(ServerLevel level, CityZoneManager manager, ZoneData zone) {
        BlockPos outpostCenter = zone.center();
        if (!level.isLoaded(outpostCenter)) {
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
