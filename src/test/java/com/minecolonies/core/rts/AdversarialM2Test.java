package com.minecolonies.core.rts;

import com.minecolonies.core.client.rts.RtsCameraController;
import com.minecolonies.core.client.rts.RtsTopBarOverlay;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.Input;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Empirical Adversarial Test Harness for Milestone 2.
 * Stress-tests RtsTopBarOverlay formatting, road speed buff constants and blocks,
 * and RtsCameraController zoom clamps and input suppression.
 */
public class AdversarialM2Test
{
    private static int totalAssertions = 0;

    public static void main(final String[] args)
    {
        System.out.println("=== STARTING ADVERSARIAL M2 TEST SUITE ===");

        // Initialize Minecraft registries/blocks safely if needed
        try
        {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
        }
        catch (final Throwable t)
        {
            System.out.println("[NOTE] Bootstrap bootstrap returned: " + t.getMessage());
        }

        testFormatResourceSummaryLineStress();
        testRoadSpeedBuffAndBlockStates();
        testCameraControllerTuningAndInputSuppression();

        System.out.println("=== ALL ADVERSARIAL M2 TESTS PASSED (" + totalAssertions + " assertions verified) ===");
    }

    private static void testFormatResourceSummaryLineStress()
    {
        System.out.println("[ADVERSARIAL] Running testFormatResourceSummaryLineStress...");

        // 1. Baseline normal case
        final String normal = RtsTopBarOverlay.formatResourceSummaryLine(100, 200, 300, 50, 10, 20, 3, 7.5);
        assertEquals("식량: 100 | 목재: 200 | 석재: 300 | 광물: 50 | 시민: 10/20 (유휴:3) | 행복도: 7.5/10", normal);

        // 2. All Zeros
        final String zero = RtsTopBarOverlay.formatResourceSummaryLine(0, 0, 0, 0, 0, 0, 0, 0.0);
        assertEquals("식량: 0 | 목재: 0 | 석재: 0 | 광물: 0 | 시민: 0/0 (유휴:0) | 행복도: 0.0/10", zero);

        // 3. Negative values
        final String negative = RtsTopBarOverlay.formatResourceSummaryLine(-500, -100, -50, -10, -5, -2, -1, -3.2);
        assertEquals("식량: -500 | 목재: -100 | 석재: -50 | 광물: -10 | 시민: -5/-2 (유휴:-1) | 행복도: -3.2/10", negative);

        // 4. Integer.MAX_VALUE
        final String maxInt = RtsTopBarOverlay.formatResourceSummaryLine(
            Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
            Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 10.0
        );
        assertTrue(maxInt.contains("식량: 2147483647"));
        assertTrue(maxInt.contains("광물: 2147483647"));
        assertTrue(maxInt.contains("시민: 2147483647/2147483647 (유휴:2147483647)"));

        // 5. Integer.MIN_VALUE
        final String minInt = RtsTopBarOverlay.formatResourceSummaryLine(
            Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
            Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, 0.0
        );
        assertTrue(minInt.contains("식량: -2147483648"));
        assertTrue(minInt.contains("광물: -2147483648"));
        assertTrue(minInt.contains("시민: -2147483648/-2147483648 (유휴:-2147483648)"));

        // 6. Extreme happiness values
        final String nanHappy = RtsTopBarOverlay.formatResourceSummaryLine(1, 1, 1, 1, 1, 1, 1, Double.NaN);
        assertTrue(nanHappy.endsWith("행복도: NaN/10"));

        final String posInfHappy = RtsTopBarOverlay.formatResourceSummaryLine(1, 1, 1, 1, 1, 1, 1, Double.POSITIVE_INFINITY);
        assertTrue(posInfHappy.endsWith("행복도: Infinity/10"));

        final String negInfHappy = RtsTopBarOverlay.formatResourceSummaryLine(1, 1, 1, 1, 1, 1, 1, Double.NEGATIVE_INFINITY);
        assertTrue(negInfHappy.endsWith("행복도: -Infinity/10"));

        final String largeHappy = RtsTopBarOverlay.formatResourceSummaryLine(1, 1, 1, 1, 1, 1, 1, 999999.9);
        assertTrue(largeHappy.endsWith("행복도: 999999.9/10"));

        final String smallHappy = RtsTopBarOverlay.formatResourceSummaryLine(1, 1, 1, 1, 1, 1, 1, Double.MIN_VALUE);
        assertTrue(smallHappy.endsWith("행복도: 0.0/10"));

        // 7. Fuzz / Random generator stress test (1,000 iterations)
        final Random random = new Random(42);
        final Pattern pattern = Pattern.compile("^식량: (-?\\d+) \\| 목재: (-?\\d+) \\| 석재: (-?\\d+) \\| 광물: (-?\\d+) \\| 시민: (-?\\d+)/(-?\\d+) \\(유휴:(-?\\d+)\\) \\| 행복도: (.+)/10$");

        for (int i = 0; i < 1000; i++)
        {
            final int food = random.nextInt();
            final int wood = random.nextInt();
            final int stone = random.nextInt();
            final int minerals = random.nextInt();
            final int total = random.nextInt();
            final int max = random.nextInt();
            final int idle = random.nextInt();
            final double happy = (random.nextDouble() - 0.5) * 200.0;

            final String formatted = RtsTopBarOverlay.formatResourceSummaryLine(food, wood, stone, minerals, total, max, idle, happy);
            assertNotNull(formatted);
            final Matcher matcher = pattern.matcher(formatted);
            assertTrue(matcher.matches());
            assertEquals(String.valueOf(food), matcher.group(1));
            assertEquals(String.valueOf(wood), matcher.group(2));
            assertEquals(String.valueOf(stone), matcher.group(3));
            assertEquals(String.valueOf(minerals), matcher.group(4));
            assertEquals(String.valueOf(total), matcher.group(5));
            assertEquals(String.valueOf(max), matcher.group(6));
            assertEquals(String.valueOf(idle), matcher.group(7));
        }

        System.out.println("  -> testFormatResourceSummaryLineStress PASSED (1,000 fuzz cycles + edge cases)");
    }

