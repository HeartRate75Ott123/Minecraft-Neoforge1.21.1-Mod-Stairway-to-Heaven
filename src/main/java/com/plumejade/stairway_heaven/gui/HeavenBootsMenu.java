package com.plumejade.stairway_heaven.gui;

import com.plumejade.stairway_heaven.StairwayHeaven;
import com.plumejade.stairway_heaven.component.ModDataComponents;
import com.plumejade.stairway_heaven.event.StepUpEventHandler;
import com.plumejade.stairway_heaven.item.ModItems;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.List;

/**
 * 天堂之靴升级容器 — 9个可锁定模具槽位，每10级经验解锁一格。
 * 模具物品持久存储在天堂之靴的数据组件中，开关GUI不丢失。
 * <p>
 * Heaven Boots upgrade container — 9 lockable mold slots,
 * one unlocks every 10 XP levels. Molds persist in the boots
 * data component across open/close cycles.
 * <p>
 * <b>隐患预防 / Safety:</b><br>
 * — slotsChanged 实时写入，防止崩溃/死亡丢失模具<br>
 * — removed 找不到靴子时掉落模具，不吞物品
 */
public class HeavenBootsMenu extends AbstractContainerMenu {

    private static final int SLOTS = 9;
    private static final int LEVELS_PER_SLOT = 10;
    /** 与 StepUpEventHandler 共享键名，确保死亡克隆时一致 / shared key with Clone handler */
    private static final String UNLOCK_KEY = StepUpEventHandler.UNLOCK_KEY;

    private final Inventory playerInventory;
    private final Player player;
    private final int bootSlot;
    private int unlockedMask = 0;
    /** 防止removed清理槽位时触发slotsChanged递归覆盖 / prevents recursive overwrite during cleanup */
    private boolean closing = false;

