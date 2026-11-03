package com.citiescolony.api;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable data carrier representing a player-designated city zone,
 * equipped with both DFU Codec and network StreamCodec for Minecraft 26.2.
 */
public record ZoneData(
        UUID id,
        ZoneType type,
        BoundingBox bounds,
        List<UUID> assignedCitizens
) implements IZoneData {

    public static final StreamCodec<FriendlyByteBuf, BoundingBox> BOUNDING_BOX_STREAM_CODEC = new StreamCodec<>() {
        @Override
        public BoundingBox decode(FriendlyByteBuf buffer) {
            int minX = buffer.readVarInt();
            int minY = buffer.readVarInt();
            int minZ = buffer.readVarInt();
            int maxX = buffer.readVarInt();
            int maxY = buffer.readVarInt();
            int maxZ = buffer.readVarInt();
            return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        }

        @Override
        public void encode(FriendlyByteBuf buffer, BoundingBox box) {
            buffer.writeVarInt(box.minX());
            buffer.writeVarInt(box.minY());
            buffer.writeVarInt(box.minZ());
            buffer.writeVarInt(box.maxX());
            buffer.writeVarInt(box.maxY());
            buffer.writeVarInt(box.maxZ());
        }
    };

    public static final Codec<ZoneData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(ZoneData::id),
            ZoneType.CODEC.fieldOf("type").forGetter(ZoneData::type),
            BoundingBox.CODEC.fieldOf("bounds").forGetter(ZoneData::bounds),
            UUIDUtil.CODEC.listOf().optionalFieldOf("assigned_citizens", List.of()).forGetter(ZoneData::assignedCitizens)
    ).apply(instance, ZoneData::new));

    public static final StreamCodec<FriendlyByteBuf, ZoneData> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, ZoneData::id,
            ZoneType.STREAM_CODEC, ZoneData::type,
            BOUNDING_BOX_STREAM_CODEC, ZoneData::bounds,
            UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list()), ZoneData::assignedCitizens,
            ZoneData::new
    );

    public ZoneData {
        Objects.requireNonNull(id, "Zone id must not be null");
        Objects.requireNonNull(type, "Zone type must not be null");
        Objects.requireNonNull(bounds, "Zone bounds must not be null");
        assignedCitizens = assignedCitizens == null ? List.of() : List.copyOf(assignedCitizens);
    }

    public static ZoneData fromCorners(ZoneType type, BlockPos firstCorner, BlockPos secondCorner) {
        Objects.requireNonNull(firstCorner, "firstCorner must not be null");
        Objects.requireNonNull(secondCorner, "secondCorner must not be null");
        BoundingBox box = BoundingBox.fromCorners(firstCorner, secondCorner);
        return new ZoneData(UUID.randomUUID(), type, box, List.of());
    }

    public ZoneData withAssignedCitizen(UUID citizenId) {
        Objects.requireNonNull(citizenId, "citizenId must not be null");
        if (this.assignedCitizens.contains(citizenId)) {
            return this;
        }
        List<UUID> updated = new ArrayList<>(this.assignedCitizens.size() + 1);
        updated.addAll(this.assignedCitizens);
        updated.add(citizenId);
        return new ZoneData(this.id, this.type, this.bounds, updated);
    }

    public ZoneData withoutAssignedCitizen(UUID citizenId) {
        if (citizenId == null || !this.assignedCitizens.contains(citizenId)) {
            return this;
        }
        List<UUID> updated = this.assignedCitizens.stream()
                .filter(existing -> !existing.equals(citizenId))
                .toList();
        return new ZoneData(this.id, this.type, this.bounds, updated);
    }

    public boolean intersects(BoundingBox other) {
        return other != null && this.bounds.intersects(other);
    }
}
