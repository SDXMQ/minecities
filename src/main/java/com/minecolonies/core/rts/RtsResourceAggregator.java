package com.minecolonies.core.rts;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.api.configuration.RtsZonePlacementMode;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.api.tileentities.MinecoloniesTileEntities;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.Network;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import com.minecolonies.core.colony.buildings.modules.LivingBuildingModule;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingLumberjack;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse;
import com.minecolonies.core.network.messages.client.RtsSyncMessage;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import com.minecolonies.core.tileentities.TileEntityRack;
import com.minecolonies.core.util.AttributeModifierUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Server-side resource aggregator, road speed booster, and RTS zone/citizen action handler.
 * Also holds the latest synced snapshot on the client side in a dedicated-server-safe manner.
 */
public final class RtsResourceAggregator
{
    public static final String ROAD_SPEED_MODIFIER_NAME = "minecolonies_rts_road_speed";
    private static final double ROAD_SPEED_BOOST_AMOUNT = 0.30D;
    private static final int MAX_ZONE_SPAN = 64;

    /**
     * Immutable snapshot of colony RTS metrics and active zones.
     */
    public record ColonySnapshot(
        int colonyId,
        int food,
        int wood,
        int stone,
        int minerals,
        int totalCitizens,
        int maxCitizens,
        int idleCitizens,
        double overallHappiness,
        @NotNull List<RtsZoneData> zones
    )
    {
    }

    private static volatile ColonySnapshot clientSnapshot = new ColonySnapshot(0, 0, 0, 0, 0, 0, 0, 0, 5.0D, List.of());

    private RtsResourceAggregator()
    {
    }

    @NotNull
    public static ColonySnapshot getClientSnapshot()
    {
        return clientSnapshot;
    }

    public static void updateClientSnapshot(@NotNull final ColonySnapshot snapshot)
    {
        clientSnapshot = snapshot;
    }

    /**
     * Called from {@code FMLEventHandler#onWorldTick} on the server side.
     */
    public static void onWorldTick(@NotNull final TickEvent.LevelTickEvent event)
    {
        if (event.level.isClientSide || event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel serverLevel))
        {
            return;
        }

        if (serverLevel.getGameTime() % 20L != 0L)
        {
            return;
        }

        final boolean speedBoostEnabled = MineColonies.getConfig().getServer().rtsRoadSpeedBoost.get();
        for (final IColony colony : IColonyManager.getInstance().getIColonies(serverLevel))
        {
            updateColonyRoadSpeedBoost(serverLevel, colony, speedBoostEnabled);
        }

