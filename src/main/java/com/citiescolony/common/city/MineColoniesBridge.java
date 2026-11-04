package com.citiescolony.common.city;

import com.citiescolony.api.JobType;
import com.citiescolony.api.ZoneData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Bridge connecting CitiesColony's RTS camera, ZoneData, and citizen commands with MineColonies'
 * IColony, ICitizenData, and IBuilding APIs when running inside the unified MineColonies runtime.
 */
public final class MineColoniesBridge {

    private static Boolean mineColoniesAvailable = null;

    private MineColoniesBridge() {
    }

    public static boolean isMineColoniesLoaded() {
        if (mineColoniesAvailable == null) {
            try {
                Class.forName("com.minecolonies.api.colony.IColonyManager");
                mineColoniesAvailable = Boolean.TRUE;
            } catch (ClassNotFoundException e) {
                mineColoniesAvailable = Boolean.FALSE;
            }
        }
        return mineColoniesAvailable;
    }

    /**
     * Synchronizes a newly created or updated CitiesColony ZoneData with MineColonies' IColony building
     * and RTS zone saved data when MineColonies is active in the runtime.
     */
    public static void syncZoneWithMineColonies(ServerLevel level, ZoneData zone, boolean deleteMode) {
        if (!isMineColoniesLoaded() || level == null || zone == null) {
            return;
        }
        try {
            Class<?> savedDataClass = Class.forName("com.minecolonies.core.rts.RtsZoneSavedData");
            Method getMethod = savedDataClass.getMethod("get", ServerLevel.class);
            Object rtsSavedData = getMethod.invoke(null, level);
            if (deleteMode) {
                Method removeMethod = savedDataClass.getMethod(
                        "removeZonesIntersecting",
                        net.minecraft.world.level.levelgen.structure.BoundingBox.class
                );
                removeMethod.invoke(rtsSavedData, zone.bounds());
            } else {
                Class<?> rtsZoneTypeClass = Class.forName("com.minecolonies.core.rts.RtsZoneType");
                Object mappedType = Enum.valueOf((Class<Enum>) rtsZoneTypeClass.asSubclass(Enum.class), zone.type().name());
                Method createZoneMethod = savedDataClass.getMethod(
                        "createZone",
                        int.class,
                        rtsZoneTypeClass,
                        BlockPos.class,
                        BlockPos.class,
                        BlockPos.class
                );
                BlockPos min = new BlockPos(zone.bounds().minX(), zone.bounds().minY(), zone.bounds().minZ());
                BlockPos max = new BlockPos(zone.bounds().maxX(), zone.bounds().maxY(), zone.bounds().maxZ());
                createZoneMethod.invoke(rtsSavedData, 0, mappedType, min, max, zone.center());
            }
        } catch (Throwable ignored) {
            // Gracefully operate in standalone mode if MineColonies classes differ
        }
    }

    /**
     * Forwards RTS move/job commands to MineColonies ICitizenData / AbstractEntityCitizen instances if targeted.
     */
    public static void forwardCitizenCommandToMineColonies(
            ServerLevel level,
            List<Integer> entityIds,
            BlockPos targetPos,
            JobType jobType
    ) {
        if (!isMineColoniesLoaded() || level == null || entityIds == null || entityIds.isEmpty()) {
            return;
        }
        try {
            Class<?> citizenClass = Class.forName("com.minecolonies.api.entity.citizen.AbstractEntityCitizen");
            Method getCitizenData = citizenClass.getMethod("getCitizenData");
            for (int id : entityIds) {
                var entity = level.getEntity(id);
                if (entity != null && citizenClass.isInstance(entity) && targetPos != null) {
                    if (entity instanceof net.minecraft.world.entity.Mob mob) {
                        mob.getNavigation().moveTo(
                                targetPos.getX() + 0.5D,
                                targetPos.getY(),
                                targetPos.getZ() + 0.5D,
                                1.15D
                        );
                    }
                    Object citizenData = getCitizenData.invoke(entity);
                    if (citizenData != null && jobType != null) {
                        Method setPaused = citizenData.getClass().getMethod("setPaused", boolean.class);
                        setPaused.invoke(citizenData, false);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
