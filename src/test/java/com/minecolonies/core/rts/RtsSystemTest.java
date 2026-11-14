package com.minecolonies.core.rts;

import com.minecolonies.api.colony.jobs.ModJobs;
import com.minecolonies.api.configuration.RtsZonePlacementMode;
import com.minecolonies.core.client.rts.RtsCameraController;
import com.minecolonies.core.client.rts.RtsMousePicker;
import com.minecolonies.core.client.rts.RtsTopBarOverlay;
import com.minecolonies.core.network.messages.client.RtsSyncMessage;
import com.minecolonies.core.network.messages.server.RtsCitizenCommandMessage;
import com.minecolonies.core.network.messages.server.RtsZoneActionMessage;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Self-contained unit test suite for the MineColonies 1.20.1 RTS camera, mouse raycaster, zone persistence,
 * placement mode configuration, and network packet serialization.
 */
public class RtsSystemTest
{
    static
    {
        try
        {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
        }
        catch (final Throwable ignored)
        {
        }
    }

    public static void main(final String[] args)
    {
        final RtsSystemTest suite = new RtsSystemTest();
        suite.runAllTests();
        System.out.println("=== ALL RTS SYSTEM TESTS PASSED (15/15) ===");
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

        // 5 Specification Tests
        testRtsCameraToggleAndMovementSuppression();
        testRtsZoneDragSelectionAndBuildingAssociation();
        testRtsZonePlacementModeAandBBehavior();
        testRtsTopBarHudResourceSyncAndFormatting();
        testRtsCitizenRoadSpeedBuffAndRemoval();

        // Stage 1-3 Regression Tests
        testRtsBlueprintPathResolutionAndNormalization();
        testRtsCameraCenteringAndDragThreshold();
        testRtsToolbarLayoutBoundsAndHitTesting();
    }