        for (final ServerPlayer player : serverLevel.players())
        {
            syncPlayerColonySnapshot(serverLevel, player);
        }
    }

    /**
     * Syncs the RTS snapshot for the colony nearest to (or containing) the given player.
     */
    public static void syncPlayerColonySnapshot(@NotNull final ServerLevel level, @NotNull final ServerPlayer player)
    {
        final IColony colony = resolveColonyForPosition(level, player.blockPosition(), player);
        final RtsZoneSavedData savedData = RtsZoneSavedData.get(level);
        final ColonySnapshot snapshot = aggregateColonyResources(level, colony, savedData);
        Network.getNetwork().sendToPlayer(new RtsSyncMessage(snapshot), player);
    }

    @Nullable
    public static IColony resolveColonyForPosition(@NotNull final ServerLevel level, @NotNull final BlockPos pos, @Nullable final ServerPlayer player)
    {
        IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, pos);
        if (colony == null && player != null)
        {
            colony = IColonyManager.getInstance().getIColonyByOwner(level, player);
        }
        if (colony == null)
        {
            colony = IColonyManager.getInstance().getClosestColony(level, pos);
        }
        return colony;
    }

    /**
     * Aggregates food, wood, stone, minerals, citizen counts, happiness, and RTS zones.
     */
    @NotNull
    public static ColonySnapshot aggregateColonyResources(
        @NotNull final ServerLevel level,
        @Nullable final IColony colony,
        @NotNull final RtsZoneSavedData zoneSavedData)
    {
        int food = 0;
        int wood = 0;
        int stone = 0;
        int minerals = 0;
        final Set<BlockPos> scannedContainers = new HashSet<>();

        final int colonyId = colony != null ? colony.getID() : 0;
        int totalCitizens = 0;
        int maxCitizens = 0;
        int idleCitizens = 0;
        double happiness = 5.0D;

        if (colony != null)
        {
            totalCitizens = colony.getCitizenManager().getCurrentCitizenCount();
            maxCitizens = colony.getCitizenManager().getMaxCitizens();
            happiness = colony.getOverallHappiness();

            for (final ICitizenData citizenData : colony.getCitizenManager().getCitizens())
            {
                if (citizenData.getJob() == null || citizenData.isIdleAtJob())
                {
                    idleCitizens++;
                }
            }

            for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
            {
                if (building instanceof BuildingWareHouse warehouse)
                {
                    for (final BlockPos containerPos : warehouse.getContainers())
                    {
                        if (!scannedContainers.add(containerPos.immutable()) || !WorldUtil.isBlockLoaded(level, containerPos))
                        {
                            continue;
                        }
                        final BlockEntity be = level.getBlockEntity(containerPos);
                        if (be instanceof TileEntityRack rack)
                        {
                            for (final Map.Entry<ItemStorage, Integer> entry : rack.getAllContent().entrySet())
                            {
                                final int count = Math.max(0, entry.getValue());
                                final ItemStack stack = entry.getKey().getItemStack();
                                final ResourceCategory cat = classifyItem(stack);
                                switch (cat)
                                {
                                    case FOOD -> food += count;
                                    case WOOD -> wood += count;
                                    case STONE -> stone += count;
                                    case MINERALS -> minerals += count;
                                    default -> {}
                                }
                            }
                        }
                    }
                }
                else
                {
                    final BlockPos buildingPos = building.getPosition();
                    if (scannedContainers.add(buildingPos.immutable()) && WorldUtil.isBlockLoaded(level, buildingPos))
                    {
                        final AbstractTileEntityColonyBuilding te = building.getTileEntity();
                        if (te != null)
                        {
                            final IItemHandler handler = te.getInventory();
                            if (handler != null)
                            {
                                for (int slot = 0; slot < handler.getSlots(); slot++)
                                {
                                    final ItemStack stack = handler.getStackInSlot(slot);
                                    if (!stack.isEmpty())
                                    {
                                        final int count = stack.getCount();
                                        switch (classifyItem(stack))
                                        {
                                            case FOOD -> food += count;
                                            case WOOD -> wood += count;
                                            case STONE -> stone += count;
                                            case MINERALS -> minerals += count;
                                            default -> {}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        final List<RtsZoneData> zones = colonyId > 0
            ? new ArrayList<>(zoneSavedData.getZonesForColony(colonyId))
            : new ArrayList<>(zoneSavedData.getZones());

        if (colonyId > 0)
        {
            for (final RtsZoneData zone : zoneSavedData.getZonesForColony(0))
            {
                zones.add(zone);
            }
        }

        // Also scan cached containers inside STORAGE zones that were not already counted
        for (final BlockPos pos : zoneSavedData.getCachedStorageContainers(level))
        {
            if (!scannedContainers.add(pos) || !WorldUtil.isBlockLoaded(level, pos))
            {
                continue;
            }
            final BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof TileEntityRack rack)
            {
                for (final Map.Entry<ItemStorage, Integer> entry : rack.getAllContent().entrySet())
                {
                    final int count = Math.max(0, entry.getValue());
                    switch (classifyItem(entry.getKey().getItemStack()))
                    {
                        case FOOD -> food += count;
                        case WOOD -> wood += count;
                        case STONE -> stone += count;
                        case MINERALS -> minerals += count;
                        default -> {}
                    }
                }
            }
            else if (be instanceof Container container)
            {
                for (int slot = 0; slot < container.getContainerSize(); slot++)
                {
                    final ItemStack stack = container.getItem(slot);
                    if (!stack.isEmpty())
                    {
                        final int count = stack.getCount();
                        switch (classifyItem(stack))
                        {
                            case FOOD -> food += count;
                            case WOOD -> wood += count;
                            case STONE -> stone += count;
                            case MINERALS -> minerals += count;
                            default -> {}
                        }
                    }
                }
            }
        }

        return new ColonySnapshot(
            colonyId,
            food,
            wood,
            stone,
            minerals,
            totalCitizens,
            maxCitizens,
            idleCitizens,
            happiness,
            List.copyOf(zones)
        );
    }

    public enum ResourceCategory
    {
        FOOD,
        WOOD,
        STONE,
        MINERALS,
        OTHER
    }

    @NotNull
    public static ResourceCategory classifyItem(@NotNull final ItemStack stack)
    {
        if (stack.isEmpty())
        {
            return ResourceCategory.OTHER;
        }
        if (stack.isEdible() || stack.is(Tags.Items.CROPS) || stack.is(ItemTags.FISHES) || stack.is(Tags.Items.SEEDS))
        {
            return ResourceCategory.FOOD;
        }
        if (stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS))
        {
            return ResourceCategory.WOOD;
        }
        if (stack.is(Tags.Items.STONE) || stack.is(Tags.Items.COBBLESTONE) || stack.is(ItemTags.STONE_BRICKS))
        {
            return ResourceCategory.STONE;
        }
        if (stack.is(Tags.Items.INGOTS) || stack.is(Tags.Items.GEMS) || stack.is(Tags.Items.ORES)
            || stack.is(Tags.Items.RAW_MATERIALS) || stack.is(ItemTags.COALS))
        {
            return ResourceCategory.MINERALS;
        }
        return ResourceCategory.OTHER;
    }

    /**
     * Applies or removes the +30% road movement speed modifier on colony citizens.
     */
    private static void updateColonyRoadSpeedBoost(
        @NotNull final ServerLevel level,
        @NotNull final IColony colony,
        final boolean enabled)
    {
        for (final ICitizenData citizenData : colony.getCitizenManager().getCitizens())
        {
            final Optional<AbstractEntityCitizen> entityOpt = citizenData.getEntity();
            if (entityOpt.isEmpty())
            {
                continue;
            }
            final AbstractEntityCitizen citizen = entityOpt.get();
            if (!enabled)
            {
                AttributeModifierUtils.removeModifier(citizen, ROAD_SPEED_MODIFIER_NAME, Attributes.MOVEMENT_SPEED);
                continue;
            }

            final BlockPos feetPos = citizen.blockPosition();
            final BlockState atFeet = level.getBlockState(feetPos);
            final BlockState belowFeet = level.getBlockState(feetPos.below());
            if (isRoadBlock(atFeet) || isRoadBlock(belowFeet))
            {
                final AttributeModifier modifier = new AttributeModifier(
                    ROAD_SPEED_MODIFIER_NAME,
                    ROAD_SPEED_BOOST_AMOUNT,
                    AttributeModifier.Operation.MULTIPLY_TOTAL
                );
                AttributeModifierUtils.addModifier(citizen, modifier, Attributes.MOVEMENT_SPEED);
            }
            else
            {
                AttributeModifierUtils.removeModifier(citizen, ROAD_SPEED_MODIFIER_NAME, Attributes.MOVEMENT_SPEED);
            }
        }
    }

    public static boolean isRoadBlock(@NotNull final BlockState state)
    {
        return state.is(Blocks.DIRT_PATH)
            || state.is(Blocks.STONE_BRICKS)
            || state.is(Blocks.COBBLESTONE)
            || state.is(Blocks.SMOOTH_STONE)
            || state.is(Blocks.POLISHED_ANDESITE)
            || state.is(Blocks.GRAVEL)
            || state.is(Blocks.MUD_BRICKS);
    }

    /**
     * Handles an RTS zone creation or deletion request from the client.
     */
    public static void handleZoneActionOnServer(
        @NotNull final ServerPlayer player,
        @NotNull final BlockPos rawStart,
        @NotNull final BlockPos rawEnd,
        @NotNull final RtsZoneType zoneType,
        final boolean deleteMode,
        @Nullable final RtsZonePlacementMode requestedPlacementMode)
    {
        if (!(player.level() instanceof ServerLevel level))
        {
            return;
        }
        if (player.blockPosition().distSqr(rawStart) > 256.0D * 256.0D)
        {
            return;
        }

        final int minX = Math.min(rawStart.getX(), rawEnd.getX());
        final int minY = Math.min(rawStart.getY(), rawEnd.getY());
        final int minZ = Math.min(rawStart.getZ(), rawEnd.getZ());
        final int maxX = Math.min(minX + MAX_ZONE_SPAN, Math.max(rawStart.getX(), rawEnd.getX()));
        final int maxY = Math.max(minY + 3, Math.max(rawStart.getY(), rawEnd.getY()));
        final int maxZ = Math.min(minZ + MAX_ZONE_SPAN, Math.max(rawStart.getZ(), rawEnd.getZ()));

        final BlockPos centerPos = new BlockPos((minX + maxX) / 2, minY, (minZ + maxZ) / 2);
        final IColony colony = resolveColonyForPosition(level, centerPos, player);
        if (colony != null && !colony.getPermissions().hasPermission(player, Action.MANAGE_HUTS))
        {
            player.displayClientMessage(Component.literal("[RTS] 정착지 구역 관리 권한이 없습니다."), true);
            return;
        }

        final RtsZoneSavedData savedData = RtsZoneSavedData.get(level);
        final BoundingBox box = new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);

        if (deleteMode)
        {
            final int removed = savedData.removeZonesIntersecting(box);
            player.displayClientMessage(Component.literal("[RTS] 구역 " + removed + "개 해제됨"), true);
            syncPlayerColonySnapshot(level, player);
            return;
        }

        final RtsZonePlacementMode mode = requestedPlacementMode != null
            ? requestedPlacementMode
            : MineColonies.getConfig().getServer().rtsZonePlacementMode.get();

        BlockPos linkedBuildingPos = null;
        if (mode == RtsZonePlacementMode.MODE_A_AUTO_PLACE)
        {
            linkedBuildingPos = autoPlaceOrLinkZoneBuilding(level, player, colony, zoneType, box, centerPos);
        }

        final int colonyId = colony != null ? colony.getID() : 0;
        savedData.addOrReplaceZone(new RtsZoneData(UUID.randomUUID(), colonyId, zoneType, box, linkedBuildingPos));
        player.displayClientMessage(
            Component.literal("[RTS] " + zoneType.getDisplayName() + " 설정 완료 (" + mode.getDisplayName() + ")"),
            true
        );
        syncPlayerColonySnapshot(level, player);
    }

    @Nullable
    private static BlockPos autoPlaceOrLinkZoneBuilding(
        @NotNull final ServerLevel level,
        @NotNull final ServerPlayer player,
        @Nullable final IColony colony,
        @NotNull final RtsZoneType zoneType,
        @NotNull final BoundingBox box,
        @NotNull final BlockPos centerPos)
    {
        final int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centerPos.getX(), centerPos.getZ());
        final int targetY = Mth.clamp(surfaceY, level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - 2);
        final BlockPos hutPos = new BlockPos(centerPos.getX(), targetY, centerPos.getZ());

        if (!WorldUtil.isBlockLoaded(level, hutPos))
        {
            return null;
        }

        // First check if a colony building already exists inside the zone bounds
        if (colony != null)
        {
            for (final Map.Entry<BlockPos, IBuilding> entry : colony.getServerBuildingManager().getBuildings().entrySet())
            {
                if (box.isInside(entry.getKey()))
                {
                    configureZoneSpecificBuilding(colony, entry.getValue(), zoneType, box, entry.getKey());
                    return entry.getKey();
                }
            }
        }

        final Block hutBlock = zoneType.getDefaultHutBlock();
        final BlockState existing = level.getBlockState(hutPos);
        if (existing.isAir() || existing.canBeReplaced())
        {
            final BlockState hutState = hutBlock.defaultBlockState();
            level.setBlockAndUpdate(hutPos, hutState);

            final BlockEntity be = level.getBlockEntity(hutPos);
            final AbstractTileEntityColonyBuilding buildingTe;
            if (be instanceof AbstractTileEntityColonyBuilding castTe)
            {
                buildingTe = castTe;
            }
            else
            {
                buildingTe = new TileEntityColonyBuilding(MinecoloniesTileEntities.BUILDING.get(), hutPos, hutState);
                level.setBlockEntity(buildingTe);
            }

            final String stylePack = colony != null ? colony.getStructurePack() : "Caledonia";
            buildingTe.setPackName(stylePack);
            hutBlock.setPlacedBy(level, hutPos, hutState, player, new ItemStack(hutBlock));
        }

        if (colony != null)
        {
            IBuilding building = colony.getServerBuildingManager().getBuilding(hutPos);
            if (building == null && level.getBlockEntity(hutPos) instanceof AbstractTileEntityColonyBuilding te)
            {
                building = colony.getServerBuildingManager().addNewBuilding(te, level);
            }
            if (building != null)
            {
                configureZoneSpecificBuilding(colony, building, zoneType, box, hutPos);
                if (building.getBuildingLevel() == 0 && !building.isPendingConstruction())
                {
                    building.requestUpgrade(player, BlockPos.ZERO);
                }
            }
        }

        return hutPos;
    }

    private static void configureZoneSpecificBuilding(
        @NotNull final IColony colony,
        @NotNull final IBuilding building,
        @NotNull final RtsZoneType zoneType,
        @NotNull final BoundingBox box,
        @NotNull final BlockPos buildingPos)
    {
        final BlockPos minCorner = new BlockPos(box.minX(), box.minY(), box.minZ());
        final BlockPos maxCorner = new BlockPos(box.maxX(), box.maxY(), box.maxZ());

        if (zoneType == RtsZoneType.LOGGING && building instanceof BuildingLumberjack lumberjack)
        {
            lumberjack.setRestrictedArea(minCorner, maxCorner);
        }
        else if (zoneType == RtsZoneType.DEFENSE && building instanceof AbstractBuildingGuards guardBuilding)
        {
            guardBuilding.addPatrolTarget(minCorner);
            guardBuilding.addPatrolTarget(maxCorner);
        }
        else if (zoneType == RtsZoneType.MINING)
        {
            for (final IBuilding other : colony.getServerBuildingManager().getBuildings().values())
            {
                if (other instanceof AbstractBuildingGuards guardBuilding && guardBuilding.getMinePos() == null)
                {
                    guardBuilding.setMinePos(buildingPos);
                }
            }
        }
    }

    /**
     * Handles an RTS citizen movement or job assignment command from the client.
     */
    public static void handleCitizenCommandOnServer(
        @NotNull final ServerPlayer player,
        final int colonyId,
        @NotNull final List<Integer> entityOrCitizenIds,
        @Nullable final BlockPos targetPos,
        @Nullable final String jobId)
    {
        if (!(player.level() instanceof ServerLevel level))
        {
            return;
        }

        final IColony colony = colonyId > 0
            ? IColonyManager.getInstance().getColonyByWorld(colonyId, level)
            : resolveColonyForPosition(level, player.blockPosition(), player);

        if (colony != null && !colony.getPermissions().hasPermission(player, Action.MANAGE_HUTS))
        {
            player.displayClientMessage(Component.literal("[RTS] 시민 명령 권한이 없습니다."), true);
            return;
        }

        int actedCount = 0;
        for (final int rawId : entityOrCitizenIds)
        {
            AbstractEntityCitizen entityCitizen = null;
            ICitizenData citizenData = null;

            final Entity worldEntity = level.getEntity(rawId);
            if (worldEntity instanceof AbstractEntityCitizen castCitizen)
            {
                entityCitizen = castCitizen;
                citizenData = castCitizen.getCitizenData();
            }
            else if (colony != null)
            {
                citizenData = colony.getCitizenManager().getCivilian(rawId);
                if (citizenData != null)
                {
                    entityCitizen = citizenData.getEntity().orElse(null);
                }
            }

            if (targetPos != null && entityCitizen != null)
            {
                entityCitizen.getNavigation().moveTo(
                    targetPos.getX() + 0.5D,
                    targetPos.getY(),
                    targetPos.getZ() + 0.5D,
                    1.15D
                );
                if (citizenData != null && citizenData.getWorkBuilding() instanceof AbstractBuildingGuards guardBuilding)
                {
                    guardBuilding.setTempNextPatrolPoint(targetPos);
                }
                actedCount++;
            }

            if (jobId != null && !jobId.isEmpty() && citizenData != null && colony != null)
            {
                if (assignCitizenToMatchingBuilding(colony, citizenData, jobId, targetPos))
                {
                    actedCount++;
                }
            }
        }

        if (actedCount > 0)
        {
            player.displayClientMessage(Component.literal("[RTS] 시민 " + actedCount + "명 명령 하달 완료"), true);
        }
    }

    private static boolean assignCitizenToMatchingBuilding(
        @NotNull final IColony colony,
        @NotNull final ICitizenData citizenData,
        @NotNull final String jobId,
        @Nullable final BlockPos preferredBuildingPos)
    {
        if (preferredBuildingPos != null)
        {
            final IBuilding preferred = colony.getServerBuildingManager().getBuilding(preferredBuildingPos);
            if (preferred != null && tryAssignCitizenToBuilding(preferred, citizenData, jobId))
            {
                return true;
            }
        }

        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
        {
            if (tryAssignCitizenToBuilding(building, citizenData, jobId))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean tryAssignCitizenToBuilding(
        @NotNull final IBuilding building,
        @NotNull final ICitizenData citizenData,
        @NotNull final String jobId)
    {
        if ("home".equalsIgnoreCase(jobId))
        {
            final LivingBuildingModule livingModule = building.getModule(LivingBuildingModule.class);
            if (livingModule != null && !livingModule.isFull())
            {
                livingModule.assignCitizen(citizenData);
                return true;
            }
            return false;
        }

        final WorkerBuildingModule workerModule = building.getModule(
            WorkerBuildingModule.class,
            m -> m.getJobEntry() != null
                && m.getJobEntry().getKey() != null
                && (m.getJobEntry().getKey().toString().equalsIgnoreCase(jobId)
                    || m.getJobEntry().getKey().getPath().equalsIgnoreCase(jobId))
                && !m.isFull()
        );
        if (workerModule != null)
        {
            citizenData.setPaused(false);
            workerModule.assignCitizen(citizenData);
            return true;
        }
        return false;
    }
}
