package com.citiescolony.common.entity;

import com.citiescolony.api.ColonyIds;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Cities: Skylines style road-aware pathfinding evaluator.
 * Lowers the pathfinding cost malus for Dirt Path, Stone Bricks, and Paved Road blocks
 * so colony citizens preferentially route along player-constructed road networks.
 */
public class RoadAwareNodeEvaluator extends WalkNodeEvaluator {

    public static final float ROAD_COST_BONUS_FACTOR = 0.25F;
    public static final float OFF_ROAD_ADDITIONAL_MALUS = 0.75F;

    @Override
    public PathType getPathTypeOfMob(PathfindingContext context, int x, int y, int z, Mob mob) {
        PathType baseType = super.getPathTypeOfMob(context, x, y, z, mob);
        if (baseType == PathType.WALKABLE) {
            BlockGetter level = context.level();
            if (isRoadSurfaceAt(level, x, y, z)) {
                return PathType.WALKABLE;
            }
        }
        return baseType;
    }

    @Override
    protected Node findAcceptedNode(int x, int y, int z, int verticalDeltaLimit, double nodeFloorLevel, net.minecraft.core.Direction direction, PathType pathType) {
        Node node = super.findAcceptedNode(x, y, z, verticalDeltaLimit, nodeFloorLevel, direction, pathType);
        if (node != null && !node.closed && node.type == PathType.WALKABLE && this.currentContext != null) {
            BlockGetter level = this.currentContext.level();
            if (isRoadSurfaceAt(level, node.x, node.y, node.z)) {
                node.costMalus = Math.max(0.0F, node.costMalus * ROAD_COST_BONUS_FACTOR);
            } else {
                node.costMalus += OFF_ROAD_ADDITIONAL_MALUS;
            }
        }
        return node;
    }

    public static boolean isRoadSurfaceAt(BlockGetter level, int x, int y, int z) {
        if (level == null) {
            return false;
        }
        BlockPos feetPos = new BlockPos(x, y, z);
        if (isRoadBlock(level.getBlockState(feetPos))) {
            return true;
        }
        BlockPos belowPos = feetPos.below();
        return isRoadBlock(level.getBlockState(belowPos));
    }

    public static boolean isEntityOnRoad(Level level, BlockPos entityBlockPos) {
        if (level == null || entityBlockPos == null) {
            return false;
        }
        if (isRoadBlock(level.getBlockState(entityBlockPos))) {
            return true;
        }
        return isRoadBlock(level.getBlockState(entityBlockPos.below()));
    }

    public static boolean isRoadBlock(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        if (state.is(Blocks.DIRT_PATH)
                || state.is(BlockTags.STONE_BRICKS)
                || state.is(Blocks.STONE_BRICK_SLAB)
                || state.is(Blocks.STONE_BRICK_STAIRS)
                || state.is(Blocks.SMOOTH_STONE)
                || state.is(Blocks.SMOOTH_STONE_SLAB)
                || state.is(Blocks.POLISHED_ANDESITE)
                || state.is(Blocks.COBBLESTONE)) {
            return true;
        }
        return state.getBlockHolder().is(ColonyIds.BlockIds.PAVED_ROAD);
    }
}
