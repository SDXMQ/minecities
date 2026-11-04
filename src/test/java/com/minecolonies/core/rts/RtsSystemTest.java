package com.minecolonies.core.rts;

import com.minecolonies.api.configuration.RtsZonePlacementMode;
import com.minecolonies.core.client.rts.RtsCameraController;
import com.minecolonies.core.client.rts.RtsMousePicker;
import com.minecolonies.core.network.messages.client.RtsSyncMessage;
import com.minecolonies.core.network.messages.server.RtsCitizenCommandMessage;
import com.minecolonies.core.network.messages.server.RtsZoneActionMessage;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Self-contained unit test suite for the MineColonies 1.20.1 RTS camera, mouse raycaster, zone persistence,
 * placement mode configuration, and network packet serialization.
 */
public class RtsSystemTest
{
    public static void main(final String[] args)
    {
        final RtsSystemTest suite = new RtsSystemTest();
        suite.runAllTests();
    }

    public void runAllTests()
    {
        testRtsZoneTypeCyclingAndMetadata();
        testRtsZonePlacementModeCycling();
        testRtsZoneDataNbtAndByteSerialization();
        testRtsZoneSavedDataCrudAndIntersectionRemoval();
        testRtsMousePickerRayUnprojection();
        testRtsCameraControllerClampingAndPanning();
        testRtsNetworkPacketsSerialization();
    }

    public void testRtsZoneTypeCyclingAndMetadata()
    {
        RtsZoneType current = RtsZoneType.RESIDENTIAL;
        assertEquals(RtsZoneType.LOGGING, current = current.next());
        assertEquals(RtsZoneType.MINING, current = current.next());
        assertEquals(RtsZoneType.FARMING, current = current.next());
        assertEquals(RtsZoneType.STORAGE, current = current.next());
        assertEquals(RtsZoneType.DEFENSE, current = current.next());
        assertEquals(RtsZoneType.RESIDENTIAL, current.next());

        for (final RtsZoneType type : RtsZoneType.values())
        {
            assertNotNull(type.getDisplayName());
            assertFalse(type.getDisplayName().isEmpty());
            assertNotNull(type.getDefaultSchematicName());
            assertFalse(type.getDefaultSchematicName().isEmpty());
            assertNotNull(type.getDefaultJobId());
            assertTrue(type.getRed() >= 0.0F && type.getRed() <= 1.0F);
            assertTrue(type.getGreen() >= 0.0F && type.getGreen() <= 1.0F);
            assertTrue(type.getBlue() >= 0.0F && type.getBlue() <= 1.0F);
        }
    }

    public void testRtsZonePlacementModeCycling()
    {
        final RtsZonePlacementMode modeA = RtsZonePlacementMode.MODE_A_AUTO_PLACE;
        final RtsZonePlacementMode modeB = RtsZonePlacementMode.MODE_B_BLUEPRINT_PREVIEW;

        assertEquals(modeB, modeA.next());
        assertEquals(modeA, modeB.next());
        assertTrue(modeA.getDisplayName().contains("방식A"));
        assertTrue(modeB.getDisplayName().contains("방식B"));
    }

    public void testRtsZoneDataNbtAndByteSerialization()
    {
        final UUID id = UUID.randomUUID();
        final BoundingBox box = new BoundingBox(-10, 64, 20, 5, 70, 35);
        final BlockPos linkedPos = new BlockPos(-2, 65, 27);
        final RtsZoneData original = new RtsZoneData(id, 3, RtsZoneType.LOGGING, box, linkedPos);

        final CompoundTag tag = original.saveToTag();
        final RtsZoneData fromNbt = RtsZoneData.loadFromTag(tag);
        assertEquals(original.id(), fromNbt.id());
        assertEquals(original.colonyId(), fromNbt.colonyId());
        assertEquals(original.type(), fromNbt.type());
        assertEquals(original.bounds(), fromNbt.bounds());
        assertEquals(original.linkedBuildingPos(), fromNbt.linkedBuildingPos());

        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.writeToBuf(buf);
        final RtsZoneData fromBuf = RtsZoneData.readFromBuf(buf);
        assertEquals(original.id(), fromBuf.id());
        assertEquals(original.colonyId(), fromBuf.colonyId());
        assertEquals(original.type(), fromBuf.type());
        assertEquals(original.bounds(), fromBuf.bounds());
        assertEquals(original.linkedBuildingPos(), fromBuf.linkedBuildingPos());

        // Edge case: null linkedBuildingPos
        final RtsZoneData withoutBuilding = new RtsZoneData(UUID.randomUUID(), 0, RtsZoneType.FARMING, box, null);
        final RtsZoneData fromNbtNull = RtsZoneData.loadFromTag(withoutBuilding.saveToTag());
        assertNull(fromNbtNull.linkedBuildingPos());

        final FriendlyByteBuf bufNull = new FriendlyByteBuf(Unpooled.buffer());
        withoutBuilding.writeToBuf(bufNull);
        final RtsZoneData fromBufNull = RtsZoneData.readFromBuf(bufNull);
        assertNull(fromBufNull.linkedBuildingPos());
    }

