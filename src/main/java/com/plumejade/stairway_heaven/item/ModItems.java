package com.plumejade.stairway_heaven.item;

import com.plumejade.stairway_heaven.StairwayHeaven;
import com.plumejade.stairway_heaven.component.ModDataComponents;
import com.plumejade.stairway_heaven.gui.HeavenBootsMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = StairwayHeaven.ITEMS;

    public static final DeferredItem<Item> HEAVEN_BOOTS = ITEMS.registerItem("heaven_boots",
            HeavenBootsItem::new,
            new Item.Properties().stacksTo(1));

    public static final DeferredItem<Item> UPGRADE_MOLD = ITEMS.registerItem("upgrade_mold",
            Item::new,
            new Item.Properties().stacksTo(1));

    /** Hidden item used only for the creative tab icon texture. */
    public static final DeferredItem<Item> TAB_ICON = ITEMS.registerItem("tab_icon",
            Item::new,
            new Item.Properties().stacksTo(1));

    public static class HeavenBootsItem extends Item {
        private static final Component CONTAINER_TITLE =
                Component.translatable("container.stairway_heaven.heaven_boots");

        public HeavenBootsItem(Properties properties) {
            super(properties);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);

            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                int bootSlot = hand == InteractionHand.MAIN_HAND
                        ? player.getInventory().selected
                        : 40; // offhand

                serverPlayer.openMenu(new MenuProvider() {
                    @Override
                    public Component getDisplayName() {
                        return CONTAINER_TITLE;
                    }

                    @Override
                    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
                        return new HeavenBootsMenu(containerId, playerInventory, bootSlot);
                    }
                });
            }

            return InteractionResultHolder.success(stack);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            int upgradeLevel = stack.getOrDefault(ModDataComponents.UPGRADE_LEVEL.get(), 0);

            if (Screen.hasShiftDown()) {
                int currentStep = upgradeLevel;

                tooltip.add(Component.translatable("tooltip.stairway_heaven.details.level",
                        upgradeLevel, 9));
                tooltip.add(Component.translatable("tooltip.stairway_heaven.details.step",
                        currentStep));
            } else {
                tooltip.add(Component.translatable("tooltip.stairway_heaven.hold_shift"));
            }

            super.appendHoverText(stack, context, tooltip, flag);
        }

        @Override
        public boolean isFoil(ItemStack stack) {
            int level = stack.getOrDefault(ModDataComponents.UPGRADE_LEVEL.get(), 0);
            return level > 0;
        }

        /** 天堂之靴不可附魔 / Heaven Boots cannot be enchanted */
        @Override
        public boolean isEnchantable(ItemStack stack) {
            return false;
        }
    }
}
