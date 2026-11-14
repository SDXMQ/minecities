package com.minecolonies.core.rts;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintTagUtils;
import com.ldtteam.structurize.storage.StructurePackMeta;
import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildingextensions.IBuildingExtension;
import com.minecolonies.api.colony.buildingextensions.registry.BuildingExtensionRegistries;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.api.compatibility.newstruct.BlueprintMapping;
import com.minecolonies.api.configuration.RtsZonePlacementMode;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.api.tileentities.MinecoloniesTileEntities;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.Network;
import com.minecolonies.core.blocks.BlockScarecrow;
import com.minecolonies.core.colony.buildingextensions.FarmField;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import com.minecolonies.core.colony.buildings.modules.BuildingExtensionsModule;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.LivingBuildingModule;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingLumberjack;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse;
import com.minecolonies.core.network.messages.client.RtsSyncMessage;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import com.minecolonies.core.tileentities.TileEntityRack;
import com.minecolonies.core.util.AttributeModifierUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
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
    public static final double ROAD_SPEED_BOOST_AMOUNT = 0.30D;
    public static final UUID ROAD_SPEED_MODIFIER_UUID = UUID.fromString("d8499b04-0e66-4726-ab29-64469d734e0d");
    public static final AttributeModifier ROAD_SPEED_MODIFIER = new AttributeModifier(
        ROAD_SPEED_MODIFIER_UUID,
        ROAD_SPEED_MODIFIER_NAME,
        ROAD_SPEED_BOOST_AMOUNT,
        AttributeModifier.Operation.MULTIPLY_TOTAL
    );
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
                AttributeModifierUtils.addModifier(citizen, ROAD_SPEED_MODIFIER, Attributes.MOVEMENT_SPEED);
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

    private record ZonePlacementResult(@Nullable BlockPos linkedBuildingPos, @Nullable String feedbackOverride)
    {
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
            final List<RtsZoneData> intersecting = savedData.getZones().stream()
                .filter(z -> z.intersects(box))
                .toList();

            for (final RtsZoneData zone : intersecting)
            {
                final BlockPos hutPos = zone.linkedBuildingPos();
                if (hutPos != null && WorldUtil.isBlockLoaded(level, hutPos))
                {
                    final IBuilding building = colony != null ? colony.getServerBuildingManager().getBuilding(hutPos) : null;
                    if (building != null && building.getBuildingLevel() > 0)
                    {
                        // Keep completed Lv.1+ MineColonies buildings intact; only unlink the RTS zone overlay.
                        continue;
                    }
                    if (building != null)
                    {
                        building.removeWorkOrder();
                        building.destroy();
                    }
                    final BlockState state = level.getBlockState(hutPos);
                    if (state.getBlock() instanceof AbstractBlockHut
                        || level.getBlockEntity(hutPos) instanceof AbstractTileEntityColonyBuilding)
                    {
                        level.removeBlock(hutPos, false);
                    }
                }
            }

            final int removed = savedData.removeZonesIntersecting(box);
            player.displayClientMessage(Component.literal("[RTS] 구역 " + removed + "개 해제됨"), true);
            syncPlayerColonySnapshot(level, player);
            return;
        }

        if (colony == null)
        {
            player.displayClientMessage(
                Component.literal("[RTS] 정착지(Colony)를 먼저 설립해야 구역을 지정할 수 있습니다. 시청(Town Hall)을 설치/우클릭하세요."),
                true
            );
            return;
        }

        final RtsZonePlacementMode mode = requestedPlacementMode != null
            ? requestedPlacementMode
            : MineColonies.getConfig().getServer().rtsZonePlacementMode.get();

        ZonePlacementResult placementResult = new ZonePlacementResult(null, null);
        if (mode == RtsZonePlacementMode.MODE_A_AUTO_PLACE)
        {
            placementResult = autoPlaceOrLinkZoneBuilding(level, player, colony, zoneType, box, centerPos);
        }

        savedData.addOrReplaceZone(new RtsZoneData(
            UUID.randomUUID(),
            colony.getID(),
            zoneType,
            box,
            placementResult.linkedBuildingPos()
        ));

        final String message = placementResult.feedbackOverride() != null
            ? placementResult.feedbackOverride()
            : "[RTS] " + zoneType.getDisplayName() + " 설정 완료 (" + mode.getDisplayName() + ")";
        player.displayClientMessage(Component.literal(message), true);
        syncPlayerColonySnapshot(level, player);
    }

    @NotNull
    private static ZonePlacementResult autoPlaceOrLinkZoneBuilding(
        @NotNull final ServerLevel level,
        @NotNull final ServerPlayer player,
        @NotNull final IColony colony,
        @NotNull final RtsZoneType zoneType,
        @NotNull final BoundingBox box,
        @NotNull final BlockPos centerPos)
    {
        // First check if a colony building already exists inside the zone bounds (prefer matching hut type)
        IBuilding existingInside = null;
        final AbstractBlockHut<?> targetHutBlock = zoneType.getDefaultHutBlock();
        for (final Map.Entry<BlockPos, IBuilding> entry : colony.getServerBuildingManager().getBuildings().entrySet())
        {
            final BlockPos bPos = entry.getKey();
            if (bPos.getX() >= box.minX() && bPos.getX() <= box.maxX()
                && bPos.getZ() >= box.minZ() && bPos.getZ() <= box.maxZ()
                && Math.abs(bPos.getY() - centerPos.getY()) <= 16)
            {
                if (targetHutBlock != null && level.getBlockState(bPos).is(targetHutBlock))
                {
                    existingInside = entry.getValue();
                    break;
                }
                if (existingInside == null)
                {
                    existingInside = entry.getValue();
                }
            }
        }
        if (existingInside != null)
        {
            configureZoneSpecificBuilding(level, colony, existingInside, zoneType, box, existingInside.getPosition());
            final String upgradeFeedback = ensureWorkerAndRequestUpgrade(player, colony, existingInside, zoneType, existingInside.getPosition());
            return new ZonePlacementResult(existingInside.getPosition(), upgradeFeedback);
        }

        final BlockPos hutPos = findPlaceableSurfacePos(level, box, centerPos);
        if (hutPos == null || !WorldUtil.isBlockLoaded(level, hutPos) || targetHutBlock == null)
        {
            return new ZonePlacementResult(null, "[RTS] 구역 내 건물을 배치할 빈 평지 공간을 찾지 못했습니다.");
        }

        final String stylePack = !colony.getStructurePack().isEmpty() ? colony.getStructurePack() : "Caledonia";
        final String blueprintPath = resolveBlueprintPath(stylePack, zoneType);

        final BlockState hutState = targetHutBlock.defaultBlockState();
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

        buildingTe.setPackName(stylePack);
        buildingTe.setBlueprintPath(blueprintPath);
        targetHutBlock.setPlacedBy(level, hutPos, hutState, player, new ItemStack(targetHutBlock));

        IBuilding building = colony.getServerBuildingManager().getBuilding(hutPos);
        if (building == null && level.getBlockEntity(hutPos) instanceof AbstractTileEntityColonyBuilding te)
        {
            building = colony.getServerBuildingManager().addNewBuilding(te, level);
        }
        if (building != null)
        {
            building.setStructurePack(stylePack);
            building.setBlueprintPath(blueprintPath);
            configureZoneSpecificBuilding(level, colony, building, zoneType, box, hutPos);
            final String upgradeFeedback = ensureWorkerAndRequestUpgrade(player, colony, building, zoneType, hutPos);
            return new ZonePlacementResult(hutPos, upgradeFeedback);
        }

        return new ZonePlacementResult(hutPos, null);
    }

    @Nullable
    private static BlockPos findPlaceableSurfacePos(
        @NotNull final ServerLevel level,
        @NotNull final BoundingBox box,
        @NotNull final BlockPos centerPos)
    {
        final int minX = box.minX();
        final int maxX = box.maxX();
        final int minZ = box.minZ();
        final int maxZ = box.maxZ();
        final int maxRadius = Math.max(maxX - minX, maxZ - minZ);

        for (int radius = 0; radius <= maxRadius; radius++)
        {
            for (int dx = -radius; dx <= radius; dx++)
            {
                for (int dz = -radius; dz <= radius; dz++)
                {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius)
                    {
                        continue;
                    }
                    final int x = centerPos.getX() + dx;
                    final int z = centerPos.getZ() + dz;
                    if (x < minX || x > maxX || z < minZ || z > maxZ)
                    {
                        continue;
                    }
                    final int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    final int targetY = Mth.clamp(surfaceY, level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - 2);
                    final BlockPos candidate = new BlockPos(x, targetY, z);
                    if (!WorldUtil.isBlockLoaded(level, candidate))
                    {
                        continue;
                    }
                    final BlockState atPos = level.getBlockState(candidate);
                    final BlockState below = level.getBlockState(candidate.below());
                    if ((atPos.isAir() || atPos.canBeReplaced())
                        && atPos.getFluidState().isEmpty()
                        && !below.isAir()
                        && below.getFluidState().isEmpty()
                        && below.isFaceSturdy(level, candidate.below(), Direction.UP)
                        && !below.is(BlockTags.LOGS)
                        && !below.is(BlockTags.LEAVES)
                        && !(below.getBlock() instanceof AbstractBlockHut))
                    {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    @NotNull
    public static String resolveBlueprintPath(@NotNull final String stylePack, @NotNull final RtsZoneType zoneType)
    {
        final AbstractBlockHut<?> hutBlock = zoneType.getDefaultHutBlock();
        if (hutBlock != null)
        {
            try
            {
                if (StructurePacks.hasPack(stylePack))
                {
                    Blueprint match = StructurePacks.findBlueprint(
                        stylePack,
                        bp -> !BlueprintTagUtils.isInvisible(bp)
                            && bp.getBlockState(bp.getPrimaryBlockOffset()).getBlock() == hutBlock
                            && bp.getFileName().endsWith("1.blueprint")
                    );
                    if (match == null)
                    {
                        match = StructurePacks.findBlueprint(
                            stylePack,
                            bp -> !BlueprintTagUtils.isInvisible(bp)
                                && bp.getBlockState(bp.getPrimaryBlockOffset()).getBlock() == hutBlock
                        );
                    }
                    final StructurePackMeta meta = StructurePacks.getStructurePack(stylePack);
                    if (match != null && meta != null && match.getFilePath() != null && match.getFileName() != null)
                    {
                        final String subPath = meta.getSubPath(match.getFilePath().resolve(match.getFileName()));
                        if (subPath != null && !subPath.isEmpty())
                        {
                            return normalizeBlueprintSubPath(subPath);
                        }
                    }
                }
            }
            catch (final Throwable ignored)
            {
            }
        }
        return resolveFallbackBlueprintPath(stylePack, zoneType.getDefaultSchematicName());
    }

    @NotNull
    public static String resolveFallbackBlueprintPath(@NotNull final String stylePack, @NotNull final String schematic)
    {
        final String normalizedStyle = stylePack.toLowerCase(Locale.ROOT).replace(" ", "").trim();
        final String mappedPath = BlueprintMapping.getPathMapping(normalizedStyle, schematic);
        final String fallbackPath = BlueprintMapping.getPathMapping("", schematic);
        final String bpRelative = mappedPath != null
            ? mappedPath
            : (fallbackPath != null ? fallbackPath : "fundamentals/" + schematic);
        return normalizeBlueprintSubPath(bpRelative);
    }

    @NotNull
    public static String normalizeBlueprintSubPath(@NotNull final String rawPath)
    {
        String clean = rawPath.replace('\\', '/').trim();
        while (clean.startsWith("/"))
        {
            clean = clean.substring(1);
        }
        if (clean.endsWith(".blueprint"))
        {
            return clean;
        }
        return clean + "1.blueprint";
    }

    @Nullable
    private static String ensureWorkerAndRequestUpgrade(
        @NotNull final ServerPlayer player,
        @NotNull final IColony colony,
        @NotNull final IBuilding building,
        @NotNull final RtsZoneType zoneType,
        @NotNull final BlockPos hutPos)
    {
        if (building instanceof BuildingBuilder)
        {
            ensureBuilderHasWorker(colony, building);
            if (building.getAllAssignedCitizen().isEmpty())
            {
                return "[RTS] 건축가(Builder) 구역이 배치되었으나 배정할 유휴 시민이 없어 건설 지시를 보류했습니다.";
            }
            if (building.getBuildingLevel() == 0 && !building.isPendingConstruction())
            {
                building.requestUpgrade(player, BlockPos.ZERO);
            }
            return null;
        }

        if (building.getBuildingLevel() == 0 && !building.isPendingConstruction())
        {
            ensureAnyBuilderWithinRangeHasWorker(colony, hutPos);
            if (!hasActiveBuilderWithinRange(colony, hutPos))
            {
                return "[RTS] " + zoneType.getDisplayName() + " 배치됨 - 반경 100블록 내 일꾼이 있는 건축가(Builder) 구역이 필요합니다.";
            }
            building.requestUpgrade(player, BlockPos.ZERO);
        }
        return null;
    }

    private static void ensureBuilderHasWorker(@NotNull final IColony colony, @NotNull final IBuilding builderBuilding)
    {
        if (!builderBuilding.getAllAssignedCitizen().isEmpty())
        {
            return;
        }
        final WorkerBuildingModule builderModule = builderBuilding.getModule(BuildingModules.BUILDER_WORK);
        if (builderModule == null || builderModule.isFull())
        {
            return;
        }
        final ICitizenData jobless = colony.getCitizenManager().getJoblessCitizen();
        if (jobless != null)
        {
            jobless.setPaused(false);
            builderModule.assignCitizen(jobless);
        }
    }

    private static void ensureAnyBuilderWithinRangeHasWorker(@NotNull final IColony colony, @NotNull final BlockPos sitePos)
    {
        for (final IBuilding candidate : colony.getServerBuildingManager().getBuildings().values())
        {
            if (candidate instanceof BuildingBuilder
                && candidate.getPosition().distSqr(sitePos) <= 100.0D * 100.0D
                && candidate.getAllAssignedCitizen().isEmpty())
            {
                ensureBuilderHasWorker(colony, candidate);
                if (!candidate.getAllAssignedCitizen().isEmpty())
                {
                    return;
                }
            }
        }
    }

    private static boolean hasActiveBuilderWithinRange(@NotNull final IColony colony, @NotNull final BlockPos sitePos)
    {
        for (final IBuilding candidate : colony.getServerBuildingManager().getBuildings().values())
        {
            if (candidate instanceof BuildingBuilder
                && !candidate.getAllAssignedCitizen().isEmpty()
                && candidate.getPosition().distSqr(sitePos) <= 100.0D * 100.0D)
            {
                return true;
            }
        }
        return false;
    }

    private static void configureZoneSpecificBuilding(
        @NotNull final ServerLevel level,
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
        else if (zoneType == RtsZoneType.FARMING)
        {
            ensureFarmFieldForZone(level, colony, building, box, buildingPos);
        }
    }

    private static void ensureFarmFieldForZone(
        @NotNull final ServerLevel level,
        @NotNull final IColony colony,
        @NotNull final IBuilding building,
        @NotNull final BoundingBox box,
        @NotNull final BlockPos buildingPos)
    {
        final List<IBuildingExtension> existingFields = colony.getServerBuildingManager().getBuildingExtensions(ext -> {
            if (ext.getBuildingExtensionType() != BuildingExtensionRegistries.farmField.get())
            {
                return false;
            }
            final BlockPos p = ext.getPosition();
            return p.getX() >= box.minX() && p.getX() <= box.maxX()
                && p.getZ() >= box.minZ() && p.getZ() <= box.maxZ();
        });

        final BuildingExtensionsModule extModule = building.getFirstModuleOccurance(BuildingExtensionsModule.class);
        if (!existingFields.isEmpty())
        {
            for (final IBuildingExtension ext : existingFields)
            {
                if (ext instanceof FarmField farmField && farmField.getSeed().isEmpty())
                {
                    farmField.setSeed(new ItemStack(Items.WHEAT_SEEDS));
                }
                if (extModule != null && !ext.isTaken())
                {
                    extModule.assignExtension(ext);
                }
            }
            return;
        }

        final BlockPos scarecrowPos = findScarecrowPlacementPos(level, box, buildingPos);
        if (scarecrowPos == null)
        {
            return;
        }

        final BlockState lower = ModBlocks.blockScarecrow.defaultBlockState().setValue(BlockScarecrow.HALF, DoubleBlockHalf.LOWER);
        final BlockState upper = lower.setValue(BlockScarecrow.HALF, DoubleBlockHalf.UPPER);
        level.setBlockAndUpdate(scarecrowPos, lower);
        level.setBlockAndUpdate(scarecrowPos.above(), upper);

        final FarmField farmField = FarmField.create(scarecrowPos, level);
        if (farmField != null)
        {
            farmField.setSeed(new ItemStack(Items.WHEAT_SEEDS));
            colony.getServerBuildingManager().addBuildingExtension(farmField);
            if (extModule != null)
            {
                extModule.assignExtension(farmField);
            }
        }
    }

    @Nullable
    private static BlockPos findScarecrowPlacementPos(
        @NotNull final ServerLevel level,
        @NotNull final BoundingBox box,
        @NotNull final BlockPos buildingPos)
    {
        final int minX = box.minX();
        final int maxX = box.maxX();
        final int minZ = box.minZ();
        final int maxZ = box.maxZ();
        final int centerX = (minX + maxX) / 2;
        final int centerZ = (minZ + maxZ) / 2;

        for (final int minDistFromHut : new int[]{4, 2})
        {
            BlockPos best = null;
            double bestCenterDistSqr = Double.MAX_VALUE;
            for (int x = minX; x <= maxX; x++)
            {
                for (int z = minZ; z <= maxZ; z++)
                {
                    final int hutChebyshev = Math.max(Math.abs(x - buildingPos.getX()), Math.abs(z - buildingPos.getZ()));
                    if (hutChebyshev < minDistFromHut)
                    {
                        continue;
                    }
                    final int y = Mth.clamp(
                        level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z),
                        level.getMinBuildHeight() + 1,
                        level.getMaxBuildHeight() - 3
                    );
                    final BlockPos candidate = new BlockPos(x, y, z);
                    if (!WorldUtil.isBlockLoaded(level, candidate))
                    {
                        continue;
                    }
                    final BlockState atPos = level.getBlockState(candidate);
                    final BlockState above = level.getBlockState(candidate.above());
                    final BlockState below = level.getBlockState(candidate.below());
                    if ((atPos.isAir() || atPos.canBeReplaced())
                        && (above.isAir() || above.canBeReplaced())
                        && atPos.getFluidState().isEmpty()
                        && above.getFluidState().isEmpty()
                        && !below.isAir()
                        && below.getFluidState().isEmpty()
                        && below.isFaceSturdy(level, candidate.below(), Direction.UP)
                        && !below.is(BlockTags.LOGS)
                        && !below.is(BlockTags.LEAVES)
                        && !(below.getBlock() instanceof AbstractBlockHut))
                    {
                        final double d2 = (x - centerX) * (x - centerX) + (z - centerZ) * (z - centerZ);
                        if (d2 < bestCenterDistSqr)
                        {
                            bestCenterDistSqr = d2;
                            best = candidate;
                        }
                    }
                }
            }
            if (best != null)
            {
                return best;
            }
        }
        return null;
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
                if (citizenData != null && citizenData.getJob() != null
                    && citizenData.getJob().getWorkerAI() instanceof com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic<?, ?> workerAi)
                {
                    workerAi.setDelay(20 * 10); // 10초간 작업 AI가 길찾기를 덮어쓰지 않도록 유예
                }
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
