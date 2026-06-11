package com.plumejade.stairway_heaven.component;

import com.mojang.serialization.Codec;
import com.plumejade.stairway_heaven.StairwayHeaven;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModDataComponents {
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, StairwayHeaven.MODID);

    /** Current upgrade level of heaven boots (derived from molds in unlocked slots). */
    public static final Supplier<DataComponentType<Integer>> UPGRADE_LEVEL =
            DATA_COMPONENTS.register("upgrade_level",
                    () -> DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .build());

    /** Stores the 9 mold slots persistently inside the boots item. */
    public static final Supplier<DataComponentType<ItemContainerContents>> MOLD_INVENTORY =
            DATA_COMPONENTS.register("mold_inventory",
                    () -> DataComponentType.<ItemContainerContents>builder()
                            .persistent(ItemContainerContents.CODEC)
                            .build());
}
