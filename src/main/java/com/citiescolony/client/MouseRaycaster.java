package com.citiescolony.client;

import com.citiescolony.common.entity.ColonyCitizenEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.List;
import java.util.Optional;

/**
 * Unprojects 2D screen cursor coordinates using the camera's View and Projection matrices
 * into a 3D world-space ray to accurately detect hovered ground BlockPos and ColonyCitizenEntity units.
 */
public final class MouseRaycaster {

    public static final double MAX_RAY_DISTANCE = 160.0D;

    public record Ray(Vec3 origin, Vec3 direction) {
        public Vec3 pointAt(double distance) {
            return origin.add(direction.scale(distance));
        }
    }

    public record RaycastResult(
            Ray ray,
            Optional<BlockPos> hitBlockPos,
            Optional<ColonyCitizenEntity> hitCitizen
    ) {
    }

    private MouseRaycaster() {
    }

    /**
     * Unprojects 2D mouse coordinates (in window pixels, top-left origin) into a normalized 3D world ray.
     */
    public static Ray unprojectMouseToWorldRay(
            double mouseX,
            double mouseY,
            int viewportWidth,
            int viewportHeight,
            Vec3 cameraWorldPos,
            Matrix4f viewMatrix,
            Matrix4f projectionMatrix
    ) {
        int safeWidth = Math.max(1, viewportWidth);
        int safeHeight = Math.max(1, viewportHeight);

        float ndcX = (float) ((2.0D * mouseX) / safeWidth - 1.0D);
        float ndcY = (float) (1.0D - (2.0D * mouseY) / safeHeight);

        Matrix4f invViewProj = new Matrix4f(projectionMatrix).mul(viewMatrix).invert();

        Vector4f nearClip = new Vector4f(ndcX, ndcY, -1.0F, 1.0F);
        Vector4f farClip = new Vector4f(ndcX, ndcY, 1.0F, 1.0F);

        invViewProj.transform(nearClip);
        invViewProj.transform(farClip);

        if (Math.abs(nearClip.w) > 1.0E-6F) {
            nearClip.div(nearClip.w);
        }
        if (Math.abs(farClip.w) > 1.0E-6F) {
            farClip.div(farClip.w);
        }

        Vector3f dir = new Vector3f(
                farClip.x - nearClip.x,
                farClip.y - nearClip.y,
                farClip.z - nearClip.z
        );
        if (dir.lengthSquared() < 1.0E-8F) {
            return computeTrigFallbackRay(cameraWorldPos, RtsCameraController.getYaw(), RtsCameraController.getPitch());
        }
        dir.normalize();
        return new Ray(cameraWorldPos, new Vec3(dir.x, dir.y, dir.z));
    }

    public static Ray computeTrigFallbackRay(Vec3 cameraWorldPos, float yawDegrees, float pitchDegrees) {
        Vec3 dir = Vec3.directionFromRotation(pitchDegrees, yawDegrees).normalize();
        return new Ray(cameraWorldPos, dir);
    }

    public static RaycastResult raycastFromMouse(Minecraft mc, double mouseX, double mouseY) {
        if (mc.level == null || mc.player == null) {
            Ray emptyRay = new Ray(Vec3.ZERO, new Vec3(0.0D, -1.0D, 0.0D));
            return new RaycastResult(emptyRay, Optional.empty(), Optional.empty());
        }

        Vec3 cameraPos = RtsCameraController.isActive()
                ? RtsCameraController.getCameraWorldPosition()
                : mc.gameRenderer.getMainCamera().getPosition();

        int width = mc.getWindow().getScreenWidth();
        int height = mc.getWindow().getScreenHeight();

        Matrix4f proj = RtsCameraController.getProjectionMatrix();
        Matrix4f view = RtsCameraController.getViewMatrix();

        Ray ray;
        if (Math.abs(proj.m00()) < 1.0E-5F) {
            double fov = mc.options.fov().get();
            float aspect = (float) width / (float) Math.max(1, height);
            proj = new Matrix4f().perspective((float) Math.toRadians(fov), aspect, 0.05F, 256.0F);
        }
        ray = unprojectMouseToWorldRay(mouseX, mouseY, width, height, cameraPos, view, proj);

        Vec3 rayStart = ray.origin();
        Vec3 rayEnd = ray.pointAt(MAX_RAY_DISTANCE);

        BlockHitResult blockHit = mc.level.clip(new ClipContext(
                rayStart,
                rayEnd,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                mc.player
        ));

        Optional<BlockPos> hitPos = blockHit.getType() == HitResult.Type.BLOCK
                ? Optional.of(blockHit.getBlockPos().immutable())
                : Optional.empty();

        double maxEntityDist = hitPos
                .map(pos -> Math.sqrt(cameraPos.distanceToSqr(Vec3.atCenterOf(pos))) + 1.0D)
                .orElse(MAX_RAY_DISTANCE);

        Optional<ColonyCitizenEntity> hitCitizen = findIntersectedCitizen(mc, rayStart, ray.pointAt(maxEntityDist));

        return new RaycastResult(ray, hitPos, hitCitizen);
    }

    private static Optional<ColonyCitizenEntity> findIntersectedCitizen(Minecraft mc, Vec3 start, Vec3 end) {
        if (mc.level == null) {
            return Optional.empty();
        }
        AABB searchBounds = new AABB(start, end).inflate(2.0D);
        List<ColonyCitizenEntity> candidates = mc.level.getEntitiesOfClass(ColonyCitizenEntity.class, searchBounds, ColonyCitizenEntity::isAlive);

        ColonyCitizenEntity closest = null;
        double closestDistSqr = Double.MAX_VALUE;

        for (ColonyCitizenEntity citizen : candidates) {
            AABB entityBox = citizen.getBoundingBox().inflate(0.35D);
            Optional<Vec3> clipHit = entityBox.clip(start, end);
            if (clipHit.isPresent()) {
                double distSqr = start.distanceToSqr(clipHit.get());
                if (distSqr < closestDistSqr) {
                    closestDistSqr = distSqr;
                    closest = citizen;
                }
            }
        }
        return Optional.ofNullable(closest);
    }
}