    private static void testRoadSpeedBuffAndBlockStates()
    {
        System.out.println("[ADVERSARIAL] Running testRoadSpeedBuffAndBlockStates...");

        // 1. Check constants
        assertEquals("minecolonies_rts_road_speed", RtsResourceAggregator.ROAD_SPEED_MODIFIER_NAME);
        assertEquals(0.30D, RtsResourceAggregator.ROAD_SPEED_BOOST_AMOUNT, 1.0E-6D);

        // 2. Check all 7 road block types
        final BlockState dirtPath = Blocks.DIRT_PATH.defaultBlockState();
        final BlockState stoneBricks = Blocks.STONE_BRICKS.defaultBlockState();
        final BlockState cobblestone = Blocks.COBBLESTONE.defaultBlockState();
        final BlockState smoothStone = Blocks.SMOOTH_STONE.defaultBlockState();
        final BlockState polishedAndesite = Blocks.POLISHED_ANDESITE.defaultBlockState();
        final BlockState gravel = Blocks.GRAVEL.defaultBlockState();
        final BlockState mudBricks = Blocks.MUD_BRICKS.defaultBlockState();

        assertTrue(RtsResourceAggregator.isRoadBlock(dirtPath));
        assertTrue(RtsResourceAggregator.isRoadBlock(stoneBricks));
        assertTrue(RtsResourceAggregator.isRoadBlock(cobblestone));
        assertTrue(RtsResourceAggregator.isRoadBlock(smoothStone));
        assertTrue(RtsResourceAggregator.isRoadBlock(polishedAndesite));
        assertTrue(RtsResourceAggregator.isRoadBlock(gravel));
        assertTrue(RtsResourceAggregator.isRoadBlock(mudBricks));

        // 3. Check non-road block types (air, water, grass, obsidian, bedrock + extras)
        final BlockState air = Blocks.AIR.defaultBlockState();
        final BlockState water = Blocks.WATER.defaultBlockState();
        final BlockState grassBlock = Blocks.GRASS_BLOCK.defaultBlockState();
        final BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
        final BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        final BlockState dirt = Blocks.DIRT.defaultBlockState();
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final BlockState sand = Blocks.SAND.defaultBlockState();
        final BlockState oakPlanks = Blocks.OAK_PLANKS.defaultBlockState();
        final BlockState lava = Blocks.LAVA.defaultBlockState();

        assertFalse(RtsResourceAggregator.isRoadBlock(air));
        assertFalse(RtsResourceAggregator.isRoadBlock(water));
        assertFalse(RtsResourceAggregator.isRoadBlock(grassBlock));
        assertFalse(RtsResourceAggregator.isRoadBlock(obsidian));
        assertFalse(RtsResourceAggregator.isRoadBlock(bedrock));
        assertFalse(RtsResourceAggregator.isRoadBlock(dirt));
        assertFalse(RtsResourceAggregator.isRoadBlock(stone));
        assertFalse(RtsResourceAggregator.isRoadBlock(sand));
        assertFalse(RtsResourceAggregator.isRoadBlock(oakPlanks));
        assertFalse(RtsResourceAggregator.isRoadBlock(lava));

        System.out.println("  -> testRoadSpeedBuffAndBlockStates PASSED (7 road blocks + 10 non-road blocks)");
    }

