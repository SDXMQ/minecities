package com.citiescolony.common.entity;

import com.citiescolony.common.city.StorageLogisticsHelper;
import net.minecraft.core.NonNullList;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.function.Predicate;

/**
 * Lightweight 9-slot inventory dedicated to ColonyCitizenEntity.
 * Supports Minecraft 26.2 ValueInput / ValueOutput serialization via ContainerHelper.
 */
public class CitizenInventory extends SimpleContainer {
    public static final int SLOT_COUNT = 9;

    public CitizenInventory() {
        super(SLOT_COUNT);
    }

    public boolean isCompletelyFull() {
        for (int i = 0; i < this.getContainerSize(); i++) {
            ItemStack stack = this.getItem(i);
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    public boolean hasEmptySlot() {
        for (int i = 0; i < this.getContainerSize(); i++) {
            if (this.getItem(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public boolean isReadyForDeposit() {
        int occupiedSlots = 0;
        for (int i = 0; i < this.getContainerSize(); i++) {
            ItemStack stack = this.getItem(i);
            if (!stack.isEmpty() && !StorageLogisticsHelper.isCombatWeapon(stack)) {
                occupiedSlots++;
            }
        }
        return occupiedSlots >= (SLOT_COUNT - 1) || !hasEmptySlot();
    }

    public boolean hasDeliverableItems() {
        for (int i = 0; i < this.getContainerSize(); i++) {
            ItemStack stack = this.getItem(i);
            if (!stack.isEmpty() && !StorageLogisticsHelper.isCombatWeapon(stack)) {
                return true;
            }
        }
        return false;
    }

    public ItemStack insertStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return this.addItem(stack.copy());
    }

    public int findSlotMatching(Predicate<ItemStack> predicate) {
        for (int i = 0; i < this.getContainerSize(); i++) {
            ItemStack stack = this.getItem(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    public ItemStack findCombatWeapon() {
        int bowSlot = findSlotMatching(s -> s.is(Items.BOW) || s.is(Items.CROSSBOW));
        int meleeSlot = findSlotMatching(s -> s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES));
        if (meleeSlot >= 0) {
            return this.getItem(meleeSlot);
        }
        if (bowSlot >= 0) {
            return this.getItem(bowSlot);
        }
        return ItemStack.EMPTY;
    }

    public ItemStack findRangedWeapon() {
        int slot = findSlotMatching(s -> s.is(Items.BOW) || s.is(Items.CROSSBOW));
        return slot >= 0 ? this.getItem(slot) : ItemStack.EMPTY;
    }

    public ItemStack findMeleeWeapon() {
        int slot = findSlotMatching(s -> s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES));
        return slot >= 0 ? this.getItem(slot) : ItemStack.EMPTY;
    }

    public void saveToValueOutput(ValueOutput output) {
        NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        for (int i = 0; i < SLOT_COUNT; i++) {
            items.set(i, this.getItem(i));
        }
        ContainerHelper.saveAllItems(output, items);
    }

    public void loadFromValueInput(ValueInput input) {
        NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        for (int i = 0; i < SLOT_COUNT; i++) {
            this.setItem(i, items.get(i));
        }
    }
}