    /**
     * Client-side constructor used by the MenuType factory.
     */
    public HeavenBootsMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, -1);
    }

    /**
     * Server-side constructor with the boots inventory slot for saving.
     */
    public HeavenBootsMenu(int containerId, Inventory playerInventory, int bootSlot) {
        super(StairwayHeaven.HEAVEN_BOOTS_MENU.get(), containerId);
        this.playerInventory = playerInventory;
        this.player = playerInventory.player;
        this.bootSlot = bootSlot;

        // Add 9 lockable mold slots (3x3 grid)
        for (int i = 0; i < SLOTS; i++) {
            int row = i / 3;
            int col = i % 3;
            this.addSlot(new LockableSlot(this, i, 62 + col * 18, 17 + row * 18));
        }

        // Player inventory
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        // Player hotbar
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }

        // Sync unlocked mask to client
        this.addDataSlot(new DataSlot() {
            @Override
            public int get() { return unlockedMask; }
            @Override
            public void set(int value) { unlockedMask = value; }
        });

        // Server-side initialization
        if (!player.level().isClientSide()) {
            initUnlocks();
            loadMoldsFromBoots();
        }
    }

    // ─── Unlock logic ────────────────────────────────────────────────

    private void initUnlocks() {
        var persistent = player.getPersistentData();
        byte[] unlocked = null;

        if (persistent.contains(UNLOCK_KEY)) {
            var tag = persistent.get(UNLOCK_KEY);
            if (tag instanceof ByteArrayTag byteArray) {
                unlocked = byteArray.getAsByteArray();
            }
        }
        if (unlocked == null || unlocked.length != SLOTS) {
            unlocked = new byte[SLOTS];
        }

        int playerLevel = player.experienceLevel;
        boolean changed = false;

        for (int i = 0; i < SLOTS; i++) {
            int required = (i + 1) * LEVELS_PER_SLOT;
            if (playerLevel >= required && unlocked[i] == 0) {
                unlocked[i] = 1;
                changed = true;
            }
            if (unlocked[i] != 0) {
                unlockedMask |= (1 << i);
            }
        }

        if (changed) {
            persistent.put(UNLOCK_KEY, new ByteArrayTag(unlocked));
        }
    }

    // ─── Mold persistence ────────────────────────────────────────────

    /** Load mold items from the boots' MOLD_INVENTORY component into the slots. */
    private void loadMoldsFromBoots() {
        ItemStack boots = getBoots();
        if (boots.isEmpty()) return;

        ItemContainerContents contents = boots.getOrDefault(
                ModDataComponents.MOLD_INVENTORY.get(), ItemContainerContents.EMPTY);

        // Copy items into slot containers
        var stacks = contents.stream().toList();
        for (int i = 0; i < SLOTS && i < stacks.size(); i++) {
            ItemStack mold = stacks.get(i);
            if (!mold.isEmpty() && mold.getItem() == ModItems.UPGRADE_MOLD.get()) {
                this.slots.get(i).set(mold.copy());
            }
        }
    }

    /** Save mold slot contents back to the boots and update upgrade_level. */
    private void saveMoldsToBoots() {
        ItemStack boots = getBoots();
        if (boots.isEmpty()) return;

        List<ItemStack> items = new ArrayList<>(SLOTS);
        int moldCount = 0;

        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = this.slots.get(i).getItem();
            if (stack.isEmpty()) {
                items.add(ItemStack.EMPTY);
            } else if (stack.getItem() == ModItems.UPGRADE_MOLD.get()) {
                items.add(stack.copy());
                if (isSlotUnlocked(i)) {
                    moldCount += stack.getCount();
                }
            } else {
                // Non-mold item – should not happen, but handle gracefully
                items.add(ItemStack.EMPTY);
            }
        }

        // Clamp upgrade level — 9 slots × 1 mold is the natural cap
        int newLevel = moldCount;

        boots.set(ModDataComponents.MOLD_INVENTORY.get(), ItemContainerContents.fromItems(items));
        boots.set(ModDataComponents.UPGRADE_LEVEL.get(), newLevel);
    }

    /** Find the heaven boots stack in the player's inventory. */
    private ItemStack getBoots() {
        // Try the original slot first
        ItemStack stack = playerInventory.getItem(bootSlot);
        if (stack.getItem() instanceof ModItems.HeavenBootsItem) {
            return stack;
        }
        // Fallback: scan entire inventory
        for (int i = 0; i < playerInventory.getContainerSize(); i++) {
            stack = playerInventory.getItem(i);
            if (stack.getItem() instanceof ModItems.HeavenBootsItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    // ─── Real-time sync ────────────────────────────────────────────

    /**
     * 每次槽位变化时立即保存模具到靴子，确保崩溃/死亡不丢数据。
     * Save molds to boots on every slot change — prevents data loss on crash/death.
     */
    @Override
    public void slotsChanged(net.minecraft.world.Container container) {
        super.slotsChanged(container);
        // 关闭过程中跳过写入，避免空槽位覆盖靴子数据
        // skip during cleanup to prevent empty slots overwriting boots data
        if (!player.level().isClientSide() && !closing) {
            saveMoldsToBoots();
        }
    }

    // ─── Close handler ───────────────────────────────────────────────

    /**
     * GUI关闭时保存模具并处理异常情况（玩家死亡/靴子丢失）。
     * On close: save molds, handle edge cases (death / boots missing).
     */
    @Override
    public void removed(Player player) {
        if (!player.level().isClientSide()) {
            closing = true;

            // 玩家死亡时不保存（物品已掉落），只清空槽位
            // don't save on death (items already dropped), just clear slots
            boolean playerDead = player.isDeadOrDying();

            if (!playerDead) {
                saveMoldsToBoots();
            }

            // 靴子找不到（死亡/丢失）→ 把槽位物品丢还给玩家避免吞物品
            // boots missing (death/loss) → return slot items to player
            boolean bootsMissing = playerDead || getBoots().isEmpty();

            for (int i = 0; i < SLOTS; i++) {
                ItemStack stack = this.slots.get(i).getItem();
                if (!stack.isEmpty()) {
                    if (bootsMissing) {
                        // 归还物品 / return items
                        if (!player.getInventory().add(stack)) {
                            player.drop(stack, false);
                        }
                    }
                    // 清空槽位 / clear slot
                    this.slots.get(i).set(ItemStack.EMPTY);
                }
            }
        }
        super.removed(player);
    }

    // ─── Slot helpers ────────────────────────────────────────────────

    public boolean isSlotUnlocked(int slotIndex) {
        return (unlockedMask & (1 << slotIndex)) != 0;
    }

    @Override
    public boolean stillValid(Player player) {
        return !player.isRemoved();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return result;

        ItemStack stackInSlot = slot.getItem();
        result = stackInSlot.copy();

        if (index < SLOTS) {
            // From mold slot to player inventory
            if (!this.moveItemStackTo(stackInSlot, SLOTS, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // From player inventory to mold slot – only allow upgrade_mold
            if (stackInSlot.getItem() == ModItems.UPGRADE_MOLD.get()) {
                if (!this.moveItemStackTo(stackInSlot, 0, SLOTS, false)) {
                    return ItemStack.EMPTY;
                }
            } else {
                return ItemStack.EMPTY;
            }
        }

        if (stackInSlot.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return result;
    }

    // ─── Lockable slot ───────────────────────────────────────────────

    private static class LockableSlot extends Slot {
        private final HeavenBootsMenu menu;
        private final int slotIndex;

        LockableSlot(HeavenBootsMenu menu, int slotIndex, int x, int y) {
            super(new SimpleContainer(1), 0, x, y);
            this.menu = menu;
            this.slotIndex = slotIndex;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return menu.isSlotUnlocked(slotIndex)
                    && stack.getItem() == ModItems.UPGRADE_MOLD.get();
        }

        @Override
        public boolean mayPickup(Player player) {
            return menu.isSlotUnlocked(slotIndex);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean isActive() {
            return true;
        }
    }
}
