package com.citiescolony.common.city;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spawns and directs night monster waves against the city Town Hall when world time reaches night (>= 13000 ticks).
 * Wave size scales proportionally to the colony citizen population and city zone development count.
 */
public final class NightWaveDefenseManager {
    public static final long NIGHT_START_TIME = 13000L;
    public static final long NIGHT_END_TIME = 22800L;
    private static final int PERIMETER_SPAWN_MARGIN = 18;
    private static final int MARCH_UPDATE_INTERVAL_TICKS = 40;

    private static final Map<String, Long> LAST_SPAWNED_DAY_BY_DIMENSION = new ConcurrentHashMap<>();
    private static final Map<String, List<UUID>> ACTIVE_WAVE_MOBS = new ConcurrentHashMap<>();

    private NightWaveDefenseManager() {
    }

    public static void tick(ServerLevel level, int citizenCount) {
        if (level.isClientSide() || level.getDifficulty() == Difficulty.PEACEFUL) {
            return;
        }
        CityZoneManager manager = CityZoneManager.get(level);
        Optional<BlockPos> townHallOpt = manager.getTownHallPos();
        if (townHallOpt.isEmpty() && manager.getZones().isEmpty()) {
            return;
        }
        BlockPos townHallPos = townHallOpt.orElseGet(() -> manager.computeCityEnvelope().getCenter());

        long dayTime = level.getDayTime() % 24000L;
        long currentDayNumber = level.getDayTime() / 24000L;
        String dimKey = level.dimension().identifier().toString();

        if (dayTime >= NIGHT_START_TIME && dayTime <= NIGHT_END_TIME) {
            Long lastSpawnedDay = LAST_SPAWNED_DAY_BY_DIMENSION.get(dimKey);
            if (lastSpawnedDay == null || lastSpawnedDay < currentDayNumber) {
                LAST_SPAWNED_DAY_BY_DIMENSION.put(dimKey, currentDayNumber);
                spawnNightWave(level, manager, townHallPos, citizenCount);
            }
        }

        if ((level.getGameTime() % MARCH_UPDATE_INTERVAL_TICKS) == 0L) {
            directActiveWaveMonsters(level, townHallPos);
        }
    }

    public static int computeWaveMonsterCount(int citizenCount, int zoneCount) {
        int effectiveCitizens = Math.max(1, citizenCount);
        int developmentBonus = Math.max(0, zoneCount / 2);
        return Math.clamp(effectiveCitizens * 2 + developmentBonus, 2, 32);
    }

    public static List<Monster> spawnNightWave(
            ServerLevel level,
            CityZoneManager manager,
            BlockPos townHallPos,
            int citizenCount
    ) {
        BoundingBox cityBounds = manager.computeCityEnvelope();
        int totalMonsters = computeWaveMonsterCount(citizenCount, manager.getZones().size());
        RandomSource random = level.getRandom();
        List<Monster> spawned = new ArrayList<>(totalMonsters);
        String dimKey = level.dimension().identifier().toString();
        List<UUID> trackedIds = ACTIVE_WAVE_MOBS.computeIfAbsent(dimKey, k -> new ArrayList<>());

        int radiusX = Math.max(16, (cityBounds.getXSpan() / 2) + PERIMETER_SPAWN_MARGIN);
        int radiusZ = Math.max(16, (cityBounds.getZSpan() / 2) + PERIMETER_SPAWN_MARGIN);
        BlockPos center = cityBounds.getCenter();

        for (int i = 0; i < totalMonsters; i++) {
            double angle = (2.0D * Math.PI * i) / totalMonsters + (random.nextDouble() * 0.35D);
            int spawnX = center.getX() + (int) Math.round(Math.cos(angle) * radiusX);
            int spawnZ = center.getZ() + (int) Math.round(Math.sin(angle) * radiusZ);
            BlockPos columnPos = new BlockPos(spawnX, townHallPos.getY(), spawnZ);
            if (!level.isLoaded(columnPos)) {
                continue;
            }
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawnX, spawnZ);
            BlockPos spawnPos = new BlockPos(spawnX, Math.max(level.getMinY() + 1, surfaceY), spawnZ);

            Monster monster = createWaveMonster(level, random, i);
            if (monster == null) {
                continue;
            }
            monster.snapTo(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D, random.nextFloat() * 360.0F, 0.0F);
            monster.setPersistenceRequired();
            monster.restrictTo(townHallPos, 12);

            if (level.addFreshEntity(monster)) {
                monster.getNavigation().moveTo(townHallPos.getX() + 0.5D, townHallPos.getY(), townHallPos.getZ() + 0.5D, 1.15D);
                spawned.add(monster);
                trackedIds.add(monster.getUUID());
            }
        }
        return spawned;
    }

    private static Monster createWaveMonster(ServerLevel level, RandomSource random, int index) {
        Monster monster;
        int roll = (index + random.nextInt(4)) % 4;
        if (roll == 0) {
            monster = EntityType.SKELETON.create(level, EntitySpawnReason.EVENT);
            if (monster != null) {
                monster.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            }
        } else if (roll == 1) {
            monster = EntityType.SPIDER.create(level, EntitySpawnReason.EVENT);
        } else if (roll == 2) {
            monster = EntityType.HUSK.create(level, EntitySpawnReason.EVENT);
        } else {
            monster = EntityType.ZOMBIE.create(level, EntitySpawnReason.EVENT);
            if (monster != null && random.nextFloat() < 0.35F) {
                monster.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            }
        }
        return monster;
    }

    private static void directActiveWaveMonsters(ServerLevel level, BlockPos townHallPos) {
        String dimKey = level.dimension().identifier().toString();
        List<UUID> tracked = ACTIVE_WAVE_MOBS.get(dimKey);
        if (tracked == null || tracked.isEmpty()) {
            return;
        }
        Iterator<UUID> iterator = tracked.iterator();
        while (iterator.hasNext()) {
            UUID id = iterator.next();
            if (!(level.getEntity(id) instanceof Monster monster) || !monster.isAlive()) {
                iterator.remove();
                continue;
            }
            if (monster.getTarget() == null && monster.getNavigation().isDone()) {
                if (monster.blockPosition().distSqr(townHallPos) > 9.0D) {
                    monster.getNavigation().moveTo(
                            townHallPos.getX() + 0.5D,
                            townHallPos.getY(),
                            townHallPos.getZ() + 0.5D,
                            1.15D
                    );
                }
            }
        }
    }
}
