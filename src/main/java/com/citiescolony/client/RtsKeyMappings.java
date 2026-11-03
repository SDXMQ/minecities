package com.citiescolony.client;

import com.citiescolony.api.ColonyIds;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Client-only keybindings for controlling the 3rd-person RTS camera,
 * zone designation modes, and citizen job cycling.
 */
public final class RtsKeyMappings {

    public static final String CATEGORY_RTS = "key.categories." + ColonyIds.MOD_ID + ".rts";

    public static final KeyMapping TOGGLE_RTS_CAMERA = new KeyMapping(
            "key." + ColonyIds.MOD_ID + ".toggle_rts_camera",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F4,
            CATEGORY_RTS
    );

    public static final KeyMapping CYCLE_ZONE_TYPE = new KeyMapping(
            "key." + ColonyIds.MOD_ID + ".cycle_zone_type",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            CATEGORY_RTS
    );

    public static final KeyMapping TOGGLE_DELETE_ZONE_MODE = new KeyMapping(
            "key." + ColonyIds.MOD_ID + ".toggle_delete_zone_mode",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X,
            CATEGORY_RTS
    );

    public static final KeyMapping CYCLE_CITIZEN_JOB = new KeyMapping(
            "key." + ColonyIds.MOD_ID + ".cycle_citizen_job",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            CATEGORY_RTS
    );

    private RtsKeyMappings() {
    }

    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_RTS_CAMERA);
        event.register(CYCLE_ZONE_TYPE);
        event.register(TOGGLE_DELETE_ZONE_MODE);
        event.register(CYCLE_CITIZEN_JOB);
    }
}