    private static void testCameraControllerTuningAndInputSuppression()
    {
        System.out.println("[ADVERSARIAL] Running testCameraControllerTuningAndInputSuppression...");

        // 1. Altitude constants
        assertEquals(12.0D, RtsCameraController.MIN_ALTITUDE, 1.0E-6D);
        assertEquals(80.0D, RtsCameraController.MAX_ALTITUDE, 1.0E-6D);

        // 2. Altitude clamping
        assertEquals(12.0D, RtsCameraController.clampAltitude(-100.0D), 1.0E-6D);
        assertEquals(12.0D, RtsCameraController.clampAltitude(0.0D), 1.0E-6D);
        assertEquals(12.0D, RtsCameraController.clampAltitude(11.99D), 1.0E-6D);
        assertEquals(12.0D, RtsCameraController.clampAltitude(12.0D), 1.0E-6D);
        assertEquals(30.0D, RtsCameraController.clampAltitude(30.0D), 1.0E-6D);
        assertEquals(80.0D, RtsCameraController.clampAltitude(80.0D), 1.0E-6D);
        assertEquals(80.0D, RtsCameraController.clampAltitude(80.01D), 1.0E-6D);
        assertEquals(80.0D, RtsCameraController.clampAltitude(1000.0D), 1.0E-6D);

        // 3. Movement input suppression
        try
        {
            final Input testInput = new Input();
            testInput.forwardImpulse = 1.0F;
            testInput.leftImpulse = -0.8F;
            testInput.up = true;
            testInput.down = false;
            testInput.left = true;
            testInput.right = false;
            testInput.jumping = true;
            testInput.shiftKeyDown = true;

            // When active is false: should NOT suppress
            RtsCameraController.setActiveForTesting(false);
            RtsCameraController.suppressMovementInput(testInput);

            assertEquals(1.0F, testInput.forwardImpulse, 1.0E-5F);
            assertEquals(-0.8F, testInput.leftImpulse, 1.0E-5F);
            assertTrue(testInput.up);
            assertTrue(testInput.left);
            assertTrue(testInput.jumping);
            assertTrue(testInput.shiftKeyDown);

            // When active is true: MUST suppress all fields
            RtsCameraController.setActiveForTesting(true);
            RtsCameraController.suppressMovementInput(testInput);

            assertEquals(0.0F, testInput.forwardImpulse, 1.0E-5F);
            assertEquals(0.0F, testInput.leftImpulse, 1.0E-5F);
            assertFalse(testInput.up);
            assertFalse(testInput.down);
            assertFalse(testInput.left);
            assertFalse(testInput.right);
            assertFalse(testInput.jumping);
            assertFalse(testInput.shiftKeyDown);
        }
        finally
        {
            // Crucial: teardown to avoid contaminating subsequent tests
            RtsCameraController.setActiveForTesting(false);
        }

        System.out.println("  -> testCameraControllerTuningAndInputSuppression PASSED");
    }

    private static void assertEquals(final Object expected, final Object actual)
    {
        totalAssertions++;
        if (!java.util.Objects.equals(expected, actual))
        {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertEquals(final double expected, final double actual, final double delta)
    {
        totalAssertions++;
        if (Math.abs(expected - actual) > delta)
        {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + "> (delta " + delta + ")");
        }
    }

    private static void assertEquals(final float expected, final float actual, final float delta)
    {
        totalAssertions++;
        if (Math.abs(expected - actual) > delta)
        {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + "> (delta " + delta + ")");
        }
    }

    private static void assertTrue(final boolean condition)
    {
        totalAssertions++;
        if (!condition)
        {
            throw new AssertionError("Expected condition to be true");
        }
    }

    private static void assertFalse(final boolean condition)
    {
        totalAssertions++;
        if (condition)
        {
            throw new AssertionError("Expected condition to be false");
        }
    }

    private static void assertNotNull(final Object obj)
    {
        totalAssertions++;
        if (obj == null)
        {
            throw new AssertionError("Expected non-null reference");
        }
    }
}
