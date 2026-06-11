package com.plumejade.stairway_heaven.client;

import com.plumejade.stairway_heaven.StairwayHeaven;
import com.plumejade.stairway_heaven.gui.HeavenBootsMenu;
import com.plumejade.stairway_heaven.gui.HeavenBootsScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * Client-side only event handlers.
 */
@EventBusSubscriber(modid = StairwayHeaven.MODID, value = Dist.CLIENT)
public class ClientModEvents {

    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(StairwayHeaven.HEAVEN_BOOTS_MENU.get(), HeavenBootsScreen::new);
    }
}
