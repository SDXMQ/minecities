package com.minecolonies.core.client.rts;

import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.api.configuration.RtsZonePlacementMode;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.rts.RtsZoneData;
import com.minecolonies.core.rts.RtsZoneType;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
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

import java.util.Arrays;
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
    public static final double DRAG_THRESHOLD_PX = 6.0D;
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
    private static boolean dragThresholdExceeded = false;
    private static boolean shiftBoxSelectDrag = false;
    private static double dragStartScreenX = 0.0D;
    private static double dragStartScreenY = 0.0D;
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
    private static double smoothedGroundY = 64.0D;
    private static int preserveHotbarSlot = -1;
    private static int preserveHotbarTicks = 0;

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

    /**
     * Computes the camera world position so the center of the viewport looks at {@code focus}
     * from {@code altitudeValue} meters above {@code focus.y} at {@code yawDeg} / {@code pitchDeg}.
     */
    @NotNull
    public static Vec3 computeInitialCameraPos(
        @NotNull final Vec3 focus,
        final float yawDeg,
        final float pitchDeg,
        final double altitudeValue)
    {
        final double clampedAlt = clampAltitude(altitudeValue);
        final float clampedPitch = clampPitch(pitchDeg);
        final float pitchRad = clampedPitch * Mth.DEG_TO_RAD;
        final float yawRad = yawDeg * Mth.DEG_TO_RAD;
        final double horizDist = clampedAlt / Math.max(0.1D, Math.tan(pitchRad));
        return new Vec3(
            focus.x + horizDist * Mth.sin(yawRad),
            focus.y + clampedAlt,
            focus.z - horizDist * Mth.cos(yawRad)
        );
    }

    /**
     * Returns true once the cursor has moved at least {@link #DRAG_THRESHOLD_PX} raw screen pixels from drag start.
     */
    public static boolean isDragThresholdMet(
        final double startScreenX,
        final double startScreenY,
        final double endScreenX,
        final double endScreenY)
    {
        final double dx = endScreenX - startScreenX;
        final double dy = endScreenY - startScreenY;
        return (dx * dx + dy * dy) >= (DRAG_THRESHOLD_PX * DRAG_THRESHOLD_PX);
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

    public static boolean isDragPreviewActive()
    {
        return isDragging && dragThresholdExceeded;
    }

    public static void toggleRtsMode(@NotNull final Minecraft mc)
    {
        if (mc.player == null)
        {
            active = false;
            resetDragState();
            middleMouseDown = false;
            return;
        }

        active = !active;
        resetDragState();
        middleMouseDown = false;
        hoveredBlockPos = null;
        hoveredBuildingPos = null;
        preserveHotbarTicks = 0;

        if (active)
        {
            final Vec3 playerPos = mc.player.position();
            altitude = clampAltitude(altitude);
            pitch = clampPitch(pitch);
            yaw = mc.player.getYRot();
            pivotFocusX = playerPos.x;
            pivotFocusZ = playerPos.z;
            smoothedGroundY = samplePivotGroundY(mc, pivotFocusX, pivotFocusZ, playerPos.y);
            cameraPos = computeInitialCameraPos(
                new Vec3(pivotFocusX, smoothedGroundY, pivotFocusZ),
                yaw,
                pitch,
                altitude
            );
            mc.player.setDeltaMovement(0.0D, mc.player.getDeltaMovement().y, 0.0D);
            mc.mouseHandler.releaseMouse();
            lastMouseX = mc.mouseHandler.xpos();
            lastMouseY = mc.mouseHandler.ypos();
            mc.gui.setOverlayMessage(
                Component.literal("[RTS 모드] WASD:이동 | 휠:줌 | 중클릭:회전 | 좌드래그:구역(" + getActivePlacementMode().getDisplayName() + ") | 우클릭:관리"),
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

    private static void resetDragState()
    {
        isDragging = false;
        dragThresholdExceeded = false;
        shiftBoxSelectDrag = false;
        dragStartPos = null;
        dragEndPos = null;
    }

    private static double samplePivotGroundY(
        @NotNull final Minecraft mc,
        final double focusX,
        final double focusZ,
        final double fallbackY)
    {
        if (mc.level == null)
        {
            return fallbackY;
        }
        final int[] offsets = {0, -3, 3};
        final int[] samples = new int[5];
        int count = 0;
        final int minBuildY = mc.level.getMinBuildHeight();

        final int baseX = Mth.floor(focusX);
        final int baseZ = Mth.floor(focusZ);
        for (int i = 0; i < 5; i++)
        {
            final int ox = switch (i)
            {
                case 1 -> offsets[1];
                case 2 -> offsets[2];
                default -> 0;
            };
            final int oz = switch (i)
            {
                case 3 -> offsets[1];
                case 4 -> offsets[2];
                default -> 0;
            };
            final int sx = baseX + ox;
            final int sz = baseZ + oz;
            if (!mc.level.hasChunkAt(new BlockPos(sx, Mth.floor(fallbackY), sz)))
            {
                continue;
            }
            final int h = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz);
            if (h > minBuildY)
            {
                samples[count++] = h;
            }
        }
        if (count == 0)
        {
            return fallbackY;
        }
        Arrays.sort(samples, 0, count);
        return samples[count / 2];
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
            resetDragState();
            middleMouseDown = false;
            return;
        }

        while (RtsKeyMappings.TOGGLE_RTS_CAMERA.get().consumeClick())
        {
            toggleRtsMode(mc);
        }

        if (!active)
        {
            preserveHotbarTicks = 0;
            return;
        }

        if (preserveHotbarTicks > 0 && preserveHotbarSlot >= 0 && preserveHotbarSlot < 9)
        {
            mc.player.getInventory().selected = preserveHotbarSlot;
            preserveHotbarTicks--;
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

        // Prevent stuck drag or hover state when any GUI screen opens
        if (mc.screen != null)
        {
            resetDragState();
            middleMouseDown = false;
            return;
        }

        // Keep mouse cursor free for RTS picking when no GUI screen is open
        if (mc.mouseHandler.isMouseGrabbed())
        {
            mc.mouseHandler.releaseMouse();
        }

        handleKeyboardPan(mc);
        updateHoverAndDragTarget(mc);
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
        pivotFocusX += delta.x;
        pivotFocusZ += delta.z;

        final double fallbackY = mc.player != null ? mc.player.getY() : smoothedGroundY;
        final double targetGroundY = samplePivotGroundY(mc, pivotFocusX, pivotFocusZ, fallbackY);
        smoothedGroundY += (targetGroundY - smoothedGroundY) * 0.22D;

        cameraPos = computeInitialCameraPos(
            new Vec3(pivotFocusX, smoothedGroundY, pivotFocusZ),
            yaw,
            pitch,
            altitude
        );
    }

    private static void updateHoverAndDragTarget(@NotNull final Minecraft mc)
    {
        final BlockHitResult hit = RtsMousePicker.pickBlock(mc, 160.0D);
        if (hit != null && hit.getType() == HitResult.Type.BLOCK)
        {
            hoveredBlockPos = hit.getBlockPos();
            final IBuildingView buildingView = RtsMousePicker.pickBuildingView(mc, hoveredBlockPos);
            if (buildingView != null)
            {
                hoveredBuildingPos = buildingView.getPosition();
            }
            else if (RtsMousePicker.isHutBlock(mc, hoveredBlockPos))
            {
                hoveredBuildingPos = hoveredBlockPos;
            }
            else
            {
                hoveredBuildingPos = null;
            }

            if (isDragging)
            {
                dragEndPos = hoveredBlockPos;
                if (!dragThresholdExceeded
                    && isDragThresholdMet(dragStartScreenX, dragStartScreenY, mc.mouseHandler.xpos(), mc.mouseHandler.ypos()))
                {
                    dragThresholdExceeded = true;
                }
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
            if (middleMouseDown)
            {
                final double dx = mouseX - lastMouseX;
                final double dy = mouseY - lastMouseY;
                yaw = (yaw + (float) (dx * 0.35D)) % 360.0F;
                if (yaw < 0.0F)
                {
                    yaw += 360.0F;
                }
                pitch = clampPitch(pitch + (float) (dy * 0.25D));
                cameraPos = computeInitialCameraPos(
                    new Vec3(pivotFocusX, smoothedGroundY, pivotFocusZ),
                    yaw,
                    pitch,
                    altitude
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
        final Minecraft mc = Minecraft.getInstance();
        if (!active || mc.screen != null)
        {
            return;
        }

        final double scrollY = event.getScrollDelta();
        if (scrollY != 0.0D)
        {
            if (mc.player != null)
            {
                preserveHotbarSlot = mc.player.getInventory().selected;
                preserveHotbarTicks = 2;
            }
            altitude = clampAltitude(altitude - scrollY * 2.5D);
            cameraPos = computeInitialCameraPos(
                new Vec3(pivotFocusX, smoothedGroundY, pivotFocusZ),
                yaw,
                pitch,
                altitude
            );
            event.setCanceled(true);
        }
    }

    public static void onInteractionKeyMappingTriggered(@NotNull final InputEvent.InteractionKeyMappingTriggered event)
    {
        if (!active)
        {
            return;
        }
        event.setSwingHand(false);
        event.setCanceled(true);
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

        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE)
        {
            event.setCanceled(true);
            return;
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
        {
            if (action == GLFW.GLFW_PRESS)
            {
                dragStartScreenX = mc.mouseHandler.xpos();
                dragStartScreenY = mc.mouseHandler.ypos();

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
                    dragThresholdExceeded = false;
                    shiftBoxSelectDrag = shiftDown;
                    dragStartPos = blockHit.getBlockPos();
                    dragEndPos = blockHit.getBlockPos();
                }
            }
            else if (action == GLFW.GLFW_RELEASE && isDragging)
            {
                final BlockPos capturedStart = dragStartPos;
                BlockPos capturedEnd = dragEndPos;
                final boolean capturedShiftDrag = shiftBoxSelectDrag;
                final boolean didDrag = dragThresholdExceeded
                    || isDragThresholdMet(dragStartScreenX, dragStartScreenY, mc.mouseHandler.xpos(), mc.mouseHandler.ypos());

                try
                {
                    final BlockHitResult blockHit = RtsMousePicker.pickBlock(mc, 160.0D);
                    if (blockHit != null && blockHit.getType() == HitResult.Type.BLOCK)
                    {
                        capturedEnd = blockHit.getBlockPos();
                    }

                    if (didDrag && capturedStart != null && capturedEnd != null)
                    {
                        if (capturedShiftDrag)
                        {
                            selectCitizensInBox(mc, capturedStart, capturedEnd);
                        }
                        else
                        {
                            RtsInteractionBridge.submitZoneSelection(
                                capturedStart,
                                capturedEnd,
                                activeZoneType,
                                deleteMode,
                                getActivePlacementMode()
                            );
                        }
                    }
                    else
                    {
                        handleLeftClickWithoutDrag(mc, capturedEnd != null ? capturedEnd : capturedStart, capturedShiftDrag);
                    }
                }
                finally
                {
                    resetDragState();
                }
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

    private static void handleLeftClickWithoutDrag(
        @NotNull final Minecraft mc,
        @Nullable final BlockPos clickPos,
        final boolean shiftDown)
    {
        if (shiftDown)
        {
            final EntityHitResult entityHit = RtsMousePicker.pickEntity(mc, 160.0D);
            if (entityHit != null && entityHit.getEntity() instanceof AbstractEntityCitizen citizen)
            {
                final int id = citizen.getId();
                if (!selectedCitizenEntityIds.remove(id))
                {
                    selectedCitizenEntityIds.add(id);
                }
                mc.gui.setOverlayMessage(
                    Component.literal("[RTS] 선택된 시민: " + selectedCitizenEntityIds.size() + "명"),
                    false
                );
            }
            return;
        }

        if (!selectedCitizenEntityIds.isEmpty())
        {
            selectedCitizenEntityIds.clear();
            mc.gui.setOverlayMessage(Component.literal("[RTS] 시민 선택 해제됨"), false);
            return;
        }

        if (clickPos == null)
        {
            return;
        }

        final IBuildingView buildingView = RtsMousePicker.pickBuildingView(mc, clickPos);
        if (buildingView != null)
        {
            final String buildingName = Component.translatable(buildingView.getBuildingDisplayName()).getString();
            mc.gui.setOverlayMessage(
                Component.literal("[RTS] 건물: " + buildingName + " (Lv." + buildingView.getBuildingLevel() + ") | 우클릭: 관리창, Shift+우클릭: 건설/업그레이드"),
                false
            );
            return;
        }

        if (RtsMousePicker.isHutBlock(mc, clickPos))
        {
            mc.gui.setOverlayMessage(
                Component.literal("[RTS] 시청/오두막 블록 - 우클릭으로 정착지 설립 및 관리 창 열기"),
                false
            );
            return;
        }

        final RtsZoneData zone = RtsInteractionBridge.findZoneContaining(clickPos);
        if (zone != null)
        {
            mc.gui.setOverlayMessage(
                Component.literal("[RTS] 구역: " + zone.type().getDisplayName() + " | 우클릭: 연결 건물 / [V] 철거"),
                false
            );
            return;
        }

        mc.gui.setOverlayMessage(
            Component.literal("[RTS] 드래그(끌기)하여 " + activeZoneType.getDisplayName() + " 구역을 지정하세요."),
            false
        );
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
