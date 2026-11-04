package com.citiescolony.client;

import com.citiescolony.api.JobType;
import com.citiescolony.api.ZoneType;
import com.citiescolony.common.entity.ColonyCitizenEntity;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * 3rd-person RTS Quarter-View Camera Controller for Minecraft 26.2.
 * - Toggled with F4: detaches viewpoint 15~35 blocks above ground at 45~60 deg pitch.
 * - WASD moves camera horizontally across the city plane.
 * - Mouse scroll zooms altitude between 15 and 35 blocks.
 * - Middle mouse button drag rotates yaw and pitch.
 * - Left-click drag selects a 3D zone box; right-click issues commands to selected citizens.
 */
public final class RtsCameraController {

    public static final double MIN_ALTITUDE = 15.0D;
    public static final double MAX_ALTITUDE = 35.0D;
    public static final float MIN_PITCH = 45.0F;
    public static final float MAX_PITCH = 60.0F;

    private static final double PAN_SPEED_BLOCKS_PER_TICK = 0.85D;
    private static final double ZOOM_STEP_BLOCKS = 2.0D;
    private static final float ROTATION_SENSITIVITY = 0.32F;

    public interface ZoneActionDispatcher {
        void dispatchZoneAction(BlockPos startPos, BlockPos endPos, ZoneType zoneType, boolean deleteMode);
    }

    public interface CitizenCommandDispatcher {
        void dispatchCitizenCommand(List<Integer> citizenEntityIds, BlockPos targetPos);
    }

    private static boolean active = false;
    private static Vec3 focusPosition = Vec3.ZERO;
    private static double altitude = 24.0D;
    private static float yaw = 45.0F;
    private static float pitch = 52.5F;

    private static boolean middleMouseDragging = false;
    private static double lastMouseX = 0.0D;
    private static double lastMouseY = 0.0D;

    private static boolean leftMouseDraggingZone = false;
    private static BlockPos dragStartPos = null;
    private static BlockPos dragCurrentPos = null;

    private static ZoneType selectedZoneType = ZoneType.RESIDENTIAL;
    private static boolean deleteZoneMode = false;
    private static JobType selectedJobToAssign = JobType.LUMBERJACK;

    private static final Set<Integer> selectedCitizenIds = new LinkedHashSet<>();

    private static final Matrix4f currentViewMatrix = new Matrix4f();
    private static final Matrix4f currentProjectionMatrix = new Matrix4f();

    private static ZoneActionDispatcher zoneActionDispatcher;
    private static CitizenCommandDispatcher citizenCommandDispatcher;
    private static BiConsumer<Integer, JobType> jobAssignDispatcher;

    private RtsCameraController() {
    }

    public static void setNetworkDispatchers(
            ZoneActionDispatcher zoneDispatcher,
            CitizenCommandDispatcher commandDispatcher,
            BiConsumer<Integer, JobType> jobDispatcher
    ) {
        zoneActionDispatcher = zoneDispatcher;
        citizenCommandDispatcher = commandDispatcher;
        jobAssignDispatcher = jobDispatcher;
    }

    public static boolean isActive() {
        return active;
    }

