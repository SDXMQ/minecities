package com.citiescolony.common.entity;

import com.citiescolony.api.ColonyIds;
import com.citiescolony.api.ColonyTask;
import com.citiescolony.api.JobType;
import com.citiescolony.api.ZoneData;
import com.citiescolony.common.city.CityZoneManager;
import com.citiescolony.common.city.StorageLogisticsHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Containers;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Lightweight autonomous colony citizen built on PathfinderMob without the heavy vanilla Brain system.
 * Features a 9-slot CitizenInventory, JobType, canonical ColonyTask reference, road speed boost (+30%),
 * and a single CitizenJobFSMGoal state machine.
 */
public class ColonyCitizenEntity extends PathfinderMob implements RangedAttackMob {

    public static final Identifier ROAD_SPEED_MODIFIER_ID = ColonyIds.id("road_speed_boost");
    public static final double ROAD_SPEED_BOOST_AMOUNT = 0.30D;
    public static final AttributeModifier ROAD_SPEED_MODIFIER = new AttributeModifier(
            ROAD_SPEED_MODIFIER_ID,
            ROAD_SPEED_BOOST_AMOUNT,
            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
    );

    private static final EntityDataAccessor<Integer> DATA_JOB_ID =
            SynchedEntityData.defineId(ColonyCitizenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_FSM_STATE =
            SynchedEntityData.defineId(ColonyCitizenEntity.class, EntityDataSerializers.STRING);

    private final CitizenInventory inventory = new CitizenInventory();
    private ColonyTask currentTask;
    private boolean taskCanonicalized = true;
    private BlockPos outpostPos;
    private BlockPos commandedTargetPos;

    public ColonyCitizenEntity(EntityType<? extends ColonyCitizenEntity> entityType, Level level) {
        super(entityType, level);
        this.setCanPickUpLoot(true);
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 24.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.28D)
                .add(Attributes.ATTACK_DAMAGE, 4.0D)
                .add(Attributes.FOLLOW_RANGE, 36.0D)
                .add(Attributes.ARMOR, 2.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_JOB_ID, JobType.NONE.id());
        builder.define(DATA_FSM_STATE, CitizenJobFSMGoal.FsmState.IDLE.name());
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new CitizenJobFSMGoal(this));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 6.0F));
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new GroundPathNavigation(this, level) {
            @Override
            protected PathFinder createPathFinder(int maxVisitedNodes) {
                this.nodeEvaluator = new RoadAwareNodeEvaluator();
                this.nodeEvaluator.setCanPassDoors(true);
                this.nodeEvaluator.setCanOpenDoors(true);
                this.nodeEvaluator.setCanFloat(true);
                return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
            }
        };
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (this.level() instanceof ServerLevel serverLevel) {
            CityZoneManager manager = CityZoneManager.get(serverLevel);
            manager.registerCitizen(this);
            if (this.currentTask != null && !this.taskCanonicalized) {
                this.currentTask = manager.resolveCanonicalTask(this.currentTask);
                this.taskCanonicalized = true;
            }
        }
    }

    @Override
    public void onRemovedFromLevel() {
        if (this.level() instanceof ServerLevel serverLevel) {
            CityZoneManager.get(serverLevel).unregisterCitizen(this);
        }
        super.onRemovedFromLevel();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level() instanceof ServerLevel serverLevel) {
            CityZoneManager manager = CityZoneManager.get(serverLevel);
            manager.registerCitizen(this);
            if (this.currentTask != null && !this.taskCanonicalized) {
                this.currentTask = manager.resolveCanonicalTask(this.currentTask);
                this.taskCanonicalized = true;
            }
            updateRoadMovementSpeedModifier();
        }
    }

    private void updateRoadMovementSpeedModifier() {
        AttributeInstance speedAttr = this.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr == null) {
            return;
        }
        boolean onRoad = RoadAwareNodeEvaluator.isEntityOnRoad(this.level(), this.blockPosition());
        boolean hasModifier = speedAttr.hasModifier(ROAD_SPEED_MODIFIER_ID);
        if (onRoad && !hasModifier) {
            speedAttr.addTransientModifier(ROAD_SPEED_MODIFIER);
        } else if (!onRoad && hasModifier) {
            speedAttr.removeModifier(ROAD_SPEED_MODIFIER_ID);
        }
    }

    public CitizenInventory getCitizenInventory() {
        return this.inventory;
    }

    public JobType getJobType() {
        int rawId = this.entityData.get(DATA_JOB_ID);
        JobType[] values = JobType.values();
        if (rawId >= 0 && rawId < values.length) {
            return values[rawId];
        }
        return JobType.NONE;
    }

    public void setJobType(JobType jobType) {
        JobType resolved = jobType == null ? JobType.NONE : jobType;
        JobType previous = getJobType();
        this.entityData.set(DATA_JOB_ID, resolved.id());
        if (previous != resolved && this.level() instanceof ServerLevel serverLevel) {
            CityZoneManager manager = CityZoneManager.get(serverLevel);
            manager.releaseCitizenTasks(this.getUUID());
            this.currentTask = null;
            if (resolved == JobType.GUARD && this.outpostPos == null) {
                Optional<BlockPos> outpostBlock = manager.findNearestGuardOutpost(this.blockPosition());
                if (outpostBlock.isPresent()) {
                    this.outpostPos = outpostBlock.get();
                } else {
                    Optional<ZoneData> defenseZone = manager.getZonesByType(com.citiescolony.api.ZoneType.DEFENSE)
                            .stream()
                            .findFirst();
                    this.outpostPos = defenseZone.map(ZoneData::center).orElseGet(this::blockPosition);
                }
            }
        }
    }

    public String getFsmStateName() {
        return this.entityData.get(DATA_FSM_STATE);
    }

    public void setFsmStateName(CitizenJobFSMGoal.FsmState state) {
        if (state != null) {
            this.entityData.set(DATA_FSM_STATE, state.name());
        }
    }

    public boolean isCitizenIdle() {
        return getJobType() == JobType.NONE
                || CitizenJobFSMGoal.FsmState.IDLE.name().equals(getFsmStateName());
    }

    public Optional<ColonyTask> getCurrentTask() {
        if (this.currentTask != null && !this.taskCanonicalized && this.level() instanceof ServerLevel serverLevel) {
            this.currentTask = CityZoneManager.get(serverLevel).resolveCanonicalTask(this.currentTask);
            this.taskCanonicalized = true;
        }
        return Optional.ofNullable(this.currentTask);
    }

    public void setCurrentTask(ColonyTask task) {
        if (task != null && this.level() instanceof ServerLevel serverLevel) {
            this.currentTask = CityZoneManager.get(serverLevel).resolveCanonicalTask(task);
            this.taskCanonicalized = true;
        } else {
            this.currentTask = task;
            this.taskCanonicalized = true;
        }
    }

    public Optional<BlockPos> getOutpostPos() {
        return Optional.ofNullable(this.outpostPos);
    }

    public void setOutpostPos(BlockPos outpostPos) {
        this.outpostPos = outpostPos == null ? null : outpostPos.immutable();
    }

    public Optional<BlockPos> getCommandedTargetPos() {
        return Optional.ofNullable(this.commandedTargetPos);
    }

    public void setCommandedTargetPos(BlockPos targetPos) {
        this.commandedTargetPos = targetPos == null ? null : targetPos.immutable();
    }

    public void issuePlayerCommand(ServerLevel level, BlockPos targetPos) {
        if (targetPos == null) {
            return;
        }
        CityZoneManager manager = CityZoneManager.get(level);
        manager.releaseCitizenTasks(this.getUUID());
        this.currentTask = null;

        Optional<ZoneData> targetZone = manager.getZoneAt(targetPos);
        if (targetZone.isPresent()) {
            ZoneData zone = targetZone.get();
            manager.assignCitizenToZone(zone.id(), this.getUUID());
            JobType mappedJob = JobType.forZoneType(zone.type());
            if (mappedJob != JobType.NONE) {
                setJobType(mappedJob);
            }
            if (zone.type() == com.citiescolony.api.ZoneType.DEFENSE) {
                setOutpostPos(targetPos);
            }
        } else if (getJobType() == JobType.GUARD) {
            setOutpostPos(targetPos);
        }

        Optional<ColonyTask> forcedTaskOpt = createForcedTaskIfActionable(level, targetPos);
        if (forcedTaskOpt.isPresent()) {
            ColonyTask forcedTask = forcedTaskOpt.get();
            forcedTask.assignTo(this.getUUID());
            manager.enqueueTask(forcedTask);
            this.currentTask = manager.resolveCanonicalTask(forcedTask);
            this.taskCanonicalized = true;
            this.commandedTargetPos = null;
        } else {
            setCommandedTargetPos(targetPos);
        }
        this.getNavigation().moveTo(targetPos.getX() + 0.5D, targetPos.getY(), targetPos.getZ() + 0.5D, 1.15D);
    }

    private Optional<ColonyTask> createForcedTaskIfActionable(ServerLevel level, BlockPos targetPos) {
        if (!level.isLoaded(targetPos) || getJobType() == JobType.GUARD) {
            return Optional.empty();
        }
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(targetPos);
        if (com.citiescolony.common.city.ZoneTaskGenerator.isLogBlock(state)) {
            if (getJobType() == JobType.NONE) {
                setJobType(JobType.LUMBERJACK);
            }
            if (getJobType().canExecute(ColonyTask.TaskType.CHOP)) {
                return Optional.of(new ColonyTask(ColonyTask.TaskType.CHOP, targetPos, 200));
            }
        } else if (com.citiescolony.common.city.ZoneTaskGenerator.isMatureCrop(state)) {
            if (getJobType() == JobType.NONE) {
                setJobType(JobType.FARMER);
            }
            if (getJobType().canExecute(ColonyTask.TaskType.HARVEST)) {
                return Optional.of(new ColonyTask(ColonyTask.TaskType.HARVEST, targetPos, 200));
            }
        } else if (StorageLogisticsHelper.getStorageContainerAt(level, targetPos).isPresent()
                && this.inventory.hasDeliverableItems()) {
            return Optional.of(new ColonyTask(ColonyTask.TaskType.DELIVER, targetPos, 200));
        } else if (getJobType() == JobType.MINER
                && com.citiescolony.common.city.ZoneTaskGenerator.isMineableTopBlock(level, targetPos, state)) {
            return Optional.of(new ColonyTask(ColonyTask.TaskType.MINE, targetPos, 200));
        }
        return Optional.empty();
    }

    @Override
    public void performRangedAttack(LivingEntity target, float distanceFactor) {
        ItemStack weapon = this.getMainHandItem();
        if (!weapon.is(Items.BOW) && !weapon.is(Items.CROSSBOW)) {
            return;
        }
        ItemStack arrowPrototype = new ItemStack(Items.ARROW);
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, arrowPrototype, distanceFactor, weapon);
        double dx = target.getX() - this.getX();
        double dy = target.getY(0.3333333333333333D) - arrow.getY();
        double dz = target.getZ() - this.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + horizontalDist * 0.2D, dz, 1.6F, 8.0F);
        this.playSound(SoundEvents.SKELETON_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
        this.level().addFreshEntity(arrow);
    }

    /**
     * Equips a combat weapon from the citizen's inventory by swapping slots (without duplicating items via .copy()
     * and without spawning free Iron Swords out of thin air).
     */
    public void equipBestCombatWeapon() {
        LivingEntity target = this.getTarget();
        boolean preferRanged = target != null && this.distanceToSqr(target) > 25.0D;
        ItemStack currentMainHand = this.getMainHandItem();

        Predicate<ItemStack> rangedCheck = s -> s.is(Items.BOW) || s.is(Items.CROSSBOW);
        Predicate<ItemStack> meleeCheck = s -> StorageLogisticsHelper.isCombatWeapon(s) && !rangedCheck.test(s);

        if (preferRanged) {
            if (rangedCheck.test(currentMainHand)) {
                return;
            }
            if (swapInventorySlotToMainHand(rangedCheck)) {
                return;
            }
        }

        if (meleeCheck.test(currentMainHand)) {
            return;
        }
        if (swapInventorySlotToMainHand(meleeCheck)) {
            return;
        }
        if (!rangedCheck.test(currentMainHand)) {
            swapInventorySlotToMainHand(rangedCheck);
        }
    }

    private boolean swapInventorySlotToMainHand(Predicate<ItemStack> weaponPredicate) {
        int slot = this.inventory.findSlotMatching(weaponPredicate);
        if (slot < 0) {
            return false;
        }
        ItemStack chosenWeapon = this.inventory.removeItemNoUpdate(slot);
        ItemStack previousHand = this.getMainHandItem();
        if (!previousHand.isEmpty()) {
            ItemStack leftover = this.inventory.insertStack(previousHand);
            if (!leftover.isEmpty()) {
                this.inventory.setItem(slot, leftover);
            }
        }
        this.setItemSlot(EquipmentSlot.MAINHAND, chosenWeapon);
        return true;
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, damageSource, recentlyHit);
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.removeItemNoUpdate(i);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, this.getX(), this.getY(), this.getZ(), stack);
            }
        }
        ItemStack mainHand = this.getMainHandItem();
        if (!mainHand.isEmpty()) {
            Containers.dropItemStack(level, this.getX(), this.getY(), this.getZ(), mainHand);
            this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        CityZoneManager manager = CityZoneManager.get(level);
        manager.unregisterCitizen(this);
        manager.releaseCitizenTasks(this.getUUID());
        manager.unassignCitizen(this.getUUID());
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.store("job_type", JobType.CODEC, this.getJobType());
        this.inventory.saveToValueOutput(output);
        if (this.currentTask != null) {
            output.store("current_task", ColonyTask.CODEC, this.currentTask);
        }
        if (this.outpostPos != null) {
            output.store("outpost_pos", BlockPos.CODEC, this.outpostPos);
        }
        if (this.commandedTargetPos != null) {
            output.store("commanded_target_pos", BlockPos.CODEC, this.commandedTargetPos);
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        JobType loadedJob = input.read("job_type", JobType.CODEC).orElse(JobType.NONE);
        this.entityData.set(DATA_JOB_ID, loadedJob.id());
        this.inventory.loadFromValueInput(input);
        this.currentTask = input.read("current_task", ColonyTask.CODEC).orElse(null);
        this.taskCanonicalized = false;
        this.outpostPos = input.read("outpost_pos", BlockPos.CODEC).orElse(null);
        this.commandedTargetPos = input.read("commanded_target_pos", BlockPos.CODEC).orElse(null);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }
}
