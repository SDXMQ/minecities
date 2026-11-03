package com.citiescolony.client;

import com.citiescolony.api.ZoneData;
import com.citiescolony.api.ZoneType;
import com.citiescolony.common.entity.ColonyCitizenEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Renders 3D zone translucent boxes, drag selection grids, and citizen selection outlines
 * strictly using Minecraft 26.2 Blaze3D PoseStack, VertexConsumer, and RenderType without raw OpenGL calls.
 */
public final class ZoneGridBlazeRenderer {

    private static final List<ZoneData> CLIENT_ZONES = new CopyOnWriteArrayList<>();

    private ZoneGridBlazeRenderer() {
    }

    public static void updateClientZones(Collection<ZoneData> syncedZones) {
        CLIENT_ZONES.clear();
        if (syncedZones != null) {
            CLIENT_ZONES.addAll(syncedZones);
        }
    }

    public static List<ZoneData> getClientZones() {
        return List.copyOf(CLIENT_ZONES);
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        RtsCameraController.updateMatrices(event.getModelViewMatrix(), event.getProjectionMatrix());

        PoseStack poseStack = event.getPoseStack();
        Vec3 camPos = event.getCamera().getPosition();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        // 1. Render all persisted city zones with their ZoneType color
        for (ZoneData zone : CLIENT_ZONES) {
            BoundingBox b = zone.bounds();
            ZoneType type = zone.type();
            renderTranslucentBox(
                    poseStack,
                    bufferSource.getBuffer(RenderType.debugFilledBox()),
                    b.minX(), b.minY(), b.minZ(),
                    b.maxX() + 1.0F, b.maxY() + 1.0F, b.maxZ() + 1.0F,
                    type.red(), type.green(), type.blue(), 0.26F
            );
            renderWireframeBox(
                    poseStack,
                    bufferSource.getBuffer(RenderType.lines()),
                    b.minX(), b.minY(), b.minZ(),
                    b.maxX() + 1.0F, b.maxY() + 1.0F, b.maxZ() + 1.0F,
                    type.red(), type.green(), type.blue(), 0.88F
            );
        }

        // 2. Render active left-click drag selection box and ground block grid in RTS mode
        Optional<BlockPos> dragStartOpt = RtsCameraController.getDragStartPos();
        Optional<BlockPos> dragEndOpt = RtsCameraController.getDragCurrentPos();
        if (RtsCameraController.isActive() && dragStartOpt.isPresent() && dragEndOpt.isPresent()) {
            BoundingBox dragBox = BoundingBox.fromCorners(dragStartOpt.get(), dragEndOpt.get().above(4));
            ZoneType activeType = RtsCameraController.getSelectedZoneType();
            float r = RtsCameraController.isDeleteZoneMode() ? 1.0F : activeType.red();
            float g = RtsCameraController.isDeleteZoneMode() ? 0.1F : activeType.green();
            float b = RtsCameraController.isDeleteZoneMode() ? 0.1F : activeType.blue();

            renderTranslucentBox(
                    poseStack,
                    bufferSource.getBuffer(RenderType.debugFilledBox()),
                    dragBox.minX(), dragBox.minY(), dragBox.minZ(),
                    dragBox.maxX() + 1.0F, dragBox.maxY() + 1.0F, dragBox.maxZ() + 1.0F,
                    r, g, b, 0.35F
            );
            renderGroundGrid(
                    poseStack,
                    bufferSource.getBuffer(RenderType.lines()),
                    dragBox,
                    r, g, b, 0.95F
            );
        }

        // 3. Render selection boxes around selected ColonyCitizenEntity units
        if (RtsCameraController.isActive()) {
            VertexConsumer lineConsumer = bufferSource.getBuffer(RenderType.lines());
            for (int entityId : RtsCameraController.getSelectedCitizenIds()) {
                Entity entity = mc.level.getEntity(entityId);
                if (entity instanceof ColonyCitizenEntity citizen && citizen.isAlive()) {
                    AABB box = citizen.getBoundingBox().inflate(0.15D);
                    renderWireframeBox(
                            poseStack,
                            lineConsumer,
                            (float) box.minX, (float) box.minY, (float) box.minZ,
                            (float) box.maxX, (float) box.maxY, (float) box.maxZ,
                            0.15F, 1.0F, 0.45F, 1.0F
                    );
                }
            }
        }

        poseStack.popPose();
        bufferSource.endBatch(RenderType.debugFilledBox());
        bufferSource.endBatch(RenderType.lines());
    }

