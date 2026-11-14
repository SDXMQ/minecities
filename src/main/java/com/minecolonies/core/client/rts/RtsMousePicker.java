package com.minecolonies.core.client.rts;

import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import net.minecraft.util.Tuple;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Map;

/**
 * Unprojects 2D mouse cursor coordinates into a 3D world ray using the RTS camera's View/Projection matrices,
 * and raycasts terrain blocks, MineColonies buildings, and citizen entities.
 */
public final class RtsMousePicker
{
    public record Ray(@NotNull Vec3 origin, @NotNull Vec3 direction)
    {
    }

    private RtsMousePicker()
    {
    }

    /**
     * Pure math unprojection from screen coordinates to a normalized 3D world ray.
     */
    @NotNull
    public static Ray unprojectMouseToWorldRay(
        final double mouseX,
        final double mouseY,
        final double screenWidth,
        final double screenHeight,
        @NotNull final Vec3 cameraPos,
        @NotNull final Matrix4f viewMatrix,
        @NotNull final Matrix4f projMatrix)
    {
        final float ndcX = (float) ((2.0D * mouseX) / Math.max(1.0D, screenWidth) - 1.0D);
        final float ndcY = (float) (1.0D - (2.0D * mouseY) / Math.max(1.0D, screenHeight));

        final Matrix4f invProj = new Matrix4f(projMatrix).invert();
        final Vector4f rayClip = new Vector4f(ndcX, ndcY, -1.0F, 1.0F);
        final Vector4f rayEye = invProj.transform(rayClip);
        rayEye.z = -1.0F;
        rayEye.w = 0.0F;

        final Matrix4f invView = new Matrix4f(viewMatrix).invert();
        final Vector4f rayWorld = invView.transform(rayEye);

        Vec3 dir = new Vec3(rayWorld.x(), rayWorld.y(), rayWorld.z());
        if (dir.lengthSqr() < 1.0E-8D)
        {
            dir = new Vec3(0.0D, -1.0D, 0.0D);
        }
        else
        {
            dir = dir.normalize();
        }
        return new Ray(cameraPos, dir);
    }

    @NotNull
    public static Ray getMouseWorldRay(@NotNull final Minecraft mc)
    {
        final double mouseX = mc.mouseHandler.xpos();
        final double mouseY = mc.mouseHandler.ypos();
        final double width = mc.getWindow().getScreenWidth();
        final double height = mc.getWindow().getScreenHeight();

        final float fov = mc.options.fov().get().floatValue();
        final Matrix4f projMatrix = mc.gameRenderer.getProjectionMatrix(fov);

        final Matrix4f viewMatrix = new Matrix4f()
            .rotation(Axis.XP.rotationDegrees(RtsCameraController.getPitch()))
            .rotate(Axis.YP.rotationDegrees(RtsCameraController.getYaw() + 180.0F));

        return unprojectMouseToWorldRay(
            mouseX,
            mouseY,
            width,
            height,
            RtsCameraController.getCameraPos(),
            viewMatrix,
            projMatrix
        );
    }

    @Nullable
    public static BlockHitResult pickBlock(@NotNull final Minecraft mc, final double maxDistance)
    {
        if (mc.level == null || mc.player == null)
        {
            return null;
        }
        final Ray ray = getMouseWorldRay(mc);
        final Vec3 end = ray.origin().add(ray.direction().scale(maxDistance));
        return mc.level.clip(new ClipContext(
            ray.origin(),
            end,
            ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE,
            mc.player
        ));
    }

    @Nullable
    public static EntityHitResult pickEntity(@NotNull final Minecraft mc, final double maxDistance)
    {
        if (mc.level == null || mc.player == null)
        {
            return null;
        }
        final Ray ray = getMouseWorldRay(mc);
        final Vec3 end = ray.origin().add(ray.direction().scale(maxDistance));
        final AABB searchBox = new AABB(ray.origin(), end).inflate(2.0D);

        // Prioritize MineColonies citizens first
        final EntityHitResult citizenHit = ProjectileUtil.getEntityHitResult(
            mc.level,
            mc.player,
            ray.origin(),
            end,
            searchBox,
            entity -> !entity.isSpectator() && entity.isPickable() && entity instanceof AbstractEntityCitizen,
            0.45F
        );
        if (citizenHit != null)
        {
            return citizenHit;
        }

        return ProjectileUtil.getEntityHitResult(
            mc.level,
            mc.player,
            ray.origin(),
            end,
            searchBox,
            (final Entity entity) -> !entity.isSpectator() && entity.isPickable() && entity instanceof LivingEntity && entity != mc.player,
            0.35F
        );
    }

    /**
     * Resolves a MineColonies {@link IBuildingView} at or enclosing the given block position.
     */
    @Nullable
    public static IBuildingView pickBuildingView(@NotNull final Minecraft mc, @Nullable final BlockPos hitPos)
    {
        if (mc.level == null || hitPos == null)
        {
            return null;
        }

        final IBuildingView directView = IColonyManager.getInstance().getBuildingView(mc.level.dimension(), hitPos);
        if (directView != null)
        {
            return directView;
        }

        final IColonyView colonyView = IColonyManager.getInstance().getClosestColonyView(mc.level, hitPos);
        if (colonyView == null)
        {
            return null;
        }

        IBuildingView closestInside = null;
        double closestDistSqr = Double.MAX_VALUE;
        for (final Map.Entry<BlockPos, IBuildingView> entry : colonyView.getClientBuildingManager().getBuildings().entrySet())
        {
            final IBuildingView view = entry.getValue();
            final double hutDistSqr = entry.getKey().distSqr(hitPos);
            if (hutDistSqr <= 4.0D && hutDistSqr < closestDistSqr)
            {
                closestDistSqr = hutDistSqr;
                closestInside = view;
                continue;
            }

            if (mc.level.getBlockEntity(entry.getKey()) instanceof AbstractTileEntityColonyBuilding buildingTe)
            {
                final Tuple<BlockPos, BlockPos> corners = buildingTe.getInWorldCorners();
                if (corners != null && corners.getA() != null && corners.getB() != null && !corners.getA().equals(corners.getB()))
                {
                    final BlockPos a = corners.getA();
                    final BlockPos b = corners.getB();
                    final int minX = Math.min(a.getX(), b.getX());
                    final int maxX = Math.max(a.getX(), b.getX());
                    final int minY = Math.min(a.getY(), b.getY());
                    final int maxY = Math.max(a.getY(), b.getY());
                    final int minZ = Math.min(a.getZ(), b.getZ());
                    final int maxZ = Math.max(a.getZ(), b.getZ());
                    if (hitPos.getX() >= minX && hitPos.getX() <= maxX
                        && hitPos.getY() >= minY && hitPos.getY() <= maxY
                        && hitPos.getZ() >= minZ && hitPos.getZ() <= maxZ
                        && hutDistSqr < closestDistSqr)
                    {
                        closestDistSqr = hutDistSqr;
                        closestInside = view;
                    }
                }
            }
        }
        return closestInside;
    }

    /**
     * Checks whether the block at {@code hitPos} is a MineColonies hut block (e.g., an un-founded Town Hall).
     */
    public static boolean isHutBlock(@NotNull final Minecraft mc, @Nullable final BlockPos hitPos)
    {
        return mc.level != null
            && hitPos != null
            && mc.level.getBlockState(hitPos).getBlock() instanceof AbstractBlockHut<?>;
    }
}
