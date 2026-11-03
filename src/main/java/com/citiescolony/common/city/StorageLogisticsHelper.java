package com.citiescolony.common.city;

import com.citiescolony.api.ZoneData;
import com.citiescolony.api.ZoneType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Caches storage container positions inside STORAGE zones and handles automated
 * item deposits, equipment/material withdrawals, and city-wide resource accounting.
 */
public final class StorageLogisticsHelper {

    public record ResourceSummary(int food, int wood, int stone, int minerals) {
        public static final ResourceSummary EMPTY = new ResourceSummary(0, 0, 0, 0);
    }

    private record CachedStorageState(long zoneRevision, long lastScanTick, List<BlockPos> chestPositions) {
    }

    private static final long CACHE_TTL_TICKS = 100L;
    private static final Map<String, CachedStorageState> LEVEL_CHEST_CACHE = new ConcurrentHashMap<>();

    private StorageLogisticsHelper() {
    }

    public static List<BlockPos> getCachedChestPositions(ServerLevel level, CityZoneManager manager) {
        String key = level.dimension().identifier().toString();
        long now = level.getGameTime();
        long revision = manager.getZoneRevision();

        CachedStorageState cached = LEVEL_CHEST_CACHE.get(key);
        if (cached != null
                && cached.zoneRevision() == revision
                && (now - cached.lastScanTick()) < CACHE_TTL_TICKS) {
            return cached.chestPositions();
        }
        return refreshStorageCache(level, manager);
    }