    public static void renderTranslucentBox(
            PoseStack poseStack,
            VertexConsumer consumer,
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ,
            float r, float g, float b, float a
    ) {
        Matrix4f matrix = poseStack.last().pose();

        // Bottom face (Y = minY)
        addQuad(consumer, matrix, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a);
        // Top face (Y = maxY)
        addQuad(consumer, matrix, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, minX, maxY, minZ, r, g, b, a);
        // North face (Z = minZ)
        addQuad(consumer, matrix, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ, r, g, b, a);
        // South face (Z = maxZ)
        addQuad(consumer, matrix, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, minX, minY, maxZ, r, g, b, a);
        // West face (X = minX)
        addQuad(consumer, matrix, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, minX, minY, minZ, r, g, b, a);
        // East face (X = maxX)
        addQuad(consumer, matrix, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ, r, g, b, a);
    }

    private static void addQuad(
            VertexConsumer consumer,
            Matrix4f matrix,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            float x4, float y4, float z4,
            float r, float g, float b, float a
    ) {
        consumer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        consumer.addVertex(matrix, x3, y3, z3).setColor(r, g, b, a);
        consumer.addVertex(matrix, x4, y4, z4).setColor(r, g, b, a);
    }

    public static void renderGroundGrid(
            PoseStack poseStack,
            VertexConsumer lineConsumer,
            BoundingBox box,
            float r, float g, float b, float a
    ) {
        float y = box.minY() + 0.03F;
        int minX = box.minX();
        int maxX = box.maxX() + 1;
        int minZ = box.minZ();
        int maxZ = box.maxZ() + 1;

        for (int x = minX; x <= maxX; x++) {
            addLine(poseStack, lineConsumer, x, y, minZ, x, y, maxZ, r, g, b, a);
        }
        for (int z = minZ; z <= maxZ; z++) {
            addLine(poseStack, lineConsumer, minX, y, z, maxX, y, z, r, g, b, a);
        }
    }

    public static void renderWireframeBox(
            PoseStack poseStack,
            VertexConsumer lineConsumer,
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ,
            float r, float g, float b, float a
    ) {
        // Bottom rectangle
        addLine(poseStack, lineConsumer, minX, minY, minZ, maxX, minY, minZ, r, g, b, a);
        addLine(poseStack, lineConsumer, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, a);
        addLine(poseStack, lineConsumer, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a);
        addLine(poseStack, lineConsumer, minX, minY, maxZ, minX, minY, minZ, r, g, b, a);

        // Top rectangle
        addLine(poseStack, lineConsumer, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, a);
        addLine(poseStack, lineConsumer, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, a);
        addLine(poseStack, lineConsumer, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a);
        addLine(poseStack, lineConsumer, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, a);

        // Vertical pillars
        addLine(poseStack, lineConsumer, minX, minY, minZ, minX, maxY, minZ, r, g, b, a);
        addLine(poseStack, lineConsumer, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, a);
        addLine(poseStack, lineConsumer, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, a);
        addLine(poseStack, lineConsumer, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, a);
    }

    private static void addLine(
            PoseStack poseStack,
            VertexConsumer consumer,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float r, float g, float b, float a
    ) {
        PoseStack.Pose pose = poseStack.last();
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 1.0E-5F) {
            dx /= len;
            dy /= len;
            dz /= len;
        } else {
            dx = 0.0F;
            dy = 1.0F;
            dz = 0.0F;
        }
        consumer.addVertex(pose, x1, y1, z1).setColor(r, g, b, a).setNormal(pose, dx, dy, dz);
        consumer.addVertex(pose, x2, y2, z2).setColor(r, g, b, a).setNormal(pose, dx, dy, dz);
    }
}
