package com.minecolonies.core.client.rts;

import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.api.configuration.RtsZonePlacementMode;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.rts.RtsZoneType;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 3rd-person Cities-like quarter-view RTS camera controller, mouse drag handler, and citizen selector.
 */
public final class RtsCameraController
{
    public static final double MIN_ALTITUDE = 12.0D;
    public static final double MAX_ALTITUDE = 80.0D;
    public static final float MIN_PITCH = 45.0F;
    public static final float MAX_PITCH = 60.0F;
    private static final double BASE_PAN_SPEED = 0.85D;

    private static final String[] CITIZEN_JOB_CYCLE = {
        "",
        "builder",
        "lumberjack",
        "miner",
        "farmer",
        "deliveryman",
        "knight",
        "archer"
    };

    private static final String[] CITIZEN_JOB_LABELS = {
        "구역기본",
        "건축가(Builder)",
        "벌목꾼(Lumberjack)",
        "광부(Miner)",
        "농부(Farmer)",
        "택배원(Deliveryman)",
        "경비병(Knight)",
        "궁수(Archer)"
    };

    private static boolean active = false;
    private static Vec3 cameraPos = Vec3.ZERO;
    private static double altitude = 25.0D;
    private static float yaw = 0.0F;
    private static float pitch = 52.5F;

    private static double lastMouseX = 0.0D;
    private static double lastMouseY = 0.0D;
    private static boolean middleMouseDown = false;

    private static RtsZoneType activeZoneType = RtsZoneType.RESIDENTIAL;
    private static boolean deleteMode = false;
    @Nullable
    private static RtsZonePlacementMode placementModeOverride = null;
    private static int activeJobIndex = 0;

    private static boolean isDragging = false;
    private static boolean shiftBoxSelectDrag = false;
    @Nullable
    private static BlockPos dragStartPos = null;
    @Nullable
    private static BlockPos dragEndPos = null;
    @Nullable
    private static BlockPos hoveredBlockPos = null;
    @Nullable
    private static BlockPos hoveredBuildingPos = null;

    private static final Set<Integer> selectedCitizenEntityIds = new LinkedHashSet<>();
    private static double pivotFocusX = 0.0D;
    private static double pivotFocusZ = 0.0D;

    private RtsCameraController()
    {
    }

    public static double clampAltitude(final double value)
    {
        return Mth.clamp(value, MIN_ALTITUDE, MAX_ALTITUDE);
    }

    public static float clampPitch(final float value)
    {
        return Mth.clamp(value, MIN_PITCH, MAX_PITCH);
    }

    @NotNull
    public static Vec3 computePanDelta(
        final float yawDegrees,
        final double altitudeValue,
        final boolean forward,
        final boolean backward,
        final boolean left,
        final boolean right)
    {
        final float rad = yawDegrees * Mth.DEG_TO_RAD;
        final double forwardX = -Mth.sin(rad);
        final double forwardZ = Mth.cos(rad);
        final double rightX = Mth.cos(rad);
        final double rightZ = Mth.sin(rad);

        final double speedScale = BASE_PAN_SPEED * (clampAltitude(altitudeValue) / 25.0D);
        double dx = 0.0D;
        double dz = 0.0D;

        if (forward)
        {
            dx += forwardX * speedScale;
            dz += forwardZ * speedScale;
        }
        if (backward)
        {
            dx -= forwardX * speedScale;
            dz -= forwardZ * speedScale;
        }
        if (right)
        {
            dx -= rightX * speedScale;
            dz -= rightZ * speedScale;
        }
        if (left)
        {
            dx += rightX * speedScale;
            dz += rightZ * speedScale;
        }
        return new Vec3(dx, 0.0D, dz);
    }

    public static boolean isActive()
    {
        return active;
    }

    public static void setActiveForTesting(final boolean state)
    {
        active = state;
    }

    @NotNull
    public static Vec3 getCameraPos()
    {
        return cameraPos;
    }