    public void testRtsZoneSavedDataCrudAndIntersectionRemoval()
    {
        final RtsZoneSavedData savedData = new RtsZoneSavedData();
        assertTrue(savedData.getZones().isEmpty());

        final RtsZoneData zone1 = new RtsZoneData(
            UUID.randomUUID(),
            1,
            RtsZoneType.RESIDENTIAL,
            new BoundingBox(0, 64, 0, 10, 68, 10),
            new BlockPos(5, 64, 5)
        );
        final RtsZoneData zone2 = new RtsZoneData(
            UUID.randomUUID(),
            2,
            RtsZoneType.MINING,
            new BoundingBox(50, 64, 50, 60, 68, 60),
            null
        );

        savedData.addOrReplaceZone(zone1);
        savedData.addOrReplaceZone(zone2);
        assertEquals(2, savedData.getZones().size());
        assertEquals(1, savedData.getZonesForColony(1).size());
        assertEquals(1, savedData.getZonesForColony(2).size());

        // Overlapping zone in colony 1 should replace zone1
        final RtsZoneData overlapping = new RtsZoneData(
            UUID.randomUUID(),
            1,
            RtsZoneType.STORAGE,
            new BoundingBox(5, 64, 5, 15, 68, 15),
            new BlockPos(10, 64, 10)
        );
        savedData.addOrReplaceZone(overlapping);
        assertEquals(2, savedData.getZones().size());
        assertEquals(RtsZoneType.STORAGE, savedData.getZonesForColony(1).get(0).type());

        // Round-trip save and load
        final CompoundTag rootTag = savedData.save(new CompoundTag());
        final RtsZoneSavedData loaded = RtsZoneSavedData.load(rootTag);
        assertEquals(2, loaded.getZones().size());

        // Remove intersecting
        final int removed = loaded.removeZonesIntersecting(new BoundingBox(48, 60, 48, 52, 70, 52));
        assertEquals(1, removed);
        assertEquals(1, loaded.getZones().size());
    }

    public void testRtsMousePickerRayUnprojection()
    {
        final Vec3 camPos = new Vec3(100.0D, 90.0D, -50.0D);
        final Matrix4f viewMatrix = new Matrix4f().identity();
        final Matrix4f projMatrix = new Matrix4f().perspective((float) Math.toRadians(70.0D), 16.0F / 9.0F, 0.05F, 256.0F);

        // Center of screen should point straight along -Z in identity view space
        final RtsMousePicker.Ray centerRay = RtsMousePicker.unprojectMouseToWorldRay(
            960.0D,
            540.0D,
            1920.0D,
            1080.0D,
            camPos,
            viewMatrix,
            projMatrix
        );
        assertEquals(camPos, centerRay.origin());
        assertEquals(1.0D, centerRay.direction().length(), 1.0E-5D);
        assertEquals(0.0D, centerRay.direction().x, 1.0E-4D);
        assertEquals(0.0D, centerRay.direction().y, 1.0E-4D);
        assertTrue(centerRay.direction().z < -0.99D);

        // Degenerate zero screen dimensions should not produce NaN
        final RtsMousePicker.Ray degenerateRay = RtsMousePicker.unprojectMouseToWorldRay(
            0.0D,
            0.0D,
            0.0D,
            0.0D,
            camPos,
            viewMatrix,
            projMatrix
        );
        assertFalse(Double.isNaN(degenerateRay.direction().x));
        assertFalse(Double.isNaN(degenerateRay.direction().y));
        assertFalse(Double.isNaN(degenerateRay.direction().z));
        assertEquals(1.0D, degenerateRay.direction().length(), 1.0E-5D);
    }

