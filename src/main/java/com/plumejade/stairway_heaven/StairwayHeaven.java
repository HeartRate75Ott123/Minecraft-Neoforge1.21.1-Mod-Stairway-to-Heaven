package com.plumejade.stairway_heaven;

import com.mojang.logging.LogUtils;
import com.plumejade.stairway_heaven.component.ModDataComponents;
import com.plumejade.stairway_heaven.event.StepUpEventHandler;
import com.plumejade.stairway_heaven.gui.HeavenBootsMenu;
import com.plumejade.stairway_heaven.item.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * 天堂阶梯 (Stairway to Heaven) — 主模组类
 * <p>
 * 添加天堂之靴（自动上坡）、升级模块、升级 GUI 和自动上坡附魔。
 * 使用 NeoForge 1.21.1 的事件系统和属性 API，零外部依赖。
 * <p>
 * Adds Heaven Boots (auto step-up), Upgrade Molds, upgrade GUI,
 * and Auto Step enchantment. Uses NeoForge 1.21.1 event system
 * and attribute API. Zero external dependencies.
 *
 * @author PlumeJade
 */
@Mod(StairwayHeaven.MODID)
public class StairwayHeaven {
    public static final String MODID = "stairway_heaven";
    public static final Logger LOGGER = LogUtils.getLogger();

    // ── Deferred Register 注册器 ──────────────────────────────────

    /** 物品注册器 / Item registry */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    /** 菜单类型注册器 / Menu type registry */
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, MODID);
    /** 创造模式选项卡注册器 / Creative tab registry */
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // ── 注册条目 / Registered entries ─────────────────────────────

    public static final Supplier<MenuType<HeavenBootsMenu>> HEAVEN_BOOTS_MENU =
            MENU_TYPES.register("heaven_boots",
                    () -> new MenuType<>(HeavenBootsMenu::new,
                            net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS));

    /** 自动上坡附魔 ResourceKey — 附魔通过 data JSON 注册，此处仅用于查询 */
    private static final ResourceKey<net.minecraft.world.item.enchantment.Enchantment> AUTO_STEP_KEY =
            ResourceKey.create(Registries.ENCHANTMENT,
                    ResourceLocation.fromNamespaceAndPath(MODID, "auto_step"));

    /** 模组专属创造模式选项卡 / Mod creative tab */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> STAIRWAY_HEAVEN_TAB =
            CREATIVE_MODE_TABS.register("stairway_heaven_tab",
                    () -> CreativeModeTab.builder()
                            .title(Component.translatable("itemGroup.stairway_heaven"))
                            .withTabsBefore(CreativeModeTabs.COMBAT)
                            .icon(() -> new ItemStack(ModItems.TAB_ICON.get()))
                            .displayItems((parameters, output) -> {
                                output.accept(ModItems.HEAVEN_BOOTS.get());
                                output.accept(ModItems.UPGRADE_MOLD.get());
                                var holder = parameters.holders()
                                        .lookupOrThrow(Registries.ENCHANTMENT)
                                        .getOrThrow(AUTO_STEP_KEY);
                                ItemStack enchBook = new ItemStack(Items.ENCHANTED_BOOK);
                                enchBook.enchant(holder, 1);
                                output.accept(enchBook);
                            }).build());

    // ── 构造函数 / Constructor ────────────────────────────────────

    public StairwayHeaven(IEventBus modEventBus) {
        // 注册 Mod Bus 内容 / register to MOD bus
        ModItems.ITEMS.register(modEventBus);
        ModDataComponents.DATA_COMPONENTS.register(modEventBus);
        MENU_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        modEventBus.addListener(this::addCreative);

        // 关键：在 FMLCommonSetupEvent 中注册 Game Bus 监听器，而非构造函数中直接注册。
        // 这样能确保在所有 @EventBusSubscriber 静态注册完成之后再注册我们的监听器，
        // 从而在与同样使用 LOWEST 优先级的其它模组（如 simple_enhancement）竞争时后执行。
        //
        // Key: register game bus listeners in FMLCommonSetupEvent (AFTER all
        // @EventBusSubscriber static registrations). This ensures our LOWEST
        // listeners fire AFTER other mods' LOWEST listeners, giving us the
        // final word on step height.
        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // 注册 Game Bus 事件 / register game bus events
        // 这些监听器会在所有 @EventBusSubscriber 静态注册之后才注册到事件总线，
        // 因此面对同样使用 LOWEST 优先级的模组（simple_enhancement）时，
        // 我们的处理器将后执行，保证步高的最终覆盖权。
        NeoForge.EVENT_BUS.addListener(
                EventPriority.LOWEST, false,
                PlayerTickEvent.Post.class,
                StepUpEventHandler::onPlayerTickPost);
        NeoForge.EVENT_BUS.addListener(
                EventPriority.HIGH, false,
                LivingFallEvent.class,
                StepUpEventHandler::onLivingFall);
        NeoForge.EVENT_BUS.addListener(
                EventPriority.NORMAL, false,
                PlayerEvent.Clone.class,
                StepUpEventHandler::onPlayerClone);

        LOGGER.info("Stairway to Heaven loaded! 天堂阶梯已加载！");
    }

    // ── 添加到原版选项卡 / Add to vanilla tabs ─────────────────────

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.HEAVEN_BOOTS.get());
        }
        // 升级模块不出现在原材料选项卡，只出现在模组专属选项卡
        // Upgrade Mold only appears in the mod's creative tab, not in ingredients
    }
}
