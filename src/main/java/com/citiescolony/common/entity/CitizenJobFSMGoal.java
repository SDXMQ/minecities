package com.citiescolony.common.entity;

import com.citiescolony.api.ColonyTask;
import com.citiescolony.api.JobType;
import com.citiescolony.api.ZoneData;
import com.citiescolony.api.ZoneType;
import com.citiescolony.common.city.CityZoneManager;
import com.citiescolony.common.city.StorageLogisticsHelper;
import com.citiescolony.common.city.ZoneTaskGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Single unified finite state machine (FSM) AI goal for ColonyCitizenEntity to minimize server tick overhead.
 * States: IDLE, MOVE_TO_TASK, WORKING, MOVE_TO_STORAGE, DEPOSITING, COMBAT.
 */
public class CitizenJobFSMGoal extends Goal {

    public enum FsmState {
        IDLE,
        MOVE_TO_TASK,
        WORKING,
        MOVE_TO_STORAGE,
        DEPOSITING,
        COMBAT
    }

    private static final double WORK_REACH_SQR = 9.0D;
    private static final double STORAGE_REACH_SQR = 9.0D;
    private static final double GUARD_ALERT_RADIUS = 16.0D;
    private static final double GUARD_DISENGAGE_RADIUS_SQR = 676.0D; // 26 blocks
    private static final int WORK_DURATION_TICKS = 30;
    private static final int ATTACK_COOLDOWN_TICKS = 20;

    private final ColonyCitizenEntity citizen;
    private FsmState state = FsmState.IDLE;
    private BlockPos activeStorageTarget;
    private int workTicks = 0;
    private int combatCooldown = 0;
    private int idleCheckCooldown = 0;

