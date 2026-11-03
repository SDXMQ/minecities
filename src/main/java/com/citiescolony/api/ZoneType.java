package com.citiescolony.api;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;

import java.util.function.IntFunction;

/**
 * Defines the zone categories available in Minecraft Cities Colony.
 * Colors match the specification:
 * RESIDENTIAL = Green, LOGGING = Brown, MINING = Gray, FARMING = Yellow, STORAGE = Blue, DEFENSE = Red.
 */
public enum ZoneType implements StringRepresentable {
    RESIDENTIAL(0, "residential", " 주거 (Residential)", 0.20f, 0.85f, 0.25f),
    LOGGING(1, "logging", "벌목 (Logging)", 0.58f, 0.38f, 0.18f),
    MINING(2, "mining", "채광 (Mining)", 0.55f, 0.58f, 0.62f),
    FARMING(3, "farming", "농업 (Farming)", 0.95f, 0.82f, 0.18f),
    STORAGE(4, "storage", "창고 (Storage)", 0.22f, 0.52f, 0.95f),
    DEFENSE(5, "defense", "방어 (Defense)", 0.92f, 0.22f, 0.22f);

    public static final Codec<ZoneType> CODEC = StringRepresentable.fromEnum(ZoneType::values);
    private static final IntFunction<ZoneType> BY_ID = ByIdMap.continuous(ZoneType::id, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
    public static final StreamCodec<ByteBuf, ZoneType> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, ZoneType::id);

    private final int id;
    private final String serializedName;
    private final String displayName;
    private final float red;
    private final float green;
    private final float blue;

    ZoneType(int id, String serializedName, String displayName, float red, float green, float blue) {
        this.id = id;
        this.serializedName = serializedName;
        this.displayName = displayName.trim();
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    public int id() {
        return this.id;
    }

    public String displayName() {
        return this.displayName;
    }

    public float red() {
        return this.red;
    }

    public float green() {
        return this.green;
    }

    public float blue() {
        return this.blue;
    }

    public int argbColor(int alpha) {
        int a = Math.clamp(alpha, 0, 255);
        int r = Math.clamp(Math.round(this.red * 255.0f), 0, 255);
        int g = Math.clamp(Math.round(this.green * 255.0f), 0, 255);
        int b = Math.clamp(Math.round(this.blue * 255.0f), 0, 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public ZoneType next() {
        ZoneType[] all = values();
        return all[(this.ordinal() + 1) % all.length];
    }

    @Override
    public String getSerializedName() {
        return this.serializedName;
    }
}
