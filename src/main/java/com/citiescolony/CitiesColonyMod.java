package com.citiescolony;

import com.citiescolony.api.ColonyIds;
import com.citiescolony.api.ColonyTask;
import com.citiescolony.common.city.CityZoneManager;
import com.citiescolony.common.city.NightWaveDefenseManager;
import com.citiescolony.common.city.StorageLogisticsHelper;
import com.citiescolony.common.city.ZoneTaskGenerator;
import com.citiescolony.common.entity.ColonyCitizenEntity;
import com.citiescolony.common.network.ColonyNetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

/**
 * Main entry point for Minecraft Cities Colony (Minecraft 26.2 / NeoForge).
 * Strictly follows Minecraft 26.2 separated BlockIds, BlockItemIds, and ItemIds conventions
 * and keeps all client-only classes out of server/common packages.
 */
@Mod(CitiesColonyMod.MOD_ID)
@EventBusSubscriber(modid = CitiesColonyMod.MOD_ID)
public class CitiesColonyMod {

    public static final String MOD_ID = ColonyIds.MOD_ID;

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, MOD_ID);

    // Town Hall Block: registers its position as townHallPos in CityZoneManager on placement and clears on removal
    public static final DeferredBlock<Block> TOWN_HALL_BLOCK = BLOCKS.register(
            ColonyIds.BlockIds.TOWN_HALL.identifier().getPath(),
            () -> new Block(BlockBehaviour.Properties.of()
                    .setId(ColonyIds.BlockIds.TOWN_HALL)
                    .mapColor(MapColor.GOLD)
                    .strength(4.0F, 12.0F)
                    .sound(SoundType.STONE)) {
                @Override
                public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
                    super.onPlace(state, level, pos, oldState, movedByPiston);
                    if (level instanceof ServerLevel serverLevel && !state.is(oldState.getBlock())) {
                        CityZoneManager manager = CityZoneManager.get(serverLevel);
                        manager.setTownHallPos(pos);
                        ColonyNetworkHandler.broadcastCitySync(serverLevel, manager);
                    }
                }

                @Override
                public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
                    super.setPlacedBy(level, pos, state, placer, stack);
                    if (level instanceof ServerLevel serverLevel) {
                        CityZoneManager manager = CityZoneManager.get(serverLevel);
                        manager.setTownHallPos(pos);
                        ColonyNetworkHandler.broadcastCitySync(serverLevel, manager);
                    }
                }

                @Override
                public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
                    if (level instanceof ServerLevel serverLevel) {
                        CityZoneManager manager = CityZoneManager.get(serverLevel);
                        if (manager.getTownHallPos().filter(pos::equals).isPresent()) {
                            manager.setTownHallPos(null);
                            ColonyNetworkHandler.broadcastCitySync(serverLevel, manager);
                        }
                    }
                    return super.playerWillDestroy(level, pos, state, player);
                }
            }
    );

    public static final DeferredBlock<Block> PAVED_ROAD_BLOCK = BLOCKS.register(
            ColonyIds.BlockIds.PAVED_ROAD.identifier().getPath(),
            () -> new Block(BlockBehaviour.Properties.of()
                    .setId(ColonyIds.BlockIds.PAVED_ROAD)
                    .mapColor(MapColor.STONE)
                    .strength(1.8F, 6.0F)
                    .sound(SoundType.STONE))
    );

    // Guard Outpost Block: registers/unregisters defense outpost anchor in CityZoneManager on placement/removal
    public static final DeferredBlock<Block> GUARD_OUTPOST_BLOCK = BLOCKS.register(
            ColonyIds.BlockIds.GUARD_OUTPOST.identifier().getPath(),
            () -> new Block(BlockBehaviour.Properties.of()
                    .setId(ColonyIds.BlockIds.GUARD_OUTPOST)
                    .mapColor(MapColor.COLOR_RED)
                    .strength(3.0F, 9.0F)
                    .sound(SoundType.WOOD)) {
                @Override
                public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
                    super.onPlace(state, level, pos, oldState, movedByPiston);
                    if (level instanceof ServerLevel serverLevel && !state.is(oldState.getBlock())) {
                        CityZoneManager manager = CityZoneManager.get(serverLevel);
                        manager.registerGuardOutpost(pos);
                        manager.enqueueTask(new ColonyTask(
                                ColonyTask.TaskType.GUARD,
                                pos.immutable(),
                                ColonyTask.TaskType.GUARD.defaultPriority()
                        ));
                    }
                }

                @Override
                public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
                    super.setPlacedBy(level, pos, state, placer, stack);
                    if (level instanceof ServerLevel serverLevel) {
                        CityZoneManager manager = CityZoneManager.get(serverLevel);
                        manager.registerGuardOutpost(pos);
                    }
                }

                @Override
                public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
                    if (level instanceof ServerLevel serverLevel) {
                        CityZoneManager manager = CityZoneManager.get(serverLevel);
                        manager.unregisterGuardOutpost(pos);
                    }
                    return super.playerWillDestroy(level, pos, state, player);
                }
            }
    );

    // BlockItems registered with explicit Minecraft 26.2 BlockItemIds paired ResourceKey
    public static final DeferredItem<BlockItem> TOWN_HALL_ITEM = ITEMS.register(
            ColonyIds.BlockItemIds.TOWN_HALL.item().identifier().getPath(),
            () -> new BlockItem(
                    TOWN_HALL_BLOCK.get(),
                    new Item.Properties().setId(ColonyIds.BlockItemIds.TOWN_HALL.item()).useBlockDescriptionPrefix()
            )
    );

    public static final DeferredItem<BlockItem> PAVED_ROAD_ITEM = ITEMS.register(
            ColonyIds.BlockItemIds.PAVED_ROAD.item().identifier().getPath(),
            () -> new BlockItem(
                    PAVED_ROAD_BLOCK.get(),
                    new Item.Properties().setId(ColonyIds.BlockItemIds.PAVED_ROAD.item()).useBlockDescriptionPrefix()
            )
    );

    public static final DeferredItem<BlockItem> GUARD_OUTPOST_ITEM = ITEMS.register(
            ColonyIds.BlockItemIds.GUARD_OUTPOST.item().identifier().getPath(),
            () -> new BlockItem(
                    GUARD_OUTPOST_BLOCK.get(),
                    new Item.Properties().setId(ColonyIds.BlockItemIds.GUARD_OUTPOST.item()).useBlockDescriptionPrefix()
            )
    );

    // Standalone Items registered with explicit Minecraft 26.2 ItemIds ResourceKey
    public static final DeferredItem<Item> COLONY_SCEPTER_ITEM = ITEMS.register(
            ColonyIds.ItemIds.COLONY_SCEPTER.identifier().getPath(),
            () -> new Item(new Item.Properties().setId(ColonyIds.ItemIds.COLONY_SCEPTER).stacksTo(1)) {
                @Override
                public InteractionResult useOn(UseOnContext context) {
                    if (context.getLevel() instanceof ServerLevel serverLevel) {
                        BlockPos rallyTarget = context.getClickedPos().above();
                        CityZoneManager manager = CityZoneManager.get(serverLevel);
                        AABB bossSearchBox = new AABB(rallyTarget).inflate(48.0D, 24.0D, 48.0D);
                        List<LivingEntity> expeditionTargets = serverLevel.getEntitiesOfClass(
                                LivingEntity.class,
                                bossSearchBox,
                                e -> e.isAlive() && e instanceof Enemy
                        );

                        for (ColonyCitizenEntity citizen : manager.getActiveCitizens()) {
                            if (citizen.getJobType() == com.citiescolony.api.JobType.GUARD) {
                                if (citizen.getCitizenInventory().findCombatWeapon().isEmpty()
                                        && !StorageLogisticsHelper.isCombatWeapon(citizen.getMainHandItem())) {
                                    ItemStack supplied = StorageLogisticsHelper.withdrawWeaponForGuard(serverLevel, manager);
                                    if (!supplied.isEmpty()) {
                                        citizen.getCitizenInventory().insertStack(supplied);
                                    }
                                }
                                citizen.equipBestCombatWeapon();
                                citizen.issuePlayerCommand(serverLevel, rallyTarget);
                                if (!expeditionTargets.isEmpty()) {
                                    citizen.setTarget(expeditionTargets.getFirst());
                                }
                            }
                        }
                        ColonyNetworkHandler.broadcastResourceHudSync(serverLevel, manager);
                    }
                    return InteractionResult.SUCCESS;
                }
            }
    );

    public static final DeferredItem<Item> CITIZEN_SPAWN_CHARTER_ITEM = ITEMS.register(
            ColonyIds.ItemIds.CITIZEN_SPAWN_CHARTER.identifier().getPath(),
            () -> new Item(new Item.Properties().setId(ColonyIds.ItemIds.CITIZEN_SPAWN_CHARTER).stacksTo(16)) {
                @Override
                public InteractionResult useOn(UseOnContext context) {
                    Level level = context.getLevel();
                    if (level instanceof ServerLevel serverLevel) {
                        BlockPos spawnPos = context.getClickedPos().relative(context.getClickedFace());
                        ColonyCitizenEntity citizen = COLONY_CITIZEN_ENTITY.get().create(serverLevel, EntitySpawnReason.SPAWN_ITEM_USE);
                        if (citizen != null) {
                            citizen.snapTo(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D, 0.0F, 0.0F);
                            serverLevel.addFreshEntity(citizen);
                            context.getItemInHand().shrink(1);
                            CityZoneManager manager = CityZoneManager.get(serverLevel);
                            manager.registerCitizen(citizen);
                            ColonyNetworkHandler.broadcastResourceHudSync(serverLevel, manager);
                        }
                    }
                    return InteractionResult.SUCCESS;
                }
            }
    );

    // ColonyCitizenEntity registration
    public static final DeferredHolder<EntityType<?>, EntityType<ColonyCitizenEntity>> COLONY_CITIZEN_ENTITY =
            ENTITY_TYPES.register(
                    ColonyIds.EntityIds.COLONY_CITIZEN.identifier().getPath(),
                    () -> EntityType.Builder.of(ColonyCitizenEntity::new, MobCategory.CREATURE)
                            .sized(0.6F, 1.95F)
                            .clientTrackingRange(10)
                            .build(ColonyIds.EntityIds.COLONY_CITIZEN)
            );

    public CitiesColonyMod(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
    }

    @SubscribeEvent
    public static void onRegisterEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(COLONY_CITIZEN_ENTITY.get(), ColonyCitizenEntity.createAttributes().build());
    }

    @SubscribeEvent
    public static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        ColonyNetworkHandler.registerPayloads(event);
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            ServerLevel level = serverPlayer.level();
            CityZoneManager manager = CityZoneManager.get(level);
            ColonyNetworkHandler.sendCitySyncToPlayer(serverPlayer, manager);
            ColonyNetworkHandler.broadcastResourceHudSync(level, manager);
        }
    }

    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            ZoneTaskGenerator.onBlockStateChanged(serverLevel, event.getPos(), event.getPlacedBlock());
        }
    }

    @SubscribeEvent
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            ZoneTaskGenerator.onBlockStateChanged(serverLevel, event.getPos(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
    }

    @SubscribeEvent
    public static void onCropGrowPost(CropGrowEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            ZoneTaskGenerator.onBlockStateChanged(serverLevel, event.getPos(), event.getState());
        }
    }

    @SubscribeEvent
    public static void onServerLevelTickPost(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        // Guard against Nether/End ticks interfering with Overworld city zones and HUD sync
        if (serverLevel != serverLevel.getServer().overworld()) {
            return;
        }
        ZoneTaskGenerator.tick(serverLevel);

        if ((serverLevel.getGameTime() % 20L) == 0L) {
            CityZoneManager manager = CityZoneManager.get(serverLevel);
            int citizenCount = countAliveCitizens(serverLevel);
            NightWaveDefenseManager.tick(serverLevel, citizenCount);

            if (!serverLevel.players().isEmpty()) {
                ColonyNetworkHandler.broadcastResourceHudSync(serverLevel, manager);
            }
        }
    }

    private static int countAliveCitizens(ServerLevel level) {
        return CityZoneManager.get(level).countAliveCitizens();
    }
}