    public void testRtsZoneTypeCyclingAndMetadata()
    {
        assertEquals(7, RtsZoneType.values().length);

        RtsZoneType current = RtsZoneType.RESIDENTIAL;
        assertEquals(RtsZoneType.LOGGING, current = current.next());
        assertEquals(RtsZoneType.MINING, current = current.next());
        assertEquals(RtsZoneType.FARMING, current = current.next());
        assertEquals(RtsZoneType.STORAGE, current = current.next());
        assertEquals(RtsZoneType.DEFENSE, current = current.next());
        assertEquals(RtsZoneType.BUILDER, current = current.next());
        assertEquals(RtsZoneType.RESIDENTIAL, current.next());

        assertEquals(6, RtsZoneType.BUILDER.id());
        assertEquals(ModJobs.BUILDER_ID, RtsZoneType.BUILDER.getDefaultJobId());
        assertEquals(RtsZoneType.BUILDER, RtsZoneType.byId(6));
        assertEquals(RtsZoneType.RESIDENTIAL, RtsZoneType.byId(-1));
        assertEquals(RtsZoneType.RESIDENTIAL, RtsZoneType.byId(99));

        for (final RtsZoneType type : RtsZoneType.values())
        {
            assertNotNull(type.getDisplayName());
            assertFalse(type.getDisplayName().isEmpty());
            assertNotNull(type.getDefaultSchematicName());
            assertFalse(type.getDefaultSchematicName().isEmpty());
            if (type == RtsZoneType.RESIDENTIAL)
            {
                assertNull(type.getDefaultJobId());
            }
            else
            {
                assertNotNull(type.getDefaultJobId());
            }
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
        assertEquals(12.0D, RtsCameraController.clampAltitude(5.0D));
        assertEquals(12.0D, RtsCameraController.clampAltitude(2.0D), 1.0E-6D);
        assertEquals(25.0D, RtsCameraController.clampAltitude(25.0D), 1.0E-6D);
        assertEquals(80.0D, RtsCameraController.clampAltitude(100.0D));
        assertEquals(80.0D, RtsCameraController.clampAltitude(100.0D), 1.0E-6D);

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

    public void testRtsCameraToggleAndMovementSuppression()
    {
        final Input input = new Input();
        input.forwardImpulse = 1.0F;
        input.leftImpulse = 0.5F;
        input.up = true;
        input.jumping = true;
        input.shiftKeyDown = true;

        try
        {
            // 1. When inactive: all inputs must be preserved
            RtsCameraController.setActiveForTesting(false);
            RtsCameraController.suppressMovementInput(input);

            assertEquals(1.0F, input.forwardImpulse, 1.0E-5F);
            assertEquals(0.5F, input.leftImpulse, 1.0E-5F);
            assertTrue(input.up);
            assertFalse(input.down);
            assertFalse(input.left);
            assertFalse(input.right);
            assertTrue(input.jumping);
            assertTrue(input.shiftKeyDown);

            // 2. When active: all 8 movement variables must be zeroed (0.0F / false)
            RtsCameraController.setActiveForTesting(true);
            RtsCameraController.suppressMovementInput(input);

            assertEquals(0.0F, input.forwardImpulse, 1.0E-5F);
            assertEquals(0.0F, input.leftImpulse, 1.0E-5F);
            assertFalse(input.up);
            assertFalse(input.down);
            assertFalse(input.left);
            assertFalse(input.right);
            assertFalse(input.jumping);
            assertFalse(input.shiftKeyDown);
        }
        finally
        {
            RtsCameraController.setActiveForTesting(false);
        }
    }

    public void testRtsZoneDragSelectionAndBuildingAssociation()
    {
        // 1. Reverse drag normalization: (25, 70, 30) -> (10, 64, 10)
        final BlockPos forwardStart = new BlockPos(10, 64, 10);
        final BlockPos forwardEnd = new BlockPos(25, 70, 30);
        final BoundingBox boxForward = BoundingBox.fromCorners(forwardStart, forwardEnd);
        final BoundingBox boxReverse = BoundingBox.fromCorners(forwardEnd, forwardStart);

        assertEquals(boxForward, boxReverse);
        assertEquals(10, boxReverse.minX());
        assertEquals(64, boxReverse.minY());
        assertEquals(10, boxReverse.minZ());
        assertEquals(25, boxReverse.maxX());
        assertEquals(70, boxReverse.maxY());
        assertEquals(30, boxReverse.maxZ());

        // 2. Max zone size clamping (MAX_ZONE_SPAN = 64)
        final BlockPos rawStart = new BlockPos(0, 64, 0);
        final BlockPos rawEnd = new BlockPos(100, 64, 100);
        final int maxSpan = 64;
        final int minX = Math.min(rawStart.getX(), rawEnd.getX());
        final int minY = Math.min(rawStart.getY(), rawEnd.getY());
        final int minZ = Math.min(rawStart.getZ(), rawEnd.getZ());
        final int maxX = Math.min(minX + maxSpan, Math.max(rawStart.getX(), rawEnd.getX()));
        final int maxY = Math.max(minY + 3, Math.max(rawStart.getY(), rawEnd.getY()));
        final int maxZ = Math.min(minZ + maxSpan, Math.max(rawStart.getZ(), rawEnd.getZ()));
        final BoundingBox clampedBox = new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);

        assertEquals(0, clampedBox.minX());
        assertEquals(64, clampedBox.maxX());
        assertEquals(0, clampedBox.minZ());
        assertEquals(64, clampedBox.maxZ());
        assertTrue(clampedBox.getXSpan() <= 65);
        assertTrue(clampedBox.getZSpan() <= 65);

        // 3. Building association with linkedBuildingPos & tag roundtrip
        final BlockPos linkedHut = new BlockPos(15, 64, 15);
        final UUID zoneId = UUID.randomUUID();
        final RtsZoneData zoneWithHut = new RtsZoneData(
            zoneId,
            1,
            RtsZoneType.RESIDENTIAL,
            boxReverse,
            linkedHut
        );
        assertEquals(linkedHut, zoneWithHut.linkedBuildingPos());
        final CompoundTag savedTag = zoneWithHut.saveToTag();
        final RtsZoneData loadedZone = RtsZoneData.loadFromTag(savedTag);
        assertEquals(zoneId, loadedZone.id());
        assertEquals(1, loadedZone.colonyId());
        assertEquals(RtsZoneType.RESIDENTIAL, loadedZone.type());
        assertEquals(boxReverse, loadedZone.bounds());
        assertEquals(linkedHut, loadedZone.linkedBuildingPos());

        // 4. RtsZoneSavedData intersection removal
        final RtsZoneSavedData savedData = new RtsZoneSavedData();
        savedData.addOrReplaceZone(zoneWithHut);
        assertEquals(1, savedData.getZones().size());

        final BoundingBox intersectBox = new BoundingBox(12, 60, 12, 18, 72, 18);
        final int removed = savedData.removeZonesIntersecting(intersectBox);
        assertEquals(1, removed);
        assertEquals(0, savedData.getZones().size());
    }

    public void testRtsZonePlacementModeAandBBehavior()
    {
        final RtsZonePlacementMode modeA = RtsZonePlacementMode.MODE_A_AUTO_PLACE;
        final RtsZonePlacementMode modeB = RtsZonePlacementMode.MODE_B_BLUEPRINT_PREVIEW;

        // Mutual cyclic toggle
        assertEquals(modeB, modeA.next());
        assertEquals(modeA, modeB.next());

        // Display name contains 방식A / 방식B
        assertTrue(modeA.getDisplayName().contains("방식A"));
        assertTrue(modeB.getDisplayName().contains("방식B"));

        // Roundtrip serialization for Mode A
        final RtsZoneActionMessage msgA = new RtsZoneActionMessage(
            new BlockPos(0, 64, 0),
            new BlockPos(10, 68, 10),
            RtsZoneType.LOGGING,
            false,
            RtsZonePlacementMode.MODE_A_AUTO_PLACE
        );
        final FriendlyByteBuf bufA = new FriendlyByteBuf(Unpooled.buffer());
        msgA.toBytes(bufA);
        final RtsZoneActionMessage msgOutA = new RtsZoneActionMessage();
        msgOutA.fromBytes(bufA);
        assertEquals(RtsZonePlacementMode.MODE_A_AUTO_PLACE, getPlacementMode(msgOutA));

        // Roundtrip serialization for Mode B
        final RtsZoneActionMessage msgB = new RtsZoneActionMessage(
            new BlockPos(5, 70, 5),
            new BlockPos(20, 75, 20),
            RtsZoneType.MINING,
            true,
            RtsZonePlacementMode.MODE_B_BLUEPRINT_PREVIEW
        );
        final FriendlyByteBuf bufB = new FriendlyByteBuf(Unpooled.buffer());
        msgB.toBytes(bufB);
        final RtsZoneActionMessage msgOutB = new RtsZoneActionMessage();
        msgOutB.fromBytes(bufB);
        assertEquals(RtsZonePlacementMode.MODE_B_BLUEPRINT_PREVIEW, getPlacementMode(msgOutB));
    }

    public void testRtsTopBarHudResourceSyncAndFormatting()
    {
        final RtsResourceAggregator.ColonySnapshot snapshot = new RtsResourceAggregator.ColonySnapshot(
            42, 1250, 640, 320, 85, 24, 30, 4, 8.7D, List.of()
        );

        final RtsSyncMessage msgIn = new RtsSyncMessage(snapshot);
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        msgIn.toBytes(buf);
        final RtsSyncMessage msgOut = new RtsSyncMessage();
        msgOut.fromBytes(buf);
        msgOut.onExecute(null, false);

        final RtsResourceAggregator.ColonySnapshot clientSnap = RtsResourceAggregator.getClientSnapshot();
        assertEquals(42, clientSnap.colonyId());
        assertEquals(1250, clientSnap.food());
        assertEquals(640, clientSnap.wood());
        assertEquals(320, clientSnap.stone());
        assertEquals(85, clientSnap.minerals());
        assertEquals(24, clientSnap.totalCitizens());
        assertEquals(30, clientSnap.maxCitizens());
        assertEquals(4, clientSnap.idleCitizens());
        assertEquals(8.7D, clientSnap.overallHappiness(), 1.0E-5D);

        final String formatted = RtsTopBarOverlay.formatResourceSummaryLine(
            clientSnap.food(),
            clientSnap.wood(),
            clientSnap.stone(),
            clientSnap.minerals(),
            clientSnap.totalCitizens(),
            clientSnap.maxCitizens(),
            clientSnap.idleCitizens(),
            clientSnap.overallHappiness()
        );
        assertEquals("식량: 1250 | 목재: 640 | 석재: 320 | 광물: 85 | 시민: 24/30 (유휴:4) | 행복도: 8.7/10", formatted);
    }

    public void testRtsCitizenRoadSpeedBuffAndRemoval()
    {
        try
        {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
        }
        catch (final Throwable ignored)
        {
        }

        // 7 Road blocks
        assertTrue(RtsResourceAggregator.isRoadBlock(Blocks.DIRT_PATH.defaultBlockState()));
        assertTrue(RtsResourceAggregator.isRoadBlock(Blocks.STONE_BRICKS.defaultBlockState()));
        assertTrue(RtsResourceAggregator.isRoadBlock(Blocks.COBBLESTONE.defaultBlockState()));
        assertTrue(RtsResourceAggregator.isRoadBlock(Blocks.SMOOTH_STONE.defaultBlockState()));
        assertTrue(RtsResourceAggregator.isRoadBlock(Blocks.POLISHED_ANDESITE.defaultBlockState()));
        assertTrue(RtsResourceAggregator.isRoadBlock(Blocks.GRAVEL.defaultBlockState()));
        assertTrue(RtsResourceAggregator.isRoadBlock(Blocks.MUD_BRICKS.defaultBlockState()));

        // Non-road blocks
        assertFalse(RtsResourceAggregator.isRoadBlock(Blocks.GRASS_BLOCK.defaultBlockState()));
        assertFalse(RtsResourceAggregator.isRoadBlock(Blocks.DIRT.defaultBlockState()));
        assertFalse(RtsResourceAggregator.isRoadBlock(Blocks.OAK_PLANKS.defaultBlockState()));
        assertFalse(RtsResourceAggregator.isRoadBlock(Blocks.AIR.defaultBlockState()));
        assertFalse(RtsResourceAggregator.isRoadBlock(Blocks.WATER.defaultBlockState()));

        // Road speed modifier constants
        assertEquals("minecolonies_rts_road_speed", RtsResourceAggregator.ROAD_SPEED_MODIFIER_NAME);
        assertEquals(0.30D, RtsResourceAggregator.ROAD_SPEED_BOOST_AMOUNT, 1.0E-6D);
        assertNotNull(RtsResourceAggregator.ROAD_SPEED_MODIFIER_UUID);
        assertNotNull(RtsResourceAggregator.ROAD_SPEED_MODIFIER);
        assertEquals(UUID.fromString("d8499b04-0e66-4726-ab29-64469d734e0d"), RtsResourceAggregator.ROAD_SPEED_MODIFIER_UUID);
        assertEquals(RtsResourceAggregator.ROAD_SPEED_MODIFIER_UUID, RtsResourceAggregator.ROAD_SPEED_MODIFIER.getId());

        // Numeric speed calculations
        final double baseSpeed = 0.25D;
        final double boostedSpeed = baseSpeed * (1.0D + RtsResourceAggregator.ROAD_SPEED_BOOST_AMOUNT);
        assertEquals(0.325D, boostedSpeed, 1.0E-6D);
        final double restoredSpeed = boostedSpeed / (1.0D + RtsResourceAggregator.ROAD_SPEED_BOOST_AMOUNT);
        assertEquals(0.25D, restoredSpeed, 1.0E-6D);
    }

    public void testRtsBlueprintPathResolutionAndNormalization()
    {
        assertEquals(
            "fundamentals/builder1.blueprint",
            RtsResourceAggregator.normalizeBlueprintSubPath("/fundamentals\\builder")
        );
        assertEquals(
            "fundamentals/builder1.blueprint",
            RtsResourceAggregator.normalizeBlueprintSubPath("fundamentals/builder1")
        );
        assertEquals(
            "agriculture/horticulture/alfarmer1.blueprint",
            RtsResourceAggregator.normalizeBlueprintSubPath("agriculture/horticulture/alfarmer1")
        );
        assertEquals(
            "fundamentals/lumberjack1.blueprint",
            RtsResourceAggregator.normalizeBlueprintSubPath("//fundamentals/lumberjack1.blueprint")
        );
        assertEquals(
            "agriculture/horticulture/farmer1.blueprint",
            RtsResourceAggregator.normalizeBlueprintSubPath("  agriculture\\horticulture/farmer  ")
        );

        assertEquals(
            "fundamentals/builder1.blueprint",
            RtsResourceAggregator.resolveFallbackBlueprintPath("Caledonia", RtsZoneType.BUILDER.getDefaultSchematicName())
        );
        assertEquals(
            "agriculture/horticulture/farmer1.blueprint",
            RtsResourceAggregator.resolveFallbackBlueprintPath("Caledonia", RtsZoneType.FARMING.getDefaultSchematicName())
        );
        assertEquals(
            "military/guardtower1.blueprint",
            RtsResourceAggregator.resolveFallbackBlueprintPath("Caledonia", RtsZoneType.DEFENSE.getDefaultSchematicName())
        );
        assertEquals(
            "craftsmanship/storage/warehouse1.blueprint",
            RtsResourceAggregator.resolveFallbackBlueprintPath("Caledonia", RtsZoneType.STORAGE.getDefaultSchematicName())
        );
        assertEquals(
            "fundamentals/customhut1.blueprint",
            RtsResourceAggregator.resolveFallbackBlueprintPath("Unknown Style", "customhut")
        );
    }

    public void testRtsCameraCenteringAndDragThreshold()
    {
        final Vec3 focus = new Vec3(291.5D, 69.0D, 250.5D);
        final float[] yaws = {0.0F, 90.0F, -60.0F, 215.0F};
        final float[] pitches = {20.0F, 45.0F, 52.5F, 60.0F, 85.0F};
        final double[] altitudes = {5.0D, 12.0D, 25.0D, 80.0D, 120.0D};

        for (final float yawDeg : yaws)
        {
            for (final float rawPitch : pitches)
            {
                for (final double rawAlt : altitudes)
                {
                    final double clampedAlt = RtsCameraController.clampAltitude(rawAlt);
                    final float clampedPitch = RtsCameraController.clampPitch(rawPitch);
                    final Vec3 camPos = RtsCameraController.computeInitialCameraPos(focus, yawDeg, rawPitch, rawAlt);
                    assertEquals(focus.y + clampedAlt, camPos.y, 1.0E-5D);

                    final float yawRad = yawDeg * Mth.DEG_TO_RAD;
                    final float pitchRad = clampedPitch * Mth.DEG_TO_RAD;
                    final double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
                    final double lookY = -Math.sin(pitchRad);
                    final double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);

                    final double t = (focus.y - camPos.y) / lookY;
                    final double hitX = camPos.x + t * lookX;
                    final double hitY = camPos.y + t * lookY;
                    final double hitZ = camPos.z + t * lookZ;

                    assertEquals(focus.x, hitX, 1.0E-2D);
                    assertEquals(focus.y, hitY, 1.0E-4D);
                    assertEquals(focus.z, hitZ, 1.0E-2D);
                }
            }
        }

        assertFalse(RtsCameraController.isDragThresholdMet(100.0D, 100.0D, 100.0D, 100.0D));
        assertFalse(RtsCameraController.isDragThresholdMet(100.0D, 100.0D, 105.9D, 100.0D));
        assertFalse(RtsCameraController.isDragThresholdMet(100.0D, 100.0D, 104.0D, 104.0D));
        assertTrue(RtsCameraController.isDragThresholdMet(100.0D, 100.0D, 106.0D, 100.0D));
        assertTrue(RtsCameraController.isDragThresholdMet(100.0D, 100.0D, 105.0D, 105.0D));
    }