    public static void toggleRtsMode() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            active = false;
            return;
        }
        active = !active;
        if (active) {
            Vec3 playerPos = mc.player.position();
            focusPosition = new Vec3(playerPos.x, playerPos.y, playerPos.z);
            altitude = Mth.clamp(altitude, MIN_ALTITUDE, MAX_ALTITUDE);
            pitch = Mth.clamp(pitch, MIN_PITCH, MAX_PITCH);
            rebuildViewMatrixFromAngles();
            mc.mouseHandler.releaseMouse();
            if (mc.gui != null && mc.gui.hud != null) {
                mc.gui.hud.setOverlayMessage(
                        Component.literal("[RTS 모드 활성화] WASD: 이동 | 휠: 줌 | 휠드래그: 회전 | 좌클릭드래그: 구역지정(Shift+드래그: 주민 다중선택) | 우클릭: 주민명령"),
                        false
                );
            }
        } else {
            leftMouseDraggingZone = false;
            dragStartPos = null;
            dragCurrentPos = null;
            middleMouseDragging = false;
            if (mc.gui != null && mc.gui.screen() == null) {
                mc.mouseHandler.grabMouse();
            }
        }
    }

    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            active = false;
            return;
        }

        while (RtsKeyMappings.TOGGLE_RTS_CAMERA.consumeClick()) {
            toggleRtsMode();
        }

        if (!active) {
            return;
        }

        while (RtsKeyMappings.CYCLE_ZONE_TYPE.consumeClick()) {
            selectedZoneType = selectedZoneType.next();
            deleteZoneMode = false;
        }

        while (RtsKeyMappings.TOGGLE_DELETE_ZONE_MODE.consumeClick()) {
            deleteZoneMode = !deleteZoneMode;
        }

        while (RtsKeyMappings.CYCLE_CITIZEN_JOB.consumeClick()) {
            cycleSelectedCitizensJob();
        }

        if (mc.gui != null && mc.gui.screen() == null && mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
        }

        handleKeyboardPanning(mc);
        updateCursorTrackingAndRotation(mc);
    }

    private static void handleKeyboardPanning(Minecraft mc) {
        if (mc.gui != null && mc.gui.screen() != null) {
            return;
        }
        long window = mc.getWindow().getWindow();
        double forward = 0.0D;
        double strafe = 0.0D;

        if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_W)) {
            forward += 1.0D;
        }
        if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_S)) {
            forward -= 1.0D;
        }
        if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_D)) {
            strafe += 1.0D;
        }
        if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_A)) {
            strafe -= 1.0D;
        }

        // Suppress vanilla movement keys so the player body does not walk while panning the RTS camera
        if (mc.options != null) {
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keySprint.setDown(false);
            mc.options.keyShift.setDown(false);
        }
        if (mc.player != null) {
            Vec3 vel = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(0.0D, vel.y, 0.0D);
        }

        if (forward != 0.0D || strafe != 0.0D) {
            double length = Math.sqrt(forward * forward + strafe * strafe);
            forward /= length;
            strafe /= length;

            double yawRad = Math.toRadians(yaw);
            double sin = Math.sin(yawRad);
            double cos = Math.cos(yawRad);

            double speedScale = PAN_SPEED_BLOCKS_PER_TICK * (altitude / 20.0D);
            double dx = (-sin * forward - cos * strafe) * speedScale;
            double dz = (cos * forward - sin * strafe) * speedScale;

            focusPosition = focusPosition.add(dx, 0.0D, dz);
        }
    }

    /**
     * Blocks vanilla player movement input while RTS camera mode is active so WASD only moves the RTS camera.
     */
    public static void onMovementInputUpdate(MovementInputUpdateEvent event) {
        if (!active) {
            return;
        }
        var input = event.getInput();
        input.moveVector = net.minecraft.world.phys.Vec2.ZERO;
        if (event.getEntity() != null) {
            Vec3 vel = event.getEntity().getDeltaMovement();
            event.getEntity().setDeltaMovement(0.0D, vel.y, 0.0D);
        }
    }

    private static void updateCursorTrackingAndRotation(Minecraft mc) {
        double mouseX = mc.mouseHandler.xpos();
        double mouseY = mc.mouseHandler.ypos();

        if (middleMouseDragging) {
            double deltaX = mouseX - lastMouseX;
            double deltaY = mouseY - lastMouseY;
            yaw = Mth.wrapDegrees(yaw + (float) (deltaX * ROTATION_SENSITIVITY));
            pitch = Mth.clamp(pitch + (float) (deltaY * ROTATION_SENSITIVITY), MIN_PITCH, MAX_PITCH);
        }

        lastMouseX = mouseX;
        lastMouseY = mouseY;

        if (leftMouseDraggingZone) {
            MouseRaycaster.RaycastResult hit = MouseRaycaster.raycastFromMouse(mc, mouseX, mouseY);
            hit.hitBlockPos().ifPresent(pos -> dragCurrentPos = pos);
        }
    }

    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!active) {
            return;
        }
        Camera camera = event.getCamera();
        event.setYaw(yaw);
        event.setPitch(pitch);
        event.setRoll(0.0F);

        Vec3 camPos = getCameraWorldPosition();
        camera.setPosition(camPos.x, camPos.y, camPos.z);

        rebuildViewMatrixFromAngles();
    }

    private static void rebuildViewMatrixFromAngles() {
        Quaternionf rotation = new Quaternionf()
                .rotationX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw + 180.0F));
        currentViewMatrix.identity().rotate(rotation);
    }

    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!active) {
            return;
        }
        double scrollY = event.getScrollDeltaY();
        if (scrollY != 0.0D) {
            altitude = Mth.clamp(altitude - (scrollY * ZOOM_STEP_BLOCKS), MIN_ALTITUDE, MAX_ALTITUDE);
            event.setCanceled(true);
        }
    }

    public static void onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        if (!active) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null && mc.gui.screen() != null) {
            return;
        }

        int button = event.getButton();
        int action = event.getAction();
        double mouseX = mc.mouseHandler.xpos();
        double mouseY = mc.mouseHandler.ypos();

        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            middleMouseDragging = (action == GLFW.GLFW_PRESS);
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            event.setCanceled(true);
            return;
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (action == GLFW.GLFW_PRESS) {
                MouseRaycaster.RaycastResult hit = MouseRaycaster.raycastFromMouse(mc, mouseX, mouseY);
                if (hit.hitCitizen().isPresent()) {
                    ColonyCitizenEntity citizen = hit.hitCitizen().get();
                    boolean additive = InputConstants.isKeyDown(mc.getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT);
                    if (!additive) {
                        selectedCitizenIds.clear();
                    }
                    selectedCitizenIds.add(citizen.getId());
                } else if (hit.hitBlockPos().isPresent()) {
                    leftMouseDraggingZone = true;
                    dragStartPos = hit.hitBlockPos().get();
                    dragCurrentPos = dragStartPos;
                }
            } else if (action == GLFW.GLFW_RELEASE && leftMouseDraggingZone) {
                leftMouseDraggingZone = false;
                if (dragStartPos != null && dragCurrentPos != null) {
                    BlockPos elevatedEnd = dragCurrentPos.above(4);
                    boolean shiftSelectOnly = InputConstants.isKeyDown(mc.getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT);
                    if (shiftSelectOnly) {
                        selectCitizensInBox(mc.level, dragStartPos, elevatedEnd, true);
                    } else {
                        selectCitizensInBox(mc.level, dragStartPos, elevatedEnd, false);
                        if (zoneActionDispatcher != null) {
                            zoneActionDispatcher.dispatchZoneAction(dragStartPos, elevatedEnd, selectedZoneType, deleteZoneMode);
                        }
                    }
                }
                dragStartPos = null;
                dragCurrentPos = null;
            }
            event.setCanceled(true);
            return;
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && action == GLFW.GLFW_PRESS) {
            MouseRaycaster.RaycastResult hit = MouseRaycaster.raycastFromMouse(mc, mouseX, mouseY);
            if (hit.hitBlockPos().isPresent() && !selectedCitizenIds.isEmpty() && citizenCommandDispatcher != null) {
                citizenCommandDispatcher.dispatchCitizenCommand(
                        new ArrayList<>(selectedCitizenIds),
                        hit.hitBlockPos().get()
                );
            }
            event.setCanceled(true);
        }
    }

    public static int selectCitizensInBox(
            net.minecraft.world.level.Level level,
            BlockPos firstCorner,
            BlockPos secondCorner,
            boolean additive
    ) {
        if (level == null || firstCorner == null || secondCorner == null) {
            return 0;
        }
        int minX = Math.min(firstCorner.getX(), secondCorner.getX()) - 1;
        int minY = Math.min(firstCorner.getY(), secondCorner.getY()) - 2;
        int minZ = Math.min(firstCorner.getZ(), secondCorner.getZ()) - 1;
        int maxX = Math.max(firstCorner.getX(), secondCorner.getX()) + 2;
        int maxY = Math.max(firstCorner.getY(), secondCorner.getY()) + 4;
        int maxZ = Math.max(firstCorner.getZ(), secondCorner.getZ()) + 2;

        net.minecraft.world.phys.AABB searchBox = new net.minecraft.world.phys.AABB(minX, minY, minZ, maxX, maxY, maxZ);
        List<ColonyCitizenEntity> found = level.getEntitiesOfClass(
                ColonyCitizenEntity.class,
                searchBox,
                ColonyCitizenEntity::isAlive
        );
        if (!found.isEmpty() && !additive) {
            selectedCitizenIds.clear();
        }
        for (ColonyCitizenEntity citizen : found) {
            selectedCitizenIds.add(citizen.getId());
        }
        return found.size();
    }

    private static void cycleSelectedCitizensJob() {
        JobType[] jobs = JobType.values();
        selectedJobToAssign = jobs[(selectedJobToAssign.ordinal() + 1) % jobs.length];
        if (jobAssignDispatcher != null) {
            for (int entityId : selectedCitizenIds) {
                jobAssignDispatcher.accept(entityId, selectedJobToAssign);
            }
        }
    }

    public static Vec3 getCameraWorldPosition() {
        double pitchRad = Math.toRadians(pitch);
        double yawRad = Math.toRadians(yaw);
        double horizontalOffset = altitude / Math.tan(pitchRad);
        double camX = focusPosition.x + Math.sin(yawRad) * horizontalOffset;
        double camY = focusPosition.y + altitude;
        double camZ = focusPosition.z - Math.cos(yawRad) * horizontalOffset;
        return new Vec3(camX, camY, camZ);
    }

    public static Vec3 getFocusPosition() {
        return focusPosition;
    }

    public static double getAltitude() {
        return altitude;
    }

    public static float getYaw() {
        return yaw;
    }

    public static float getPitch() {
        return pitch;
    }

    public static ZoneType getSelectedZoneType() {
        return selectedZoneType;
    }

    public static void setSelectedZoneType(ZoneType type) {
        if (type != null) {
            selectedZoneType = type;
        }
    }

    public static boolean isDeleteZoneMode() {
        return deleteZoneMode;
    }

    public static Optional<BlockPos> getDragStartPos() {
        return Optional.ofNullable(dragStartPos);
    }

    public static Optional<BlockPos> getDragCurrentPos() {
        return Optional.ofNullable(dragCurrentPos);
    }

    public static Set<Integer> getSelectedCitizenIds() {
        return Collections.unmodifiableSet(selectedCitizenIds);
    }

    public static void updateMatrices(Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
        if (modelViewMatrix != null) {
            currentViewMatrix.set(modelViewMatrix);
        }
        if (projectionMatrix != null) {
            currentProjectionMatrix.set(projectionMatrix);
        }
    }

    public static Matrix4f getViewMatrix() {
        return new Matrix4f(currentViewMatrix);
    }

    public static Matrix4f getProjectionMatrix() {
        return new Matrix4f(currentProjectionMatrix);
    }
}