    public CitizenJobFSMGoal(ColonyCitizenEntity citizen) {
        this.citizen = citizen;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.TARGET));
    }

    public FsmState getState() {
        return this.state;
    }

    private void transitionTo(FsmState nextState) {
        this.state = nextState;
        this.citizen.setFsmStateName(nextState);
        if (nextState != FsmState.WORKING) {
            this.workTicks = 0;
        }
    }

    @Override
    public boolean canUse() {
        return !this.citizen.level().isClientSide() && this.citizen.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        if (!(this.citizen.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (this.combatCooldown > 0) {
            this.combatCooldown--;
        }

        CityZoneManager manager = CityZoneManager.get(serverLevel);

        // Priority 1: Guard combat check or self-defense
        if (checkAndEnterCombat(serverLevel, manager)) {
            if (this.state != FsmState.COMBAT) {
                transitionTo(FsmState.COMBAT);
            }
        }

        // Priority 2: Direct RTS player move command override when not in combat
        Optional<BlockPos> commandedOpt = this.citizen.getCommandedTargetPos();
        if (commandedOpt.isPresent() && this.state != FsmState.COMBAT) {
            BlockPos cmdPos = commandedOpt.get();
            if (this.citizen.blockPosition().distSqr(cmdPos) <= 4.0D) {
                this.citizen.setCommandedTargetPos(null);
            } else {
                if (this.citizen.getNavigation().isDone()) {
                    this.citizen.getNavigation().moveTo(cmdPos.getX() + 0.5D, cmdPos.getY(), cmdPos.getZ() + 0.5D, 1.15D);
                }
                return;
            }
        }

        switch (this.state) {
            case IDLE -> tickIdle(serverLevel, manager);
            case MOVE_TO_TASK -> tickMoveToTask(serverLevel, manager);
            case WORKING -> tickWorking(serverLevel, manager);
            case MOVE_TO_STORAGE -> tickMoveToStorage(serverLevel, manager);
            case DEPOSITING -> tickDepositing(serverLevel, manager);
            case COMBAT -> tickCombat(serverLevel, manager);
        }
    }

    private boolean checkAndEnterCombat(ServerLevel level, CityZoneManager manager) {
        if (this.citizen.getJobType() == JobType.GUARD) {
            LivingEntity currentTarget = this.citizen.getTarget();
            if (currentTarget != null && currentTarget.isAlive()) {
                return true;
            }
            BlockPos anchor = this.citizen.getOutpostPos().orElseGet(this.citizen::blockPosition);
            AABB searchBox = new AABB(anchor).inflate(GUARD_ALERT_RADIUS, 8.0D, GUARD_ALERT_RADIUS);
            List<Monster> monsters = level.getEntitiesOfClass(Monster.class, searchBox, LivingEntity::isAlive);
            if (!monsters.isEmpty()) {
                Monster nearest = monsters.stream()
                        .min(Comparator.comparingDouble(this.citizen::distanceToSqr))
                        .orElse(monsters.getFirst());
                this.citizen.setTarget(nearest);
                ensureGuardArmed(level, manager);
                return true;
            }
        } else if (this.citizen.getLastHurtByMob() instanceof Monster attacker && attacker.isAlive()) {
            this.citizen.setTarget(attacker);
            return true;
        }
        return false;
    }

    private void ensureGuardArmed(ServerLevel level, CityZoneManager manager) {
        if (this.citizen.getCitizenInventory().findCombatWeapon().isEmpty()
                && !StorageLogisticsHelper.isCombatWeapon(this.citizen.getMainHandItem())) {
            ItemStack withdrawn = StorageLogisticsHelper.withdrawWeaponForGuard(level, manager);
            if (!withdrawn.isEmpty()) {
                this.citizen.getCitizenInventory().insertStack(withdrawn);
            }
        }
        this.citizen.equipBestCombatWeapon();
    }

    private void tickIdle(ServerLevel level, CityZoneManager manager) {
        JobType job = this.citizen.getJobType();
        if (job == JobType.NONE) {
            return;
        }

        if (job == JobType.GUARD) {
            ensureGuardOutpostAssigned(manager);
            Optional<BlockPos> outpostOpt = this.citizen.getOutpostPos();
            if (outpostOpt.isPresent()) {
                BlockPos outpost = outpostOpt.get();
                if (this.citizen.blockPosition().distSqr(outpost) > 6.25D) {
                    this.citizen.getNavigation().moveTo(outpost.getX() + 0.5D, outpost.getY(), outpost.getZ() + 0.5D, 1.0D);
                }
            }
            return;
        }

        if (this.citizen.getCitizenInventory().isReadyForDeposit()
                || (job == JobType.COURIER && this.citizen.getCitizenInventory().hasDeliverableItems())) {
            transitionTo(FsmState.MOVE_TO_STORAGE);
            return;
        }

        if (this.idleCheckCooldown > 0) {
            this.idleCheckCooldown--;
            return;
        }
        this.idleCheckCooldown = 10;

        Optional<ColonyTask> nextTaskOpt = this.citizen.getCurrentTask()
                .filter(t -> !t.state().isTerminal())
                .or(() -> manager.pollNextTaskFor(job, this.citizen.getUUID(), this.citizen.blockPosition()));

        if (nextTaskOpt.isPresent()) {
            ColonyTask task = nextTaskOpt.get();
            this.citizen.setCurrentTask(task);
            transitionTo(FsmState.MOVE_TO_TASK);
        } else if (this.citizen.getCitizenInventory().hasDeliverableItems()) {
            transitionTo(FsmState.MOVE_TO_STORAGE);
        }
    }

    private void ensureGuardOutpostAssigned(CityZoneManager manager) {
        if (this.citizen.getOutpostPos().isPresent()) {
            return;
        }
        Optional<ZoneData> defenseZone = manager.getZonesByType(ZoneType.DEFENSE).stream().findFirst();
        if (defenseZone.isPresent()) {
            this.citizen.setOutpostPos(defenseZone.get().center());
        } else {
            manager.getTownHallPos().ifPresent(this.citizen::setOutpostPos);
        }
    }

    private void tickMoveToTask(ServerLevel level, CityZoneManager manager) {
        Optional<ColonyTask> taskOpt = this.citizen.getCurrentTask();
        if (taskOpt.isEmpty() || taskOpt.get().state().isTerminal()) {
            this.citizen.setCurrentTask(null);
            transitionTo(FsmState.IDLE);
            return;
        }

        ColonyTask task = taskOpt.get();
        BlockPos target = task.targetPos();
        if (this.citizen.blockPosition().distSqr(target) <= WORK_REACH_SQR) {
            this.citizen.getNavigation().stop();
            task.markInProgress();
            transitionTo(FsmState.WORKING);
            return;
        }

        if (this.citizen.getNavigation().isDone()) {
            boolean pathStarted = this.citizen.getNavigation().moveTo(
                    target.getX() + 0.5D,
                    target.getY(),
                    target.getZ() + 0.5D,
                    1.05D
            );
            if (!pathStarted && this.citizen.blockPosition().distSqr(target) <= 16.0D) {
                task.markInProgress();
                transitionTo(FsmState.WORKING);
            }
        }
    }

    private void tickWorking(ServerLevel level, CityZoneManager manager) {
        Optional<ColonyTask> taskOpt = this.citizen.getCurrentTask();
        if (taskOpt.isEmpty()) {
            transitionTo(FsmState.IDLE);
            return;
        }

        ColonyTask task = taskOpt.get();
        BlockPos target = task.targetPos();
        this.citizen.getLookControl().setLookAt(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D);
        if ((this.workTicks % 6) == 0) {
            this.citizen.swing(InteractionHand.MAIN_HAND);
        }

        this.workTicks++;
        if (this.workTicks < WORK_DURATION_TICKS) {
            return;
        }

        executeTaskAction(level, manager, task);
        manager.completeTask(task.taskId());
        this.citizen.setCurrentTask(null);

        if (this.citizen.getCitizenInventory().isReadyForDeposit()) {
            transitionTo(FsmState.MOVE_TO_STORAGE);
        } else {
            transitionTo(FsmState.IDLE);
        }
    }

    private void executeTaskAction(ServerLevel level, CityZoneManager manager, ColonyTask task) {
        BlockPos pos = task.targetPos();
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);

        switch (task.type()) {
            case CHOP -> {
                if (ZoneTaskGenerator.isLogBlock(state)) {
                    harvestBlockDropsIntoInventory(level, pos, state);
                    level.destroyBlock(pos, false, this.citizen);
                    BlockState below = level.getBlockState(pos.below());
                    if (below.is(BlockTags.DIRT) && level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.OAK_SAPLING.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
            case MINE -> {
                if (ZoneTaskGenerator.isMineableTopBlock(level, pos, state)) {
                    harvestBlockDropsIntoInventory(level, pos, state);
                    level.destroyBlock(pos, false, this.citizen);
                }
            }
            case HARVEST -> {
                if (ZoneTaskGenerator.isMatureCrop(state)) {
                    harvestBlockDropsIntoInventory(level, pos, state);
                    if (state.getBlock() instanceof CropBlock cropBlock) {
                        level.setBlock(pos, cropBlock.getStateForAge(0), Block.UPDATE_ALL);
                    } else if (state.getBlock() instanceof SweetBerryBushBlock) {
                        level.setBlock(pos, state.setValue(SweetBerryBushBlock.AGE, 1), Block.UPDATE_ALL);
                    } else {
                        level.destroyBlock(pos, false, this.citizen);
                    }
                }
            }
            case BUILD -> {
                if (state.isAir() || state.canBeReplaced()) {
                    BlockState buildBlockState = consumeOrFetchBuildingBlock(level, manager);
                    level.setBlock(pos, buildBlockState, Block.UPDATE_ALL);
                }
            }
            case DELIVER -> {
                // Courier delivery transitions directly to storage deposit
            }
            case GUARD -> {
                this.citizen.setOutpostPos(pos);
            }
        }
    }

    private void harvestBlockDropsIntoInventory(ServerLevel level, BlockPos pos, BlockState state) {
        List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), this.citizen, this.citizen.getMainHandItem());
        if (drops.isEmpty()) {
            ItemStack fallback = new ItemStack(state.getBlock().asItem());
            if (!fallback.isEmpty()) {
                drops = List.of(fallback);
            }
        }
        for (ItemStack drop : drops) {
            ItemStack remainder = this.citizen.getCitizenInventory().insertStack(drop);
            if (!remainder.isEmpty()) {
                Block.popResource(level, pos, remainder);
            }
        }
    }

    private BlockState consumeOrFetchBuildingBlock(ServerLevel level, CityZoneManager manager) {
        CitizenInventory inv = this.citizen.getCitizenInventory();
        int slot = inv.findSlotMatching(StorageLogisticsHelper::isBuildingBlockItem);
        if (slot < 0) {
            ItemStack fetched = StorageLogisticsHelper.withdrawBuildingMaterial(level, manager, 16);
            if (!fetched.isEmpty()) {
                inv.insertStack(fetched);
                slot = inv.findSlotMatching(StorageLogisticsHelper::isBuildingBlockItem);
            }
        }
        if (slot >= 0) {
            ItemStack stack = inv.getItem(slot);
            if (stack.getItem() instanceof BlockItem blockItem) {
                BlockState state = blockItem.getBlock().defaultBlockState();
                stack.shrink(1);
                if (stack.isEmpty()) {
                    inv.setItem(slot, ItemStack.EMPTY);
                }
                return state;
            }
        }
        return Blocks.OAK_PLANKS.defaultBlockState();
    }

    private void tickMoveToStorage(ServerLevel level, CityZoneManager manager) {
        if (!this.citizen.getCitizenInventory().hasDeliverableItems()) {
            this.activeStorageTarget = null;
            transitionTo(FsmState.IDLE);
            return;
        }

        if (this.activeStorageTarget == null) {
            this.activeStorageTarget = StorageLogisticsHelper
                    .findNearestStorageWithSpace(level, manager, this.citizen.blockPosition(), ItemStack.EMPTY)
                    .or(manager::getTownHallPos)
                    .orElse(null);
        }

        if (this.activeStorageTarget == null) {
            transitionTo(FsmState.IDLE);
            return;
        }

        if (this.citizen.blockPosition().distSqr(this.activeStorageTarget) <= STORAGE_REACH_SQR) {
            this.citizen.getNavigation().stop();
            transitionTo(FsmState.DEPOSITING);
            return;
        }

        if (this.citizen.getNavigation().isDone()) {
            this.citizen.getNavigation().moveTo(
                    this.activeStorageTarget.getX() + 0.5D,
                    this.activeStorageTarget.getY(),
                    this.activeStorageTarget.getZ() + 0.5D,
                    1.1D
            );
        }
    }

    private void tickDepositing(ServerLevel level, CityZoneManager manager) {
        CitizenInventory inv = this.citizen.getCitizenInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || StorageLogisticsHelper.isCombatWeapon(stack)) {
                continue;
            }
            ItemStack remainder = StorageLogisticsHelper.depositStack(level, manager, stack);
            inv.setItem(i, remainder);
        }
        this.activeStorageTarget = null;
        transitionTo(FsmState.IDLE);
    }

    private void tickCombat(ServerLevel level, CityZoneManager manager) {
        LivingEntity target = this.citizen.getTarget();
        BlockPos outpost = this.citizen.getOutpostPos().orElseGet(this.citizen::blockPosition);

        if (target == null || !target.isAlive() || target.distanceToSqr(outpost.getX(), outpost.getY(), outpost.getZ()) > GUARD_DISENGAGE_RADIUS_SQR) {
            this.citizen.setTarget(null);
            if (this.citizen.getJobType() == JobType.GUARD && this.citizen.getOutpostPos().isPresent()) {
                BlockPos post = this.citizen.getOutpostPos().get();
                this.citizen.getNavigation().moveTo(post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D, 1.1D);
            }
            transitionTo(FsmState.IDLE);
            return;
        }

        this.citizen.equipBestCombatWeapon();
        this.citizen.getLookControl().setLookAt(target, 30.0F, 30.0F);
        double distSqr = this.citizen.distanceToSqr(target);
        ItemStack mainHand = this.citizen.getItemBySlot(EquipmentSlot.MAINHAND);
        boolean usingBow = mainHand.is(Items.BOW) || mainHand.is(Items.CROSSBOW);

        if (usingBow && distSqr > 16.0D && distSqr <= 256.0D) {
            this.citizen.getNavigation().stop();
            if (this.combatCooldown <= 0) {
                this.citizen.performRangedAttack(target, 1.0F);
                this.combatCooldown = ATTACK_COOLDOWN_TICKS + 10;
            }
        } else {
            this.citizen.getNavigation().moveTo(target, 1.2D);
            if (distSqr <= 6.25D && this.combatCooldown <= 0) {
                this.citizen.swing(InteractionHand.MAIN_HAND);
                this.citizen.doHurtTarget(level, target);
                this.combatCooldown = ATTACK_COOLDOWN_TICKS;
            }
        }
    }
}
