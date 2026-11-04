package com.citiescolony;

import com.citiescolony.api.ColonyTask;
import com.citiescolony.api.JobType;
import com.citiescolony.api.TaskState;
import com.citiescolony.api.ZoneData;
import com.citiescolony.api.ZoneType;
import com.citiescolony.client.MouseRaycaster;
import com.citiescolony.common.city.CityZoneManager;
import com.citiescolony.common.city.NightWaveDefenseManager;
import com.citiescolony.common.city.ZoneTaskGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitiesColonyCoreTest {

    @Test
    void testZoneDataCreationAndCitizenAssignment() {
        BlockPos start = new BlockPos(10, 64, 10);
        BlockPos end = new BlockPos(20, 68, 20);
        ZoneData zone = ZoneData.fromCorners(ZoneType.LOGGING, start, end);

        assertTrue(zone.contains(new BlockPos(15, 65, 15)));
        assertFalse(zone.contains(new BlockPos(5, 65, 5)));
        assertTrue(zone.assignedCitizens().isEmpty());

        UUID citizenId = UUID.randomUUID();
        ZoneData assigned = zone.withAssignedCitizen(citizenId);
        assertEquals(1, assigned.assignedCitizens().size());
        assertTrue(assigned.assignedCitizens().contains(citizenId));

        ZoneData unassigned = assigned.withoutAssignedCitizen(citizenId);
        assertTrue(unassigned.assignedCitizens().isEmpty());
    }

    @Test
    void testColonyTaskStateMachineAndOrdering() {
        ColonyTask chopTask = new ColonyTask(ColonyTask.TaskType.CHOP, new BlockPos(0, 64, 0), 50);
        ColonyTask guardTask = new ColonyTask(ColonyTask.TaskType.GUARD, new BlockPos(0, 64, 0), 100);

        assertTrue(guardTask.compareTo(chopTask) < 0, "Higher priority task should sort first");
        assertEquals(TaskState.PENDING, chopTask.state());

        UUID workerId = UUID.randomUUID();
        assertTrue(chopTask.assignTo(workerId));
        assertEquals(TaskState.ASSIGNED, chopTask.state());
        assertFalse(chopTask.assignTo(UUID.randomUUID()), "Cannot reassign an already assigned task");

        chopTask.markInProgress();
        assertEquals(TaskState.IN_PROGRESS, chopTask.state());

        chopTask.markCompleted();
        assertTrue(chopTask.state().isTerminal());
    }

    @Test
    void testCityZoneManagerSpatialIndexAndCanonicalTasks() {
        CityZoneManager manager = new CityZoneManager(List.of(), Optional.empty(), List.of());
        ZoneData loggingZone = manager.createZone(ZoneType.LOGGING, new BlockPos(0, 64, 0), new BlockPos(8, 70, 8));
        assertEquals(1, manager.getZonesByType(ZoneType.LOGGING).size());

        // Town Hall and Guard Outpost registration lifecycle
        BlockPos townHallBlockPos = new BlockPos(100, 65, 100);
        manager.setTownHallPos(townHallBlockPos);
        assertEquals(Optional.of(townHallBlockPos), manager.getTownHallPos());

        BlockPos outpostPos = new BlockPos(120, 65, 120);
        manager.registerGuardOutpost(outpostPos);
        assertEquals(Optional.of(outpostPos), manager.findNearestGuardOutpost(BlockPos.ZERO));
        manager.unregisterGuardOutpost(outpostPos);
        assertTrue(manager.findNearestGuardOutpost(BlockPos.ZERO).isEmpty());

        // O(1) spatial index check
        BlockPos chopPos = new BlockPos(2, 65, 2);
        ColonyTask chop1 = new ColonyTask(ColonyTask.TaskType.CHOP, chopPos, 60);
        assertTrue(manager.enqueueTask(chop1));
        assertTrue(manager.hasActiveTaskAt(chopPos));
        assertFalse(manager.enqueueTask(new ColonyTask(ColonyTask.TaskType.CHOP, chopPos, 60)),
                "Duplicate active task at same position should be rejected in O(1)");

        // Canonical task rebinding after deserialization
        ColonyTask deserializedClone = new ColonyTask(
                chop1.taskId(),
                chop1.type(),
                chop1.targetPos(),
                chop1.priority(),
                TaskState.ASSIGNED,
                Optional.of(UUID.randomUUID())
        );
        ColonyTask canonical = manager.resolveCanonicalTask(deserializedClone);
        assertSame(chop1, canonical, "Deserialized task must rebind to the canonical instance in taskQueue");

        UUID lumberjackId = UUID.randomUUID();
        assertTrue(manager.pollNextTaskFor(JobType.MINER, lumberjackId, BlockPos.ZERO).isEmpty(),
                "Miner should not receive CHOP task");

        Optional<ColonyTask> polled = manager.pollNextTaskFor(JobType.LUMBERJACK, lumberjackId, BlockPos.ZERO);
        assertTrue(polled.isPresent());
        assertEquals(chop1.taskId(), polled.get().taskId());

        manager.removeZonesIntersecting(new BoundingBox(0, 60, 0, 10, 75, 10));
        assertTrue(manager.getZones().isEmpty());
        assertFalse(manager.hasActiveTaskAt(chopPos), "Spatial index should be cleaned when zone is removed");
        assertFalse(manager.removeZone(loggingZone.id()));
    }

    @Test
    void testStaircaseMiningConstraintAndCourierDeliverSupport() {
        BoundingBox mineBox = new BoundingBox(0, 50, 0, 10, 64, 10);
        // At entry edge x=0, only top layer y=64 is allowed so staircase entrance stays intact
        assertTrue(ZoneTaskGenerator.isWithinStaircaseMiningConstraint(mineBox, new BlockPos(0, 64, 0)));
        assertFalse(ZoneTaskGenerator.isWithinStaircaseMiningConstraint(mineBox, new BlockPos(0, 60, 0)));
        // Deeper inside at x=6, mining down to y=58 is allowed
        assertTrue(ZoneTaskGenerator.isWithinStaircaseMiningConstraint(mineBox, new BlockPos(6, 58, 5)));

        assertTrue(JobType.COURIER.canExecute(ColonyTask.TaskType.DELIVER));
    }

    @Test
    void testNightWaveScalingFormula() {
        assertEquals(2, NightWaveDefenseManager.computeWaveMonsterCount(0, 0));
        assertEquals(12, NightWaveDefenseManager.computeWaveMonsterCount(5, 4));
        assertEquals(32, NightWaveDefenseManager.computeWaveMonsterCount(50, 20));
    }

    @Test
    void testAssignedCitizenZoneTaskPriority() {
        CityZoneManager manager = new CityZoneManager(List.of(), Optional.empty(), List.of());
        ZoneData zoneA = manager.createZone(ZoneType.LOGGING, new BlockPos(0, 64, 0), new BlockPos(10, 70, 10));
        ZoneData zoneB = manager.createZone(ZoneType.LOGGING, new BlockPos(50, 64, 50), new BlockPos(60, 70, 60));

        ColonyTask taskInZoneA = new ColonyTask(ColonyTask.TaskType.CHOP, new BlockPos(2, 65, 2), 60);
        ColonyTask taskInZoneB = new ColonyTask(ColonyTask.TaskType.CHOP, new BlockPos(52, 65, 52), 60);
        manager.enqueueTask(taskInZoneA);
        manager.enqueueTask(taskInZoneB);

        UUID citizenAssignedToB = UUID.randomUUID();
        assertTrue(manager.assignCitizenToZone(zoneB.id(), citizenAssignedToB));
        assertEquals(Optional.of(zoneB.id()), manager.getAssignedZoneForCitizen(citizenAssignedToB).map(ZoneData::id));

        Optional<ColonyTask> polled = manager.pollNextTaskFor(JobType.LUMBERJACK, citizenAssignedToB, BlockPos.ZERO);
        assertTrue(polled.isPresent());
        assertEquals(taskInZoneB.taskId(), polled.get().taskId(),
                "Citizen assigned to Zone B should prioritize tasks inside Zone B even when standing closer to Zone A");
    }

    @Test
    void testMouseRaycasterUnprojectionProducesNormalizedDirection() {
        Vec3 camPos = new Vec3(0.0D, 88.0D, 0.0D);
        Matrix4f view = new Matrix4f().rotationX((float) Math.toRadians(52.5F));
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70.0F), 16.0F / 9.0F, 0.05F, 256.0F);

        MouseRaycaster.Ray ray = MouseRaycaster.unprojectMouseToWorldRay(
                960.0D, 540.0D, 1920, 1080, camPos, view, proj
        );
        assertEquals(camPos, ray.origin());
        assertEquals(1.0D, ray.direction().length(), 1.0E-4D);
        assertTrue(ray.direction().y < 0.0D, "Quarter-view center ray should point downward toward terrain");
    }
}
