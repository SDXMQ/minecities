package com.citiescolony.api;

import net.minecraft.core.registries.Registries;
import net.minecraft.references.BlockItemId;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Minecraft 26.2 compliant registry identifier separation.
 * Strictly separates BlockIds, BlockItemIds, and ItemIds without relying on raw instances or valueLookupBuilder.
 */
public final class ColonyIds {
    public static final String MOD_ID = "citiescolony";

    private ColonyIds() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    /**
     * Block-only ResourceKeys (follows vanilla 26.2 net.minecraft.references.BlockIds pattern).
     */
    public static final class BlockIds {
        public static final ResourceKey<Block> TOWN_HALL = blockKey("town_hall");
        public static final ResourceKey<Block> PAVED_ROAD = blockKey("paved_road");
        public static final ResourceKey<Block> GUARD_OUTPOST = blockKey("guard_outpost");

        private BlockIds() {
        }

        public static ResourceKey<Block> blockKey(String name) {
            return ResourceKey.create(Registries.BLOCK, id(name));
        }
    }

    /**
     * Paired Block + Item ResourceKeys (follows vanilla 26.2 net.minecraft.references.BlockItemIds pattern).
     */
    public static final class BlockItemIds {
        public static final BlockItemId TOWN_HALL = blockItemKey("town_hall");
        public static final BlockItemId PAVED_ROAD = blockItemKey("paved_road");
        public static final BlockItemId GUARD_OUTPOST = blockItemKey("guard_outpost");

        private BlockItemIds() {
        }

        public static BlockItemId blockItemKey(String name) {
            Identifier identifier = id(name);
            return new BlockItemId(
                    ResourceKey.create(Registries.BLOCK, identifier),
                    ResourceKey.create(Registries.ITEM, identifier)
            );
        }
    }

    /**
     * Standalone Item ResourceKeys (follows vanilla 26.2 net.minecraft.references.ItemIds pattern).
     */
    public static final class ItemIds {
        public static final ResourceKey<Item> COLONY_SCEPTER = itemKey("colony_scepter");
        public static final ResourceKey<Item> CITIZEN_SPAWN_CHARTER = itemKey("citizen_spawn_charter");

        private ItemIds() {
        }

        public static ResourceKey<Item> itemKey(String name) {
            return ResourceKey.create(Registries.ITEM, id(name));
        }
    }

    /**
     * Entity ResourceKeys.
     */
    public static final class EntityIds {
        public static final ResourceKey<EntityType<?>> COLONY_CITIZEN =
                ResourceKey.create(Registries.ENTITY_TYPE, id("colony_citizen"));

        private EntityIds() {
        }
    }
}