    public static double getAltitude()
    {
        return altitude;
    }

    public static float getYaw()
    {
        return yaw;
    }

    public static float getPitch()
    {
        return pitch;
    }

    @NotNull
    public static RtsZoneType getActiveZoneType()
    {
        return activeZoneType;
    }

    public static boolean isDeleteMode()
    {
        return deleteMode;
    }

    @NotNull
    public static RtsZonePlacementMode getActivePlacementMode()
    {
        if (placementModeOverride != null)
        {
            return placementModeOverride;
        }
        try
        {
            return MineColonies.getConfig().getClient().rtsZonePlacementMode.get();
        }
        catch (final Exception ignored)
        {
            return RtsZonePlacementMode.MODE_A_AUTO_PLACE;
        }
    }

    @Nullable
    public static String getActiveJobIdOverride()
    {
        final String id = CITIZEN_JOB_CYCLE[activeJobIndex];
        return id.isEmpty() ? null : id;
    }

    @NotNull
    public static String getActiveJobDisplayLabel()
    {
        return CITIZEN_JOB_LABELS[activeJobIndex];
    }

    public static boolean isDragging()
    {
        return isDragging;
    }

    public static boolean isShiftBoxSelectDrag()
    {
        return shiftBoxSelectDrag;
    }

    @Nullable
    public static BlockPos getDragStartPos()
    {
        return dragStartPos;
    }

    @Nullable
    public static BlockPos getDragEndPos()
    {
        return dragEndPos;
    }

    @Nullable
    public static BlockPos getHoveredBlockPos()
    {
        return hoveredBlockPos;
    }

    @Nullable
    public static BlockPos getHoveredBuildingPos()
    {
        return hoveredBuildingPos;
    }

    @NotNull
    public static Set<Integer> getSelectedCitizens()
    {
        return Collections.unmodifiableSet(selectedCitizenEntityIds);
    }

    public static void toggleRtsMode(@NotNull final Minecraft mc)
    {
        if (mc.player == null)
        {
            active = false;
            return;
        }

        active = !active;
        isDragging = false;
        dragStartPos = null;
        dragEndPos = null;
        hoveredBlockPos = null;
        hoveredBuildingPos = null;

        if (active)
        {
            final Vec3 playerPos = mc.player.position();
            altitude = clampAltitude(altitude);
            pitch = clampPitch(pitch);
            yaw = mc.player.getYRot();
            cameraPos = new Vec3(playerPos.x, playerPos.y + altitude, playerPos.z);
            mc.player.setDeltaMovement(0.0D, mc.player.getDeltaMovement().y, 0.0D);
            mc.mouseHandler.releaseMouse();
            lastMouseX = mc.mouseHandler.xpos();
            lastMouseY = mc.mouseHandler.ypos();
            mc.gui.setOverlayMessage(
                Component.literal("[RTS 모드 활성화] WASD:이동 | 휠:줌 | 좌드래그:구역(" + getActivePlacementMode().getDisplayName() + ") | 우클릭:원격GUI/방식B"),
                false
            );
        }
        else
        {
            if (mc.screen == null)
            {
                mc.mouseHandler.grabMouse();
            }
            mc.gui.setOverlayMessage(Component.literal("[1인칭 기본 시점 복귀]"), false);
        }
    }

