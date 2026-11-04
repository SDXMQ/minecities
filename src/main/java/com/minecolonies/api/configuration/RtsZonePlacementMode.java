package com.minecolonies.api.configuration;

/**
 * Defines how dragging a Cities-like zone places or previews MineColonies buildings in RTS mode.
 * <ul>
 *     <li>{@link #MODE_A_AUTO_PLACE}: (Default) Automatically places the recommended MineColonies hut/field at the zone center and queues a build work order. Detailed adjustment via Mode B remains accessible by right-clicking.</li>
 *     <li>{@link #MODE_B_BLUEPRINT_PREVIEW}: Opens the Structurize/MineColonies Build Tool preview window at the zone center so the player can fine-tune style and rotation before placement.</li>
 * </ul>
 */
public enum RtsZonePlacementMode
{
    MODE_A_AUTO_PLACE("방식A:자동배치"),
    MODE_B_BLUEPRINT_PREVIEW("방식B:상세미리보기");

    private final String displayName;

    RtsZonePlacementMode(final String displayName)
    {
        this.displayName = displayName;
    }

    public String getDisplayName()
    {
        return displayName;
    }

    public RtsZonePlacementMode next()
    {
        final RtsZonePlacementMode[] all = values();
        return all[(this.ordinal() + 1) % all.length];
    }
}
