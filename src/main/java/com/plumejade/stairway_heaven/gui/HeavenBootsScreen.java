package com.plumejade.stairway_heaven.gui;

import com.plumejade.stairway_heaven.StairwayHeaven;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Screen for the Heaven Boots upgrade GUI.
 * Renders locked slots with a barrier item overlay.
 */
public class HeavenBootsScreen extends AbstractContainerScreen<HeavenBootsMenu> {

    private static final ResourceLocation BACKGROUND =
            ResourceLocation.withDefaultNamespace("textures/gui/container/dispenser.png");

    private static final ItemStack BARRIER = new ItemStack(Items.BARRIER);

    private static final int SLOTS = 9;

    public HeavenBootsScreen(HeavenBootsMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        // Draw dispenser background
        guiGraphics.blit(BACKGROUND,
                this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight, 256, 256);

        // Draw barrier overlay on locked slots
        for (int i = 0; i < SLOTS; i++) {
            if (!this.menu.isSlotUnlocked(i)) {
                int row = i / 3;
                int col = i % 3;
                int x = this.leftPos + 62 + col * 18;
                int y = this.topPos + 17 + row * 18;

                guiGraphics.pose().pushPose();
                guiGraphics.pose().translate(0, 0, 200);
                guiGraphics.renderFakeItem(BARRIER, x, y);
                guiGraphics.pose().popPose();
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 0x404040, false);
        guiGraphics.drawString(this.font, this.playerInventoryTitle,
                this.inventoryLabelX, this.inventoryLabelY, 0x404040, false);
    }

    @Override
    protected void renderTooltip(GuiGraphics guiGraphics, int x, int y) {
        super.renderTooltip(guiGraphics, x, y);

        for (int i = 0; i < SLOTS; i++) {
            int row = i / 3;
            int col = i % 3;
            int slotX = this.leftPos + 62 + col * 18;
            int slotY = this.topPos + 17 + row * 18;

            if (x >= slotX && x < slotX + 18 && y >= slotY && y < slotY + 18) {
                if (!this.menu.isSlotUnlocked(i)) {
                    int requiredLevel = (i + 1) * 10;
                    guiGraphics.renderTooltip(this.font,
                            Component.translatable("tooltip.stairway_heaven.locked_slot", requiredLevel),
                            x, y);
                }
            }
        }
    }
}
