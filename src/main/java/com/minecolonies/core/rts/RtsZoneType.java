package com.minecolonies.core.rts;

import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.jobs.ModJobs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Cities-like RTS Zone categories integrated with MineColonies 1.20.1 huts and jobs.
 * Colors follow the specification:
 * RESIDENTIAL = Green, LOGGING = Brown, MINING = Gray, FARMING = Yellow, STORAGE = Blue, DEFENSE = Red.
 */
public enum RtsZoneType implements StringRepresentable
{
    RESIDENTIAL(0, "residential", "주거 (Residential)", 0.20F, 0.85F, 0.25F, "residence", null),
    LOGGING(1, "logging", "벌목 (Logging)", 0.58F, 0.38F, 0.18F, "lumberjack", ModJobs.LUMBERJACK_ID),
    MINING(2, "mining", "채광 (Mining)", 0.55F, 0.58F, 0.62F, "miner", ModJobs.MINER_ID),
    FARMING(3, "farming", "농업 (Farming)", 0.95F, 0.82F, 0.18F, "farmer", ModJobs.FARMER_ID),
    STORAGE(4, "storage", "창고 (Storage)", 0.22F, 0.52F, 0.95F, "warehouse", ModJobs.DELIVERY_ID),
    DEFENSE(5, "defense", "방어 (Defense)", 0.92F, 0.22F, 0.22F, "guardtower", ModJobs.KNIGHT_ID);

    private final int id;
    private final String serializedName;
    private final String displayName;
    private final float red;
    private final float green;
    private final float blue;
    private final String schematicName;
    @Nullable
    private final ResourceLocation defaultJobId;

    RtsZoneType(
        final int id,
        final String serializedName,
        final String displayName,
        final float red,
        final float green,
        final float blue,
        final String schematicName,
        @Nullable final ResourceLocation defaultJobId)
    {
        this.id = id;
        this.serializedName = serializedName;
        this.displayName = displayName;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.schematicName = schematicName;
        this.defaultJobId = defaultJobId;
    }

    public int id()
    {
        return id;
    }

    public String displayName()
    {
        return displayName;
    }

    public String getDisplayName()
    {
        return displayName;
    }

    public float red()
    {
        return red;
    }

    public float getRed()
    {
        return red;
    }

    public float green()
    {
        return green;
    }

    public float getGreen()
    {
        return green;
    }

    public float blue()
    {
        return blue;
    }

    public float getBlue()
    {
        return blue;
    }

    public String getSchematicName()
    {
        return schematicName;
    }

    public String getDefaultSchematicName()
    {
        return schematicName;
    }

    @Nullable
    public ResourceLocation getDefaultJobId()
    {
        return defaultJobId;
    }

    @Nullable
    public AbstractBlockHut<?> getDefaultHutBlock()
    {
        return switch (this)
        {
            case RESIDENTIAL -> ModBlocks.blockHutHome;
            case LOGGING -> ModBlocks.blockHutLumberjack;
            case MINING -> ModBlocks.blockHutMiner;
            case FARMING -> ModBlocks.blockHutFarmer;
            case STORAGE -> ModBlocks.blockHutWareHouse;
            case DEFENSE -> ModBlocks.blockHutGuardTower;
        };
    }

    public int argbColor(final int alpha)
    {
        final int a = Mth.clamp(alpha, 0, 255);
        final int r = Mth.clamp(Math.round(this.red * 255.0F), 0, 255);
        final int g = Mth.clamp(Math.round(this.green * 255.0F), 0, 255);
        final int b = Mth.clamp(Math.round(this.blue * 255.0F), 0, 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public RtsZoneType next()
    {
        final RtsZoneType[] all = values();
        return all[(this.ordinal() + 1) % all.length];
    }

    public static RtsZoneType byId(final int id)
    {
        final RtsZoneType[] all = values();
        if (id < 0 || id >= all.length)
        {
            return RESIDENTIAL;
        }
        return all[id];
    }

    @NotNull
    @Override
    public String getSerializedName()
    {
        return serializedName;
    }
}
