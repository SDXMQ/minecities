package com.minecolonies.core.client.rts;

import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import net.minecraft.util.Tuple;
import com.minecolonies.core.rts.RtsResourceAggregator;
import com.minecolonies.core.rts.RtsZoneData;
import com.minecolonies.core.rts.RtsZoneType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;

/**
 * 1.20.1 Blaze3D 3D renderer for RTS zones, active drag grid, hovered buildings, and selected citizens.
 */
public final class RtsGridRenderer
{
    private RtsGridRenderer()
    {
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
        RenderSystem.disableCull();

        final VertexConsumer fillBuffer = bufferSource.getBuffer(RenderType.debugFilledBox());
        for (final RtsZoneData zone : RtsResourceAggregator.getClientSnapshot().zones())
        {
            final BoundingBox box = zone.bounds();
            final float y = box.minY() + 1.03F;
            renderFilledQuad(
                poseStack,
                fillBuffer,
                box.minX(),
                y,
                box.minZ(),
                box.maxX() + 1.0F,
                box.maxZ() + 1.0F,
                zone.type().getRed(),
                zone.type().getGreen(),
                zone.type().getBlue(),
                0.28F
            );
        }

        if (RtsCameraController.isDragging())
        {
            final BlockPos start = RtsCameraController.getDragStartPos();
            final BlockPos end = RtsCameraController.getDragEndPos();
            if (start != null && end != null)
            {
                final float minX = Math.min(start.getX(), end.getX());
                final float maxX = Math.max(start.getX(), end.getX()) + 1.0F;
                final float minZ = Math.min(start.getZ(), end.getZ());
                final float maxZ = Math.max(start.getZ(), end.getZ()) + 1.0F;
                final float y = Math.max(start.getY(), end.getY()) + 1.05F;

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
                renderFilledQuad(poseStack, fillBuffer, minX, y, minZ, maxX, maxZ, r, g, b, 0.42F);
            }
        }
        bufferSource.endBatch(RenderType.debugFilledBox());

        final VertexConsumer lineBuffer = bufferSource.getBuffer(RenderType.lines());
        for (final RtsZoneData zone : RtsResourceAggregator.getClientSnapshot().zones())
        {
            final BoundingBox box = zone.bounds();
            final float y = box.minY() + 1.04F;
            renderGridOutline(
                poseStack,
                lineBuffer,
                box.minX(),
                y,
                box.minZ(),
                box.maxX() + 1.0F,
                box.maxZ() + 1.0F,
                zone.type().getRed(),
                zone.type().getGreen(),
                zone.type().getBlue(),
                0.85F,
                false
            );
            if (zone.linkedBuildingPos() != null)
            {
                final BlockPos bp = zone.linkedBuildingPos();
                renderGridOutline(
                    poseStack,
                    lineBuffer,
                    bp.getX(),
                    bp.getY() + 1.05F,
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

        if (RtsCameraController.isDragging())
        {
            final BlockPos start = RtsCameraController.getDragStartPos();
            final BlockPos end = RtsCameraController.getDragEndPos();
            if (start != null && end != null)
            {
                final float minX = Math.min(start.getX(), end.getX());
                final float maxX = Math.max(start.getX(), end.getX()) + 1.0F;
                final float minZ = Math.min(start.getZ(), end.getZ());
                final float maxZ = Math.max(start.getZ(), end.getZ()) + 1.0F;
                final float y = Math.max(start.getY(), end.getY()) + 1.06F;
                renderGridOutline(poseStack, lineBuffer, minX, y, minZ, maxX, maxZ, 1.0F, 1.0F, 1.0F, 0.95F, true);
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
                renderGridOutline(
                    poseStack,
                    lineBuffer,
                    hoveredBlockPos.getX(),
                    hoveredBlockPos.getY() + 1.03F,
                    hoveredBlockPos.getZ(),
                    hoveredBlockPos.getX() + 1.0F,
                    hoveredBlockPos.getZ() + 1.0F,
                    1.0F,
                    1.0F,
                    0.45F,
                    0.90F,
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
                final float ey = (float) entity.getY() + 0.05F;
                final float ez = (float) entity.getZ();
                renderGridOutline(
                    poseStack,
                    lineBuffer,
                    ex - 0.45F,
                    ey,
                    ez - 0.45F,
                    ex + 0.45F,
                    ez + 0.45F,
                    0.15F,
                    1.0F,
                    0.35F,
                    1.0F,
                    false
                );
            }
        }
        bufferSource.endBatch(RenderType.lines());

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        poseStack.popPose();
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
                    final float y = buildingPos.getY() + 1.05F;
                    renderGridOutline(poseStack, lineBuffer, minX, y, minZ, maxX, maxZ, 0.25F, 0.90F, 1.0F, 0.95F, false);
                    return;
                }
            }
        }
        renderGridOutline(
            poseStack,
            lineBuffer,
            buildingPos.getX(),
            buildingPos.getY() + 1.05F,
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