    public static List<BlockPos> refreshStorageCache(ServerLevel level, CityZoneManager manager) {
        Set<BlockPos> discovered = new LinkedHashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (ZoneData zone : manager.getZonesByType(ZoneType.STORAGE)) {
            BoundingBox box = zone.bounds();
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        cursor.set(x, y, z);
                        if (!level.isLoaded(cursor)) {
                            continue;
                        }
                        if (getStorageContainerAt(level, cursor).isPresent()) {
                            discovered.add(cursor.immutable());
                        }
                    }
                }
            }
        }

        List<BlockPos> immutableList = Collections.unmodifiableList(new ArrayList<>(discovered));
        String key = level.dimension().identifier().toString();
        LEVEL_CHEST_CACHE.put(key, new CachedStorageState(manager.getZoneRevision(), level.getGameTime(), immutableList));
        return immutableList;
    }

    public static Optional<Container> getStorageContainerAt(ServerLevel level, BlockPos pos) {
        if (pos == null || !level.isLoaded(pos)) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            Container chestContainer = ChestBlock.getContainer(chestBlock, state, level, pos, true);
            if (chestContainer != null) {
                return Optional.of(chestContainer);
            }
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof ChestBlockEntity || blockEntity instanceof BarrelBlockEntity) {
            return Optional.of((Container) blockEntity);
        }
        if (blockEntity instanceof Container genericContainer) {
            return Optional.of(genericContainer);
        }
        return Optional.empty();
    }

    public static Optional<BlockPos> findNearestStorageWithSpace(
            ServerLevel level,
            CityZoneManager manager,
            BlockPos origin,
            ItemStack sampleStack
    ) {
        List<BlockPos> chests = getCachedChestPositions(level, manager);
        BlockPos bestPos = null;
        double bestDistSqr = Double.MAX_VALUE;

        for (BlockPos pos : chests) {
            Optional<Container> containerOpt = getStorageContainerAt(level, pos);
            if (containerOpt.isEmpty()) {
                continue;
            }
            if (hasSpaceFor(containerOpt.get(), sampleStack)) {
                double dist = origin == null ? 0.0D : pos.distSqr(origin);
                if (dist < bestDistSqr) {
                    bestDistSqr = dist;
                    bestPos = pos;
                }
            }
        }
        return Optional.ofNullable(bestPos);
    }

    public static boolean hasSpaceFor(Container container, ItemStack stack) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack inSlot = container.getItem(i);
            if (inSlot.isEmpty()) {
                return true;
            }
            if (!stack.isEmpty()
                    && ItemStack.isSameItemSameComponents(inSlot, stack)
                    && inSlot.getCount() < Math.min(inSlot.getMaxStackSize(), container.getMaxStackSize())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Deposits the given ItemStack into any available chest inside STORAGE zones.
     *
     * @return the remainder ItemStack that could not fit (ItemStack.EMPTY if fully deposited)
     */
    public static ItemStack depositStack(ServerLevel level, CityZoneManager manager, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = stack.copy();
        List<BlockPos> chests = getCachedChestPositions(level, manager);

        for (BlockPos pos : chests) {
            if (remaining.isEmpty()) {
                break;
            }
            Optional<Container> containerOpt = getStorageContainerAt(level, pos);
            if (containerOpt.isEmpty()) {
                continue;
            }
            Container container = containerOpt.get();
            remaining = insertIntoContainer(container, remaining);
        }
        return remaining;
    }

    public static ItemStack insertIntoContainer(Container container, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = stack.copy();
        boolean changed = false;

        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slotStack = container.getItem(i);
            if (!slotStack.isEmpty() && ItemStack.isSameItemSameComponents(slotStack, remaining)) {
                int maxCap = Math.min(slotStack.getMaxStackSize(), container.getMaxStackSize());
                int canAdd = maxCap - slotStack.getCount();
                if (canAdd > 0) {
                    int move = Math.min(canAdd, remaining.getCount());
                    slotStack.grow(move);
                    remaining.shrink(move);
                    container.setItem(i, slotStack);
                    changed = true;
                }
            }
        }

        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slotStack = container.getItem(i);
            if (slotStack.isEmpty()) {
                int maxCap = Math.min(remaining.getMaxStackSize(), container.getMaxStackSize());
                int move = Math.min(maxCap, remaining.getCount());
                ItemStack placed = remaining.copyWithCount(move);
                remaining.shrink(move);
                container.setItem(i, placed);
                changed = true;
            }
        }

        if (changed) {
            container.setChanged();
        }
        return remaining.isEmpty() ? ItemStack.EMPTY : remaining;
    }

    /**
     * Withdraws up to maxCount items matching the predicate from cached STORAGE zone containers.
     */
    public static ItemStack extractMatchingItem(
            ServerLevel level,
            CityZoneManager manager,
            Predicate<ItemStack> predicate,
            int maxCount
    ) {
        if (predicate == null || maxCount <= 0) {
            return ItemStack.EMPTY;
        }
        List<BlockPos> chests = getCachedChestPositions(level, manager);
        ItemStack extracted = ItemStack.EMPTY;

        for (BlockPos pos : chests) {
            Optional<Container> containerOpt = getStorageContainerAt(level, pos);
            if (containerOpt.isEmpty()) {
                continue;
            }
            Container container = containerOpt.get();
            boolean changed = false;

            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack slotStack = container.getItem(i);
                if (slotStack.isEmpty() || !predicate.test(slotStack)) {
                    continue;
                }
                if (extracted.isEmpty()) {
                    int take = Math.min(maxCount, slotStack.getCount());
                    extracted = slotStack.split(take);
                    changed = true;
                } else if (ItemStack.isSameItemSameComponents(extracted, slotStack)) {
                    int needed = Math.min(maxCount, extracted.getMaxStackSize()) - extracted.getCount();
                    if (needed > 0) {
                        int take = Math.min(needed, slotStack.getCount());
                        slotStack.shrink(take);
                        extracted.grow(take);
                        changed = true;
                    }
                }
                if (slotStack.isEmpty()) {
                    container.setItem(i, ItemStack.EMPTY);
                }
                if (!extracted.isEmpty() && extracted.getCount() >= Math.min(maxCount, extracted.getMaxStackSize())) {
                    break;
                }
            }

            if (changed) {
                container.setChanged();
            }
            if (!extracted.isEmpty() && extracted.getCount() >= maxCount) {
                break;
            }
        }
        return extracted;
    }

    public static ItemStack withdrawWeaponForGuard(ServerLevel level, CityZoneManager manager) {
        ItemStack existing = extractMatchingItem(level, manager, StorageLogisticsHelper::isCombatWeapon, 1);
        if (!existing.isEmpty()) {
            return existing;
        }
        return craftWeaponFromStorageResources(level, manager);
    }

    /**
     * Synthesizes a combat weapon (Iron Sword, Stone Sword, or Wooden Sword) by consuming raw
     * materials from STORAGE chests when no pre-crafted weapon is available.
     */
    public static ItemStack craftWeaponFromStorageResources(ServerLevel level, CityZoneManager manager) {
        ItemStack wood = extractMatchingItem(level, manager, s -> s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS), 1);
        if (wood.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack iron = extractMatchingItem(
                level,
                manager,
                s -> s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON) || s.is(ItemTags.IRON_ORES),
                2
        );
        if (iron.getCount() >= 2) {
            return new ItemStack(Items.IRON_SWORD);
        } else if (!iron.isEmpty()) {
            depositStack(level, manager, iron);
        }

        ItemStack stone = extractMatchingItem(
                level,
                manager,
                s -> s.is(Items.COBBLESTONE) || s.is(Items.STONE) || s.is(ItemTags.STONE_CRAFTING_MATERIALS),
                2
        );
        if (stone.getCount() >= 2) {
            return new ItemStack(Items.STONE_SWORD);
        } else if (!stone.isEmpty()) {
            depositStack(level, manager, stone);
        }

        return new ItemStack(Items.WOODEN_SWORD);
    }

    public static ItemStack withdrawBuildingMaterial(ServerLevel level, CityZoneManager manager, int count) {
        return extractMatchingItem(level, manager, StorageLogisticsHelper::isBuildingBlockItem, count);
    }

    public static boolean isCombatWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.is(ItemTags.SWORDS)
                || stack.is(ItemTags.AXES)
                || stack.is(Items.BOW)
                || stack.is(Items.CROSSBOW);
    }

    public static boolean isBuildingBlockItem(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
            return false;
        }
        return stack.is(ItemTags.PLANKS)
                || stack.is(ItemTags.STONE_BRICKS)
                || stack.is(Items.COBBLESTONE)
                || stack.is(Items.STONE)
                || stack.is(Items.BRICKS)
                || stack.is(ItemTags.LOGS);
    }

    public static ResourceSummary computeResourceSummary(ServerLevel level, CityZoneManager manager) {
        int food = 0;
        int wood = 0;
        int stone = 0;
        int minerals = 0;

        for (BlockPos pos : getCachedChestPositions(level, manager)) {
            Optional<Container> containerOpt = getStorageContainerAt(level, pos);
            if (containerOpt.isEmpty()) {
                continue;
            }
            Container container = containerOpt.get();
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                int count = stack.getCount();
                if (stack.has(DataComponents.FOOD) || stack.is(Items.WHEAT) || stack.is(Items.HAY_BLOCK)) {
                    food += count;
                } else if (stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS)) {
                    wood += count;
                } else if (stack.is(ItemTags.STONE_CRAFTING_MATERIALS)
                        || stack.is(ItemTags.STONE_BRICKS)
                        || stack.is(Items.STONE)
                        || stack.is(Items.COBBLESTONE)
                        || stack.is(Items.DEEPSLATE)
                        || stack.is(Items.COBBLED_DEEPSLATE)) {
                    stone += count;
                } else if (stack.is(ItemTags.COAL_ORES)
                        || stack.is(ItemTags.IRON_ORES)
                        || stack.is(ItemTags.GOLD_ORES)
                        || stack.is(ItemTags.DIAMOND_ORES)
                        || stack.is(ItemTags.REDSTONE_ORES)
                        || stack.is(ItemTags.COPPER_ORES)
                        || stack.is(Items.COAL)
                        || stack.is(Items.RAW_IRON)
                        || stack.is(Items.IRON_INGOT)
                        || stack.is(Items.RAW_COPPER)
                        || stack.is(Items.COPPER_INGOT)
                        || stack.is(Items.RAW_GOLD)
                        || stack.is(Items.GOLD_INGOT)
                        || stack.is(Items.DIAMOND)
                        || stack.is(Items.EMERALD)
                        || stack.is(Items.REDSTONE)) {
                    minerals += count;
                }
            }
        }

        return new ResourceSummary(food, wood, stone, minerals);
    }
}
