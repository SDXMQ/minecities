package com.citiescolony.common.city;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spawns and directs night monster waves against the city Town Hall when world time reaches night (>= 13000 ticks).
 * Stores wave state inside world-scoped CityZoneManager (SavedData) instead of static maps to prevent
 * cross-world state pollution in singleplayer, and boosts monster FOLLOW_RANGE to cover large city radii.
 */
public final class NightWaveDefenseManager {
    public static final long NIGHT_START_TIME = 13000L;
    public static final long NIGHT_END_TIME = 22800L;
    private static final int PERIMETER_SPAWN_MARGIN = 18;
    private static final int MARCH_UPDATE_INTERVAL_TICKS = 40;

    private NightWaveDefenseManager() {
    }

    public static void tick(ServerLevel level, int citizenCount) {
        if (level.isClientSide()
                || level != level.getServer().overworld()
                || level.getDifficulty() == Difficulty.PEACEFUL) {
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

        if (dayTime >= NIGHT_START_TIME && dayTime <= NIGHT_END_TIME) {
            long lastSpawnedDay = manager.getLastSpawnedWaveDay();
            if (lastSpawnedDay < currentDayNumber) {
                manager.setLastSpawnedWaveDay(currentDayNumber);
                spawnNightWave(level, manager, townHallPos, citizenCount);
            }
        }

        if ((level.getGameTime() % MARCH_UPDATE_INTERVAL_TICKS) == 0L) {
            directActiveWaveMonsters(level, manager, townHallPos);
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

            // Expand FOLLOW_RANGE beyond the distance from spawnPos to townHallPos so navigation never fails on large cities
            double distToTownHall = Math.sqrt(spawnPos.distSqr(townHallPos));
            AttributeInstance followRange = monster.getAttribute(Attributes.FOLLOW_RANGE);
            if (followRange != null) {
                followRange.setBaseValue(Math.max(64.0D, distToTownHall + 32.0D));
            }
            monster.restrictTo(townHallPos, 12);

            if (level.addFreshEntity(monster)) {
                issueMarchOrderTowardTownHall(monster, townHallPos);
                spawned.add(monster);
                manager.addActiveWaveMob(monster.getUUID());
            }
        }
        return spawned;
    }

    private static void issueMarchOrderTowardTownHall(Monster monster, BlockPos townHallPos) {
        double dx = (townHallPos.getX() + 0.5D) - monster.getX();
        double dy = townHallPos.getY() - monster.getY();
        double dz = (townHallPos.getZ() + 0.5D) - monster.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        AttributeInstance followRange = monster.getAttribute(Attributes.FOLLOW_RANGE);
        if (followRange != null && followRange.getBaseValue() < dist + 24.0D) {
            followRange.setBaseValue(Math.max(64.0D, dist + 32.0D));
        }

        if (!monster.getNavigation().moveTo(townHallPos.getX() + 0.5D, townHallPos.getY(), townHallPos.getZ() + 0.5D, 1.15D)
                && dist > 24.0D) {
            double stepScale = 24.0D / dist;
            double wpX = monster.getX() + dx * stepScale;
            double wpY = monster.getY() + dy * stepScale;
            double wpZ = monster.getZ() + dz * stepScale;
            monster.getNavigation().moveTo(wpX, wpY, wpZ, 1.15D);
        }
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

    private static void directActiveWaveMonsters(ServerLevel level, CityZoneManager manager, BlockPos townHallPos) {
        synchronized (manager) {
            List<UUID> tracked = manager.getMutableActiveWaveMobIds();
            if (tracked.isEmpty()) {
                return;
            }
            boolean removedAny = false;
            Iterator<UUID> iterator = tracked.iterator();
            while (iterator.hasNext()) {
                UUID id = iterator.next();
                if (!(level.getEntity(id) instanceof Monster monster) || !monster.isAlive()) {
                    iterator.remove();
                    removedAny = true;
                    continue;
                }
                if (monster.getTarget() == null && monster.getNavigation().isDone()) {
                    if (monster.blockPosition().distSqr(townHallPos) > 9.0D) {
                        issueMarchOrderTowardTownHall(monster, townHallPos);
                    }
                }
            }
            if (removedAny) {
                manager.setDirty();
            }
        }
    }
}