    public static void onClientTick(@NotNull final TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }

        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null)
        {
            active = false;
            return;
        }

        while (RtsKeyMappings.TOGGLE_RTS_CAMERA.get().consumeClick())
        {
            toggleRtsMode(mc);
        }

        if (!active)
        {
            return;
        }

        while (RtsKeyMappings.CYCLE_ZONE_TYPE.get().consumeClick())
        {
            activeZoneType = activeZoneType.next();
            deleteMode = false;
            mc.gui.setOverlayMessage(Component.literal("[RTS] 선택 구역: " + activeZoneType.getDisplayName()), false);
        }

        while (RtsKeyMappings.TOGGLE_DELETE_MODE.get().consumeClick())
        {
            deleteMode = !deleteMode;
            mc.gui.setOverlayMessage(
                Component.literal(deleteMode ? "[RTS] 구역 해제(철거) 모드" : "[RTS] 구역 지정 모드: " + activeZoneType.getDisplayName()),
                false
            );
        }

        while (RtsKeyMappings.CYCLE_CITIZEN_JOB.get().consumeClick())
        {
            activeJobIndex = (activeJobIndex + 1) % CITIZEN_JOB_CYCLE.length;
            mc.gui.setOverlayMessage(
                Component.literal("[RTS] 시민 배치 직업: " + getActiveJobDisplayLabel()),
                false
            );
        }

        while (RtsKeyMappings.TOGGLE_PLACEMENT_MODE.get().consumeClick())
        {
            final RtsZonePlacementMode nextMode = getActivePlacementMode().next();
            placementModeOverride = nextMode;
            try
            {
                MineColonies.getConfig().getClient().rtsZonePlacementMode.set(nextMode);
            }
            catch (final Exception ignored)
            {
            }
            mc.gui.setOverlayMessage(
                Component.literal("[RTS] 구역 배치 방식 전환: " + nextMode.getDisplayName()),
                false
            );
        }

        // Keep mouse cursor free for RTS picking when no GUI screen is open
        if (mc.screen == null && mc.mouseHandler.isMouseGrabbed())
        {
            mc.mouseHandler.releaseMouse();
        }

        if (mc.screen == null)
        {
            handleKeyboardPan(mc);
            updateHoverAndDragTarget(mc);
        }
    }

    private static void handleKeyboardPan(@NotNull final Minecraft mc)
    {
        final long window = mc.getWindow().getWindow();
        final boolean w = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_W);
        final boolean s = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_S);
        final boolean a = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_A);
        final boolean d = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_D);

        // Prevent player avatar from walking while panning RTS camera
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);

        final Vec3 delta = computePanDelta(yaw, altitude, w, s, a, d);
        final double baseY = mc.player != null ? mc.player.getY() : cameraPos.y - altitude;
        cameraPos = new Vec3(cameraPos.x + delta.x, baseY + altitude, cameraPos.z + delta.z);
    }

    private static void updateHoverAndDragTarget(@NotNull final Minecraft mc)
    {
        final BlockHitResult hit = RtsMousePicker.pickBlock(mc, 160.0D);
        if (hit != null && hit.getType() == HitResult.Type.BLOCK)
        {
            hoveredBlockPos = hit.getBlockPos();
            final IBuildingView buildingView = RtsMousePicker.pickBuildingView(mc, hoveredBlockPos);
            hoveredBuildingPos = buildingView != null ? buildingView.getPosition() : null;
            if (isDragging)
            {
                dragEndPos = hoveredBlockPos;
            }
        }
        else
        {
            hoveredBlockPos = null;
            hoveredBuildingPos = null;
        }
    }

    public static void onComputeCameraAngles(@NotNull final ViewportEvent.ComputeCameraAngles event)
    {
        if (!active)
        {
            return;
        }

        final Minecraft mc = Minecraft.getInstance();
        final double mouseX = mc.mouseHandler.xpos();
        final double mouseY = mc.mouseHandler.ypos();
        final long window = mc.getWindow().getWindow();

        if (mc.screen == null && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS)
        {
            if (!middleMouseDown)
            {
                final float curPitchRad = pitch * Mth.DEG_TO_RAD;
                final float curYawRad = yaw * Mth.DEG_TO_RAD;
                final double curHorizDist = altitude / Math.max(0.1D, Math.tan(curPitchRad));
                pivotFocusX = cameraPos.x - curHorizDist * Mth.sin(curYawRad);
                pivotFocusZ = cameraPos.z + curHorizDist * Mth.cos(curYawRad);
            }
            else
            {
                final double dx = mouseX - lastMouseX;
                final double dy = mouseY - lastMouseY;
                yaw = (yaw + (float) (dx * 0.35D)) % 360.0F;
                if (yaw < 0.0F)
                {
                    yaw += 360.0F;
                }
                pitch = clampPitch(pitch + (float) (dy * 0.25D));

                final float newPitchRad = pitch * Mth.DEG_TO_RAD;
                final float newYawRad = yaw * Mth.DEG_TO_RAD;
                final double newHorizDist = altitude / Math.max(0.1D, Math.tan(newPitchRad));
                final double baseY = mc.player != null ? mc.player.getY() : cameraPos.y - altitude;
                cameraPos = new Vec3(
                    pivotFocusX + newHorizDist * Mth.sin(newYawRad),
                    baseY + altitude,
                    pivotFocusZ - newHorizDist * Mth.cos(newYawRad)
                );
            }
            middleMouseDown = true;
        }
        else
        {
            middleMouseDown = false;
        }

        lastMouseX = mouseX;
        lastMouseY = mouseY;

        event.setYaw(yaw);
        event.setPitch(pitch);

        final Camera camera = event.getCamera();
        applyCameraPosition(camera, cameraPos.x, cameraPos.y, cameraPos.z);
    }

    private static java.lang.reflect.Method cameraSetPositionMethod = null;
    private static boolean cameraMethodResolved = false;

    private static void applyCameraPosition(@NotNull final Camera camera, final double x, final double y, final double z)
    {
        if (!cameraMethodResolved)
        {
            cameraMethodResolved = true;
            try
            {
                cameraSetPositionMethod = net.minecraftforge.fml.util.ObfuscationReflectionHelper.findMethod(
                    Camera.class,
                    "m_90580_",
                    double.class,
                    double.class,
                    double.class
                );
                cameraSetPositionMethod.setAccessible(true);
            }
            catch (final Throwable t)
            {
                try
                {
                    cameraSetPositionMethod = Camera.class.getDeclaredMethod("setPosition", double.class, double.class, double.class);
                    cameraSetPositionMethod.setAccessible(true);
                }
                catch (final Throwable ignored)
                {
                }
            }
        }
        if (cameraSetPositionMethod != null)
        {
            try
            {
                cameraSetPositionMethod.invoke(camera, x, y, z);
            }
            catch (final Throwable ignored)
            {
            }
        }
    }

    public static void suppressMovementInput(@NotNull final net.minecraft.client.player.Input input)
    {
        if (!active)
        {
            return;
        }
        input.forwardImpulse = 0.0F;
        input.leftImpulse = 0.0F;
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.jumping = false;
        input.shiftKeyDown = false;
    }

    public static void onMovementInputUpdate(@NotNull final net.minecraftforge.client.event.MovementInputUpdateEvent event)
    {
        if (!active)
        {
            return;
        }
        suppressMovementInput(event.getInput());
        final Entity player = event.getEntity();
        if (player != null)
        {
            final Vec3 dm = player.getDeltaMovement();
            player.setDeltaMovement(0.0D, dm.y, 0.0D);
        }
    }

    public static void onMouseScroll(@NotNull final InputEvent.MouseScrollingEvent event)
    {
        if (!active || Minecraft.getInstance().screen != null)
        {
            return;
        }

        final double scrollY = event.getScrollDelta();
        if (scrollY != 0.0D)
        {
            altitude = clampAltitude(altitude - scrollY * 2.5D);
            final Minecraft mc = Minecraft.getInstance();
            final double baseY = mc.player != null ? mc.player.getY() : cameraPos.y - altitude;
            cameraPos = new Vec3(cameraPos.x, baseY + altitude, cameraPos.z);
            event.setCanceled(true);
        }
    }

    public static void onMouseButtonPre(@NotNull final InputEvent.MouseButton.Pre event)
    {
        final Minecraft mc = Minecraft.getInstance();
        if (!active || mc.screen != null || mc.level == null || mc.player == null)
        {
            return;
        }

        final int button = event.getButton();
        final int action = event.getAction();
        final boolean shiftDown = Screen.hasShiftDown();
        final boolean altDown = Screen.hasAltDown();

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
        {
            if (action == GLFW.GLFW_PRESS)
            {
                if (!shiftDown)
                {
                    final EntityHitResult entityHit = RtsMousePicker.pickEntity(mc, 160.0D);
                    if (entityHit != null)
                    {
                        final Entity hitEntity = entityHit.getEntity();
                        if (hitEntity instanceof AbstractEntityCitizen)
                        {
                            final int id = hitEntity.getId();
                            if (selectedCitizenEntityIds.contains(id))
                            {
                                selectedCitizenEntityIds.remove(id);
                            }
                            else
                            {
                                selectedCitizenEntityIds.clear();
                                selectedCitizenEntityIds.add(id);
                            }
                            mc.gui.setOverlayMessage(
                                Component.literal("[RTS] 시민 선택됨 (" + selectedCitizenEntityIds.size() + "명) - 우클릭: 이동/직업배치"),
                                false
                            );
                            event.setCanceled(true);
                            return;
                        }
                    }
                }

                final BlockHitResult blockHit = RtsMousePicker.pickBlock(mc, 160.0D);
                if (blockHit != null && blockHit.getType() == HitResult.Type.BLOCK)
                {
                    isDragging = true;
                    shiftBoxSelectDrag = shiftDown;
                    dragStartPos = blockHit.getBlockPos();
                    dragEndPos = blockHit.getBlockPos();
                }
            }
            else if (action == GLFW.GLFW_RELEASE && isDragging)
            {
                final BlockHitResult blockHit = RtsMousePicker.pickBlock(mc, 160.0D);
                if (blockHit != null && blockHit.getType() == HitResult.Type.BLOCK)
                {
                    dragEndPos = blockHit.getBlockPos();
                }

                if (dragStartPos != null && dragEndPos != null)
                {
                    if (shiftBoxSelectDrag)
                    {
                        selectCitizensInBox(mc, dragStartPos, dragEndPos);
                    }
                    else
                    {
                        RtsInteractionBridge.submitZoneSelection(
                            dragStartPos,
                            dragEndPos,
                            activeZoneType,
                            deleteMode,
                            getActivePlacementMode()
                        );
                    }
                }
                isDragging = false;
                shiftBoxSelectDrag = false;
            }
            event.setCanceled(true);
        }
        else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
        {
            if (action == GLFW.GLFW_PRESS)
            {
                RtsInteractionBridge.handleRightClickInRts(
                    mc,
                    selectedCitizenEntityIds,
                    activeZoneType,
                    getActiveJobIdOverride(),
                    shiftDown,
                    altDown
                );
            }
            event.setCanceled(true);
        }
    }

    private static void selectCitizensInBox(
        @NotNull final Minecraft mc,
        @NotNull final BlockPos start,
        @NotNull final BlockPos end)
    {
        if (mc.level == null)
        {
            return;
        }
        final AABB selectionBox = new AABB(
            Math.min(start.getX(), end.getX()),
            Math.min(start.getY(), end.getY()) - 3.0D,
            Math.min(start.getZ(), end.getZ()),
            Math.max(start.getX(), end.getX()) + 1.0D,
            Math.max(start.getY(), end.getY()) + 6.0D,
            Math.max(start.getZ(), end.getZ()) + 1.0D
        );

        final List<AbstractEntityCitizen> citizens = mc.level.getEntitiesOfClass(AbstractEntityCitizen.class, selectionBox);
        selectedCitizenEntityIds.clear();
        for (final AbstractEntityCitizen citizen : citizens)
        {
            selectedCitizenEntityIds.add(citizen.getId());
        }
        mc.gui.setOverlayMessage(
            Component.literal("[RTS] 영역 내 시민 " + selectedCitizenEntityIds.size() + "명 다중 선택됨"),
            false
        );
    }
}
