package com.minecolonies.core.client.rts;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.util.Lazy;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

/**
 * Key mappings for the Cities-like RTS camera and colony control mode.
 */
public final class RtsKeyMappings
{
    public static final String CATEGORY = "key.minecolonies.categories.rts";

    public static final Lazy<KeyMapping> TOGGLE_RTS_CAMERA = Lazy.of(() -> new KeyMapping(
        "key.minecolonies.rts.toggle_camera",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_F4,
        CATEGORY
    ));

    public static final Lazy<KeyMapping> CYCLE_ZONE_TYPE = Lazy.of(() -> new KeyMapping(
        "key.minecolonies.rts.cycle_zone",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_Z,
        CATEGORY
    ));

    public static final Lazy<KeyMapping> TOGGLE_DELETE_MODE = Lazy.of(() -> new KeyMapping(
        "key.minecolonies.rts.toggle_delete",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_X,
        CATEGORY
    ));

    public static final Lazy<KeyMapping> CYCLE_CITIZEN_JOB = Lazy.of(() -> new KeyMapping(
        "key.minecolonies.rts.cycle_job",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_J,
        CATEGORY
    ));

    public static final Lazy<KeyMapping> TOGGLE_PLACEMENT_MODE = Lazy.of(() -> new KeyMapping(
        "key.minecolonies.rts.toggle_placement_mode",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_B,
        CATEGORY
    ));

    private RtsKeyMappings()
    {
    }

    public static void register(@NotNull final RegisterKeyMappingsEvent event)
    {
        event.register(TOGGLE_RTS_CAMERA.get());
        event.register(CYCLE_ZONE_TYPE.get());
        event.register(TOGGLE_DELETE_MODE.get());
        event.register(CYCLE_CITIZEN_JOB.get());
        event.register(TOGGLE_PLACEMENT_MODE.get());
    }
}