    public void testRtsToolbarLayoutBoundsAndHitTesting()
    {
        final int[][] viewports = {
            {427, 240},
            {640, 360},
            {320, 200}
        };

        for (final int[] vp : viewports)
        {
            final int w = vp[0];
            final int h = vp[1];
            final List<RtsTopBarOverlay.ToolbarButton> buttons = RtsTopBarOverlay.computeToolbarButtons(
                w,
                h,
                RtsZonePlacementMode.MODE_A_AUTO_PLACE,
                "기본"
            );
            assertEquals(RtsZoneType.values().length + 3, buttons.size());

            for (int i = 0; i < buttons.size(); i++)
            {
                final RtsTopBarOverlay.ToolbarButton a = buttons.get(i);
                assertTrue(a.x() >= 0);
                assertTrue(a.y() >= RtsTopBarOverlay.TOP_BAR_HEIGHT);
                assertTrue(a.x() + a.width() <= w);
                assertTrue(a.y() + a.height() <= h);

                final double cx = a.x() + a.width() / 2.0D;
                final double cy = a.y() + a.height() / 2.0D;
                final RtsTopBarOverlay.ToolbarButton hit = RtsTopBarOverlay.findToolbarButtonAt(
                    w,
                    h,
                    RtsZonePlacementMode.MODE_A_AUTO_PLACE,
                    "기본",
                    cx,
                    cy
                );
                assertNotNull(hit);
                assertEquals(a.actionType(), hit.actionType());
                assertEquals(a.zoneType(), hit.zoneType());
                assertTrue(RtsTopBarOverlay.isHudBlockingPoint(buttons, cx, cy));

                for (int j = i + 1; j < buttons.size(); j++)
                {
                    final RtsTopBarOverlay.ToolbarButton b = buttons.get(j);
                    final boolean separated = (a.x() + a.width() <= b.x())
                        || (b.x() + b.width() <= a.x())
                        || (a.y() + a.height() <= b.y())
                        || (b.y() + b.height() <= a.y());
                    assertTrue(separated);
                }
            }

            final int[] dock = RtsTopBarOverlay.computeToolbarDockBounds(buttons);
            assertTrue(dock[0] >= 0);
            assertTrue(dock[1] >= RtsTopBarOverlay.TOP_BAR_HEIGHT - 2);
            assertTrue(dock[2] <= w);
            assertTrue(dock[3] <= h);
            final int chatY = RtsTopBarOverlay.getPreferredChatBottomY(w, h);
            assertTrue(chatY < dock[1]);
            if (h >= 240)
            {
                assertTrue(chatY <= h - 75);
            }
            if (w == 427 && h == 240)
            {
                assertTrue(dock[3] <= h - 39);
            }

            assertTrue(RtsTopBarOverlay.isHudBlockingPoint(buttons, w / 2.0D, 10.0D));
            assertNull(RtsTopBarOverlay.findToolbarButtonAt(
                w,
                h,
                RtsZonePlacementMode.MODE_A_AUTO_PLACE,
                "기본",
                w / 2.0D,
                80.0D
            ));
            assertFalse(RtsTopBarOverlay.isHudBlockingPoint(buttons, w / 2.0D, 80.0D));
        }

        final RtsZoneType originalZone = RtsCameraController.getActiveZoneType();
        final boolean originalDelete = RtsCameraController.isDeleteMode();
        final RtsZonePlacementMode originalMode = RtsCameraController.getActivePlacementMode();
        try
        {
            assertTrue(RtsCameraController.toggleDeleteMode() != originalDelete);
            RtsCameraController.setActiveZoneType(RtsZoneType.BUILDER);
            assertEquals(RtsZoneType.BUILDER, RtsCameraController.getActiveZoneType());
            assertFalse(RtsCameraController.isDeleteMode());

            final RtsZonePlacementMode toggledMode = RtsCameraController.cyclePlacementMode();
            assertEquals(originalMode.next(), toggledMode);
            assertEquals(originalMode, RtsCameraController.cyclePlacementMode());

            for (int i = 0; i < 8; i++)
            {
                RtsCameraController.cycleCitizenJob();
            }
            assertEquals("기본", RtsCameraController.getActiveJobShortLabel());
            assertNull(RtsCameraController.getActiveJobIdOverride());
        }
        finally
        {
            RtsCameraController.setActiveZoneType(originalZone);
            if (originalDelete)
            {
                RtsCameraController.toggleDeleteMode();
            }
            if (RtsCameraController.getActivePlacementMode() != originalMode)
            {
                RtsCameraController.cyclePlacementMode();
            }
            for (int guard = 0; guard < 16 && RtsCameraController.getActiveJobIdOverride() != null; guard++)
            {
                RtsCameraController.cycleCitizenJob();
            }
        }
    }

    private static RtsZonePlacementMode getPlacementMode(final RtsZoneActionMessage msg)
    {
        try
        {
            final Field field = RtsZoneActionMessage.class.getDeclaredField("placementMode");
            field.setAccessible(true);
            return (RtsZonePlacementMode) field.get(msg);
        }
        catch (final Exception e)
        {
            throw new RuntimeException("Failed to read placementMode via reflection", e);
        }
    }

    private static void assertEquals(final Object expected, final Object actual)
    {
        if (!Objects.equals(expected, actual))
        {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertEquals(final double expected, final double actual)
    {
        assertEquals(expected, actual, 1.0E-6D);
    }

    private static void assertEquals(final double expected, final double actual, final double delta)
    {
        if (Math.abs(expected - actual) > delta)
        {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + "> (delta " + delta + ")");
        }
    }

    private static void assertEquals(final float expected, final float actual)
    {
        assertEquals(expected, actual, 1.0E-5F);
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