    public void testRtsCameraControllerClampingAndPanning()
    {
        assertEquals(15.0D, RtsCameraController.clampAltitude(2.0D), 1.0E-6D);
        assertEquals(25.0D, RtsCameraController.clampAltitude(25.0D), 1.0E-6D);
        assertEquals(45.0D, RtsCameraController.clampAltitude(100.0D), 1.0E-6D);

        assertEquals(45.0F, RtsCameraController.clampPitch(10.0F), 1.0E-5F);
        assertEquals(52.5F, RtsCameraController.clampPitch(52.5F), 1.0E-5F);
        assertEquals(60.0F, RtsCameraController.clampPitch(89.0F), 1.0E-5F);

        // Forward + Backward should cancel out
        final Vec3 cancelled = RtsCameraController.computePanDelta(0.0F, 25.0D, true, true, false, false);
        assertEquals(0.0D, cancelled.length(), 1.0E-6D);

        // Higher altitude should pan proportionally faster than lower altitude
        final Vec3 lowPan = RtsCameraController.computePanDelta(0.0F, 15.0D, true, false, false, false);
        final Vec3 highPan = RtsCameraController.computePanDelta(0.0F, 45.0D, true, false, false, false);
        assertTrue(highPan.length() > lowPan.length());
    }

    public void testRtsNetworkPacketsSerialization()
    {
        final RtsZoneData zone = new RtsZoneData(
            UUID.randomUUID(),
            7,
            RtsZoneType.DEFENSE,
            new BoundingBox(1, 64, 1, 8, 70, 8),
            new BlockPos(4, 64, 4)
        );
        final RtsResourceAggregator.ColonySnapshot snap = new RtsResourceAggregator.ColonySnapshot(
            7, 120, 340, 210, 55, 18, 25, 2, 8.4D, List.of(zone)
        );

        final RtsSyncMessage syncIn = new RtsSyncMessage(snap);
        final FriendlyByteBuf syncBuf = new FriendlyByteBuf(Unpooled.buffer());
        syncIn.toBytes(syncBuf);
        final RtsSyncMessage syncOut = new RtsSyncMessage();
        syncOut.fromBytes(syncBuf);
        syncOut.onExecute(null, false);

        final RtsResourceAggregator.ColonySnapshot syncedClient = RtsResourceAggregator.getClientSnapshot();
        assertEquals(7, syncedClient.colonyId());
        assertEquals(120, syncedClient.food());
        assertEquals(340, syncedClient.wood());
        assertEquals(210, syncedClient.stone());
        assertEquals(55, syncedClient.minerals());
        assertEquals(18, syncedClient.totalCitizens());
        assertEquals(25, syncedClient.maxCitizens());
        assertEquals(2, syncedClient.idleCitizens());
        assertEquals(8.4D, syncedClient.overallHappiness(), 1.0E-5D);
        assertEquals(1, syncedClient.zones().size());

        final RtsZoneActionMessage zoneMsgIn = new RtsZoneActionMessage(
            new BlockPos(0, 64, 0),
            new BlockPos(12, 64, 12),
            RtsZoneType.FARMING,
            false,
            RtsZonePlacementMode.MODE_B_BLUEPRINT_PREVIEW
        );
        final FriendlyByteBuf zoneBuf = new FriendlyByteBuf(Unpooled.buffer());
        zoneMsgIn.toBytes(zoneBuf);
        final RtsZoneActionMessage zoneMsgOut = new RtsZoneActionMessage();
        zoneMsgOut.fromBytes(zoneBuf);

        final RtsCitizenCommandMessage cmdIn = new RtsCitizenCommandMessage(
            7,
            List.of(101, 102, 103),
            new BlockPos(20, 65, -15),
            "lumberjack"
        );
        final FriendlyByteBuf cmdBuf = new FriendlyByteBuf(Unpooled.buffer());
        cmdIn.toBytes(cmdBuf);
        final RtsCitizenCommandMessage cmdOut = new RtsCitizenCommandMessage();
        cmdOut.fromBytes(cmdBuf);
    }

    private static void assertEquals(final Object expected, final Object actual)
    {
        if (!Objects.equals(expected, actual))
        {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertEquals(final double expected, final double actual, final double delta)
    {
        if (Math.abs(expected - actual) > delta)
        {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + "> (delta " + delta + ")");
        }
    }

    private static void assertEquals(final float expected, final float actual, final float delta)
    {
        if (Math.abs(expected - actual) > delta)
        {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + "> (delta " + delta + ")");
        }
    }

    private static void assertTrue(final boolean condition)
    {
        if (!condition)
        {
            throw new AssertionError("Expected condition to be true");
        }
    }

    private static void assertFalse(final boolean condition)
    {
        if (condition)
        {
            throw new AssertionError("Expected condition to be false");
        }
    }

    private static void assertNotNull(final Object obj)
    {
        if (obj == null)
        {
            throw new AssertionError("Expected non-null reference");
        }
    }

    private static void assertNull(final Object obj)
    {
        if (obj != null)
        {
            throw new AssertionError("Expected null reference but was <" + obj + ">");
        }
    }
}
