package com.minecolonies.core.client.rts;

import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.core.rts.RtsResourceAggregator;
import com.minecolonies.core.rts.RtsZoneData;
import com.minecolonies.core.rts.RtsZoneType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;

/**
 * 1.20.1 Blaze3D 3D renderer for terrain-conforming RTS zones, active drag grid, hovered buildings, and selected citizens.
 */
public final class RtsGridRenderer
{
    private RtsGridRenderer()
    {
    }

    /**
     * Resolves the terrain surface Y (top face coordinate) at column {@code (x, z)} so zone overlays follow hills and valleys.
     */
    public static float resolveZoneSurfaceY(
        @NotNull final ClientLevel level,
        final int x,
        final int z,
        final int refMinY)
    {
        if (!level.hasChunkAt(new BlockPos(x, refMinY, z)))
        {
            return refMinY + 1.0F;
        }

        final int minBuildY = level.getMinBuildHeight();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= minBuildY)
        {
            return refMinY + 1.0F;
        }

        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        final int floorLimit = Math.max(minBuildY + 1, y - 32);
        while (y > floorLimit)
        {
            cursor.set(x, y - 1, z);
            final BlockState state = level.getBlockState(cursor);
            if (!state.getFluidState().isEmpty())
            {
                break;
            }
            if (state.isAir()
                || state.canBeReplaced()
                || state.is(BlockTags.LOGS)
                || state.is(BlockTags.LEAVES))
            {
                y--;
            }
            else
            {
                break;
            }
        }
        return (float) y;
    }

    public static void onRenderLevelStage(@NotNull final RenderLevelStageEvent event)
    {
        if (!RtsCameraController.isActive() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
        {
            return;
        }

        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
        {
            return;
        }

        final PoseStack poseStack = event.getPoseStack();
        final Vec3 camPos = event.getCamera().getPosition();
        final MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-1.0F, -10.0F);

        final VertexConsumer fillBuffer = bufferSource.getBuffer(RenderType.debugFilledBox());
        for (final RtsZoneData zone : RtsResourceAggregator.getClientSnapshot().zones())
        {
            final BoundingBox box = zone.bounds();
            renderTerrainConformingFill(
                mc.level,
                poseStack,
                fillBuffer,
                box.minX(),
                box.maxX(),
                box.minZ(),
                box.maxZ(),
                box.minY(),
                0.04F,
                zone.type().getRed(),
                zone.type().getGreen(),
                zone.type().getBlue(),
                0.26F
            );
        }

        if (RtsCameraController.isDragPreviewActive())
        {
            final BlockPos start = RtsCameraController.getDragStartPos();
            final BlockPos end = RtsCameraController.getDragEndPos();
            if (start != null && end != null)
            {
                final int minX = Math.min(start.getX(), end.getX());
                final int maxX = Math.max(start.getX(), end.getX());
                final int minZ = Math.min(start.getZ(), end.getZ());
                final int maxZ = Math.max(start.getZ(), end.getZ());
                final int refMinY = Math.min(start.getY(), end.getY());

                final float r;
                final float g;
                final float b;
                if (RtsCameraController.isShiftBoxSelectDrag())
                {
                    r = 0.20F;
                    g = 0.95F;
                    b = 0.85F;
                }
                else if (RtsCameraController.isDeleteMode())
                {
                    r = 0.95F;
                    g = 0.15F;
                    b = 0.15F;
                }
                else
                {
                    final RtsZoneType type = RtsCameraController.getActiveZoneType();
                    r = type.getRed();
                    g = type.getGreen();
                    b = type.getBlue();
                }
                renderTerrainConformingFill(
                    mc.level,
                    poseStack,
                    fillBuffer,
                    minX,
                    maxX,
                    minZ,
                    maxZ,
                    refMinY,
                    0.07F,
                    r,
                    g,
                    b,
                    0.38F
                );
            }
        }

        for (final int entityId : RtsCameraController.getSelectedCitizens())
        {
            final Entity entity = mc.level.getEntity(entityId);
            if (entity != null && entity.isAlive())
            {
                final float ex = (float) entity.getX();
                final float ey = (float) entity.getY() + 0.05F;
                final float ez = (float) entity.getZ();
                renderFilledQuad(
                    poseStack,
                    fillBuffer,
                    ex - 0.55F,
                    ey,
                    ez - 0.55F,
                    ex + 0.55F,
                    ez + 0.55F,
                    0.15F,
                    1.0F,
                    0.45F,
                    0.42F
                );
                final float yBottom = (float) entity.getY() + entity.getBbHeight() + 0.35F;
                final float yTop = yBottom + 5.2F;
                renderVerticalBeaconPillar(
                    poseStack,
                    fillBuffer,
                    ex,
                    ez,
                    yBottom,
                    yTop,
                    0.11F,
                    0.20F,
                    1.0F,
                    0.65F,
                    0.45F
                );
            }
        }
        bufferSource.endBatch(RenderType.debugFilledBox());

        final VertexConsumer lineBuffer = bufferSource.getBuffer(RenderType.lines());
        for (final RtsZoneData zone : RtsResourceAggregator.getClientSnapshot().zones())
        {
            final BoundingBox box = zone.bounds();
            renderTerrainConformingOutline(
                mc.level,
                poseStack,
                lineBuffer,
                box.minX(),
                box.maxX(),
                box.minZ(),
                box.maxZ(),
                box.minY(),
                0.06F,
                zone.type().getRed(),
                zone.type().getGreen(),
                zone.type().getBlue(),
                0.90F,
                false
            );
            if (zone.linkedBuildingPos() != null)
            {
                final BlockPos bp = zone.linkedBuildingPos();
                renderGridOutline(
                    poseStack,
                    lineBuffer,
                    bp.getX(),
                    bp.getY() + 1.06F,
                    bp.getZ(),
                    bp.getX() + 1.0F,
                    bp.getZ() + 1.0F,
                    1.0F,
                    0.95F,
                    0.25F,
                    0.95F,
                    false
                );
            }
        }

        if (RtsCameraController.isDragPreviewActive())
        {
            final BlockPos start = RtsCameraController.getDragStartPos();
            final BlockPos end = RtsCameraController.getDragEndPos();
            if (start != null && end != null)
            {
                final int minX = Math.min(start.getX(), end.getX());
                final int maxX = Math.max(start.getX(), end.getX());
                final int minZ = Math.min(start.getZ(), end.getZ());
                final int maxZ = Math.max(start.getZ(), end.getZ());
                final int refMinY = Math.min(start.getY(), end.getY());
                renderTerrainConformingOutline(
                    mc.level,
                    poseStack,
                    lineBuffer,
                    minX,
                    maxX,
                    minZ,
                    maxZ,
                    refMinY,
                    0.09F,
                    1.0F,
                    1.0F,
                    1.0F,
                    0.95F,
                    true
                );
            }
        }
        else
        {
            final BlockPos hoveredBuildingPos = RtsCameraController.getHoveredBuildingPos();
            final BlockPos hoveredBlockPos = RtsCameraController.getHoveredBlockPos();
            if (hoveredBuildingPos != null)
            {
                renderHoveredBuildingOutline(mc, poseStack, lineBuffer, hoveredBuildingPos);
            }
            else if (hoveredBlockPos != null)
            {
                final float hy = resolveZoneSurfaceY(mc.level, hoveredBlockPos.getX(), hoveredBlockPos.getZ(), hoveredBlockPos.getY()) + 0.05F;
                renderGridOutline(
                    poseStack,
                    lineBuffer,
                    hoveredBlockPos.getX(),
                    Math.max(hoveredBlockPos.getY() + 1.05F, hy),
                    hoveredBlockPos.getZ(),
                    hoveredBlockPos.getX() + 1.0F,
                    hoveredBlockPos.getZ() + 1.0F,
                    1.0F,
                    1.0F,
                    0.45F,
                    0.92F,
                    false
                );
            }
        }

        for (final int entityId : RtsCameraController.getSelectedCitizens())
        {
            final Entity entity = mc.level.getEntity(entityId);
            if (entity != null && entity.isAlive())
            {
                final float ex = (float) entity.getX();
                final float ey = (float) entity.getY() + 0.07F;
                final float ez = (float) entity.getZ();
                renderGridOutline(
                    poseStack,
                    lineBuffer,
                    ex - 0.58F,
                    ey,
                    ez - 0.58F,
                    ex + 0.58F,
                    ez + 0.58F,
                    0.15F,
                    1.0F,
                    0.45F,
                    1.0F,
                    false
                );
                final float yBottom = (float) entity.getY() + entity.getBbHeight() + 0.35F;
                final float yTop = yBottom + 5.6F;
                drawLine(poseStack.last(), lineBuffer, ex, yBottom, ez, ex, yTop, ez, 0.35F, 1.0F, 0.80F, 1.0F);
                renderGridOutline(
                    poseStack,
                    lineBuffer,
                    ex - 0.30F,
                    yTop,
                    ez - 0.30F,
                    ex + 0.30F,
                    ez + 0.30F,
                    0.35F,
                    1.0F,
                    0.85F,
                    1.0F,
                    false
                );
            }
        }
        bufferSource.endBatch(RenderType.lines());

        RenderSystem.polygonOffset(0.0F, 0.0F);
        RenderSystem.disablePolygonOffset();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private static void renderTerrainConformingFill(
        @NotNull final ClientLevel level,
        @NotNull final PoseStack poseStack,
        @NotNull final VertexConsumer fillBuffer,
        final int rawMinX,
        final int rawMaxX,
        final int rawMinZ,
        final int rawMaxZ,
        final int refMinY,
        final float yOffset,
        final float r,
        final float g,
        final float b,
        final float a)
    {
        final int maxX = Math.min(rawMinX + 64, rawMaxX);
        final int maxZ = Math.min(rawMinZ + 64, rawMaxZ);

        // Merge consecutive Z columns sharing the same height into a single quad for fast rendering on plains and accurate stepping on hills
        for (int x = rawMinX; x <= maxX; x++)
        {
            int runStartZ = rawMinZ;
            float runY = resolveZoneSurfaceY(level, x, rawMinZ, refMinY) + yOffset;
            for (int z = rawMinZ + 1; z <= maxZ; z++)
            {
                final float colY = resolveZoneSurfaceY(level, x, z, refMinY) + yOffset;
                if (Math.abs(colY - runY) > 0.01F)
                {
                    renderFilledQuad(poseStack, fillBuffer, x, runY, runStartZ, x + 1.0F, z, r, g, b, a);
                    runStartZ = z;
                    runY = colY;
                }
            }
            renderFilledQuad(poseStack, fillBuffer, x, runY, runStartZ, x + 1.0F, maxZ + 1.0F, r, g, b, a);
        }
    }

    private static void renderTerrainConformingOutline(
        @NotNull final ClientLevel level,
        @NotNull final PoseStack poseStack,
        @NotNull final VertexConsumer lineBuffer,
        final int rawMinX,
        final int rawMaxX,
        final int rawMinZ,
        final int rawMaxZ,
        final int refMinY,
        final float yOffset,
        final float r,
        final float g,
        final float b,
        final float a,
        final boolean drawInternalGrid)
    {
        final int maxX = Math.min(rawMinX + 64, rawMaxX);
        final int maxZ = Math.min(rawMinZ + 64, rawMaxZ);
        final PoseStack.Pose pose = poseStack.last();

        float prevNorthY = Float.NaN;
        float prevSouthY = Float.NaN;
        for (int x = rawMinX; x <= maxX; x++)
        {
            final float ny = resolveZoneSurfaceY(level, x, rawMinZ, refMinY) + yOffset;
            if (!Float.isNaN(prevNorthY) && Math.abs(ny - prevNorthY) > 0.01F)
            {
                drawLine(pose, lineBuffer, x, prevNorthY, rawMinZ, x, ny, rawMinZ, r, g, b, a);
            }
            drawLine(pose, lineBuffer, x, ny, rawMinZ, x + 1.0F, ny, rawMinZ, r, g, b, a);
            prevNorthY = ny;

            final float sy = resolveZoneSurfaceY(level, x, maxZ, refMinY) + yOffset;
            if (!Float.isNaN(prevSouthY) && Math.abs(sy - prevSouthY) > 0.01F)
            {
                drawLine(pose, lineBuffer, x, prevSouthY, maxZ + 1.0F, x, sy, maxZ + 1.0F, r, g, b, a);
            }
            drawLine(pose, lineBuffer, x, sy, maxZ + 1.0F, x + 1.0F, sy, maxZ + 1.0F, r, g, b, a);
            prevSouthY = sy;
        }

        float prevWestY = Float.NaN;
        float prevEastY = Float.NaN;
        for (int z = rawMinZ; z <= maxZ; z++)
        {
            final float wy = resolveZoneSurfaceY(level, rawMinX, z, refMinY) + yOffset;
            if (!Float.isNaN(prevWestY) && Math.abs(wy - prevWestY) > 0.01F)
            {
                drawLine(pose, lineBuffer, rawMinX, prevWestY, z, rawMinX, wy, z, r, g, b, a);
            }
            drawLine(pose, lineBuffer, rawMinX, wy, z, rawMinX, wy, z + 1.0F, r, g, b, a);
            prevWestY = wy;

            final float ey = resolveZoneSurfaceY(level, maxX, z, refMinY) + yOffset;
            if (!Float.isNaN(prevEastY) && Math.abs(ey - prevEastY) > 0.01F)
            {
                drawLine(pose, lineBuffer, maxX + 1.0F, prevEastY, z, maxX + 1.0F, ey, z, r, g, b, a);
            }
            drawLine(pose, lineBuffer, maxX + 1.0F, ey, z, maxX + 1.0F, ey, z + 1.0F, r, g, b, a);
            prevEastY = ey;
        }

        if (drawInternalGrid && (maxX - rawMinX) <= 48 && (maxZ - rawMinZ) <= 48)
        {
            final float gridAlpha = a * 0.40F;
            final int step = ((maxX - rawMinX) > 24 || (maxZ - rawMinZ) > 24) ? 2 : 1;
            for (int x = rawMinX + step; x <= maxX; x += step)
            {
                int runStartZ = rawMinZ;
                float runY = resolveZoneSurfaceY(level, x, rawMinZ, refMinY) + yOffset;
                for (int z = rawMinZ + 1; z <= maxZ; z++)
                {
                    final float cy = resolveZoneSurfaceY(level, x, z, refMinY) + yOffset;
                    if (Math.abs(cy - runY) > 0.01F)
                    {
                        drawLine(pose, lineBuffer, x, runY, runStartZ, x, runY, z, r, g, b, gridAlpha);
                        runStartZ = z;
                        runY = cy;
                    }
                }
                drawLine(pose, lineBuffer, x, runY, runStartZ, x, runY, maxZ + 1.0F, r, g, b, gridAlpha);
            }
            for (int z = rawMinZ + step; z <= maxZ; z += step)
            {
                int runStartX = rawMinX;
                float runY = resolveZoneSurfaceY(level, rawMinX, z, refMinY) + yOffset;
                for (int x = rawMinX + 1; x <= maxX; x++)
                {
                    final float cy = resolveZoneSurfaceY(level, x, z, refMinY) + yOffset;
                    if (Math.abs(cy - runY) > 0.01F)
                    {
                        drawLine(pose, lineBuffer, runStartX, runY, z, x, runY, z, r, g, b, gridAlpha);
                        runStartX = x;
                        runY = cy;
                    }
                }
                drawLine(pose, lineBuffer, runStartX, runY, z, maxX + 1.0F, runY, z, r, g, b, gridAlpha);
            }
        }
    }

    private static void renderVerticalBeaconPillar(
        @NotNull final PoseStack poseStack,
        @NotNull final VertexConsumer consumer,
        final float cx,
        final float cz,
        final float yBottom,
        final float yTop,
        final float halfWidth,
        final float r,
        final float g,
        final float b,
        final float a)
    {
        final Matrix4f matrix = poseStack.last().pose();
        final float x1 = cx - halfWidth;
        final float x2 = cx + halfWidth;
        final float z1 = cz - halfWidth;
        final float z2 = cz + halfWidth;

        // Front/Back
        consumer.vertex(matrix, x1, yBottom, z1).color(r, g, b, a).endVertex();
        consumer.vertex(matrix, x1, yTop, z1).color(r, g, b, a * 0.25F).endVertex();
        consumer.vertex(matrix, x2, yTop, z1).color(r, g, b, a * 0.25F).endVertex();
        consumer.vertex(matrix, x2, yBottom, z1).color(r, g, b, a).endVertex();

        consumer.vertex(matrix, x1, yBottom, z2).color(r, g, b, a).endVertex();
        consumer.vertex(matrix, x1, yTop, z2).color(r, g, b, a * 0.25F).endVertex();
        consumer.vertex(matrix, x2, yTop, z2).color(r, g, b, a * 0.25F).endVertex();
        consumer.vertex(matrix, x2, yBottom, z2).color(r, g, b, a).endVertex();

        // Left/Right
        consumer.vertex(matrix, x1, yBottom, z1).color(r, g, b, a).endVertex();
        consumer.vertex(matrix, x1, yTop, z1).color(r, g, b, a * 0.25F).endVertex();
        consumer.vertex(matrix, x1, yTop, z2).color(r, g, b, a * 0.25F).endVertex();
        consumer.vertex(matrix, x1, yBottom, z2).color(r, g, b, a).endVertex();

        consumer.vertex(matrix, x2, yBottom, z1).color(r, g, b, a).endVertex();
        consumer.vertex(matrix, x2, yTop, z1).color(r, g, b, a * 0.25F).endVertex();
        consumer.vertex(matrix, x2, yTop, z2).color(r, g, b, a * 0.25F).endVertex();
        consumer.vertex(matrix, x2, yBottom, z2).color(r, g, b, a).endVertex();
    }

    private static void renderHoveredBuildingOutline(
        @NotNull final Minecraft mc,
        @NotNull final PoseStack poseStack,
        @NotNull final VertexConsumer lineBuffer,
        @NotNull final BlockPos buildingPos)
    {
        if (mc.level == null)
        {
            return;
        }
        final IColonyView colonyView = IColonyManager.getInstance().getClosestColonyView(mc.level, buildingPos);
        if (colonyView != null)
        {
            final IBuildingView view = colonyView.getClientBuildingManager().getBuilding(buildingPos);
            if (view != null && mc.level.getBlockEntity(buildingPos) instanceof AbstractTileEntityColonyBuilding buildingTe)
            {
                final Tuple<BlockPos, BlockPos> corners = buildingTe.getInWorldCorners();
                if (corners != null && corners.getA() != null && corners.getB() != null && !corners.getA().equals(corners.getB()))
                {
                    final BlockPos a = corners.getA();
                    final BlockPos b = corners.getB();
                    final float minX = Math.min(a.getX(), b.getX());
                    final float maxX = Math.max(a.getX(), b.getX()) + 1.0F;
                    final float minZ = Math.min(a.getZ(), b.getZ());
                    final float maxZ = Math.max(a.getZ(), b.getZ()) + 1.0F;
                    final float y = buildingPos.getY() + 1.06F;
                    renderGridOutline(poseStack, lineBuffer, minX, y, minZ, maxX, maxZ, 0.25F, 0.90F, 1.0F, 0.95F, false);
                    return;
                }
            }
        }
        renderGridOutline(
            poseStack,
            lineBuffer,
            buildingPos.getX(),
            buildingPos.getY() + 1.06F,
            buildingPos.getZ(),
            buildingPos.getX() + 1.0F,
            buildingPos.getZ() + 1.0F,
            0.25F,
            0.90F,
            1.0F,
            0.95F,
            false
        );
    }

    private static void renderFilledQuad(
        @NotNull final PoseStack poseStack,
        @NotNull final VertexConsumer consumer,
        final float minX,
        final float y,
        final float minZ,
        final float maxX,
        final float maxZ,
        final float r,
        final float g,
        final float b,
        final float a)
    {
        final Matrix4f matrix = poseStack.last().pose();
        consumer.vertex(matrix, minX, y, minZ).color(r, g, b, a).endVertex();
        consumer.vertex(matrix, minX, y, maxZ).color(r, g, b, a).endVertex();
        consumer.vertex(matrix, maxX, y, maxZ).color(r, g, b, a).endVertex();
        consumer.vertex(matrix, maxX, y, minZ).color(r, g, b, a).endVertex();
    }

    private static void renderGridOutline(
        @NotNull final PoseStack poseStack,
        @NotNull final VertexConsumer consumer,
        final float minX,
        final float y,
        final float minZ,
        final float maxX,
        final float maxZ,
        final float r,
        final float g,
        final float b,
        final float a,
        final boolean drawInternalGrid)
    {
        final PoseStack.Pose pose = poseStack.last();
        drawLine(pose, consumer, minX, y, minZ, maxX, y, minZ, r, g, b, a);
        drawLine(pose, consumer, maxX, y, minZ, maxX, y, maxZ, r, g, b, a);
        drawLine(pose, consumer, maxX, y, maxZ, minX, y, maxZ, r, g, b, a);
        drawLine(pose, consumer, minX, y, maxZ, minX, y, minZ, r, g, b, a);

        if (drawInternalGrid && (maxX - minX) <= 64.0F && (maxZ - minZ) <= 64.0F)
        {
            for (float x = minX + 1.0F; x < maxX; x += 1.0F)
            {
                drawLine(pose, consumer, x, y, minZ, x, y, maxZ, r, g, b, a * 0.45F);
            }
            for (float z = minZ + 1.0F; z < maxZ; z += 1.0F)
            {
                drawLine(pose, consumer, minX, y, z, maxX, y, z, r, g, b, a * 0.45F);
            }
        }
    }

    private static void drawLine(
        @NotNull final PoseStack.Pose pose,
        @NotNull final VertexConsumer consumer,
        final float x1,
        final float y1,
        final float z1,
        final float x2,
        final float y2,
        final float z2,
        final float r,
        final float g,
        final float b,
        final float a)
    {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        final float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 1.0E-5F)
        {
            dx /= len;
            dy /= len;
            dz /= len;
        }
        consumer.vertex(pose.pose(), x1, y1, z1).color(r, g, b, a).normal(pose.normal(), dx, dy, dz).endVertex();
        consumer.vertex(pose.pose(), x2, y2, z2).color(r, g, b, a).normal(pose.normal(), dx, dy, dz).endVertex();
    }
}
