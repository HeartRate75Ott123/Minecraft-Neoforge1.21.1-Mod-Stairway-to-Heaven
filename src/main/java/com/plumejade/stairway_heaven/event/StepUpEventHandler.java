package com.plumejade.stairway_heaven.event;

import com.plumejade.stairway_heaven.component.ModDataComponents;
import com.plumejade.stairway_heaven.item.ModItems;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 处理天堂之靴的自动上坡、摔落减免，以及自动上坡附魔效果。
 * Handles Heaven Boots auto-step, fall damage reduction, and auto-step enchantment.
 * <p>
 * <b>修饰器方案 / Modifier-based approach:</b><br>
 * 使用 {@code AttributeModifier.Operation.ADD_VALUE} 而非 {@code setBaseValue}，
 * 修饰器与步高基底独立，simple_enhancement 等模组对基底的修改不受影响。<br>
 * 无天堂之靴加成的玩家完全不干预，让其他模组自由工作。
 * <p>
 * <b>架构 / Architecture:</b><br>
 * — {@code PlayerTickEvent.Pre LOWEST}（无节流）: 每 tick 确保修饰器生效（仅对有缓存玩家）<br>
 * — {@code PlayerTickEvent.Pre NORMAL}（每 5 tick）: 扫描背包 + 更新缓存 + 同步修饰器<br>
 * — {@code PlayerTickEvent.Post LOWEST}（每 2 tick）: 兼容安全网，重新应用修饰器<br>
 * — 事件驱动 / Event-driven: join/equip/clone/GUI 即时刷新<br>
 * — {@code onLivingFall} 直接读缓存，不重复扫描
 * <p>
 * <b>兼容策略 / Compatibility:</b><br>
 * Uses ADD_VALUE modifier instead of setBaseValue — other mods modifying the base
 * (e.g. simple_enhancement giant potion) coexist with our bonus.
 * Players without Heaven Boots bonus are never touched.
 */
public final class StepUpEventHandler {

    /** 原版默认步高 / Vanilla default step height */
    private static final double VANILLA_STEP = 0.6;

    /** 自动上坡附魔的 ResourceKey */
    private static final ResourceKey<Enchantment> AUTO_STEP_KEY =
            ResourceKey.create(Registries.ENCHANTMENT,
                    ResourceLocation.fromNamespaceAndPath("stairway_heaven", "auto_step"));

    /** 解锁数据持久键 (public for HeavenBootsMenu) */
    public static final String UNLOCK_KEY = "stairway_heaven_unlocked";
    private static final int SLOTS = 9;

    /** 属性修饰器 ID（ResourceLocation 方式，兼容 1.21 属性系统） */
    private static final ResourceLocation STEP_BONUS_ID =
            ResourceLocation.fromNamespaceAndPath("stairway_heaven", "step_height_bonus");

    /** 安全网扫描间隔 (tick) / Safety scan interval */
    private static final int SAFETY_THROTTLE = 5;

    /** 总加成缓存 / Total bonus cache (bonus from boots + enchant) */
    private static final Map<UUID, Integer> totalBonusCache = new HashMap<>();

    /** 附魔 Holder 懒缓存 */
    private static Holder<Enchantment> autoStepHolder = null;

    /** Curios API 可用性（单次检测） */
    private static Boolean curiosAvailable = null;

    private StepUpEventHandler() {}

    // ════════════════════════════════════════════════════════════
    //  即时修正（无节流）：每 tick Pre LOWEST 确保修饰器生效
    //  Instant correction (unthrottled): ensures modifier is active every tick at Pre LOWEST
    // ════════════════════════════════════════════════════════════

    /**
     * 每 tick 在 Pre LOWEST 阶段确保步高属性修饰器生效。
     * 使用 {@code AttributeModifier.Operation.ADD_VALUE} 而非 {@code setBaseValue}，
     * 这样 simple_enhancement 的巨人药剂等修改基底的效果不会被冲掉，
     * 两者加成叠加共存。
     * <p>
     * 无天堂之靴加成的玩家完全跳过，不干预步高属性。
     * <p>
     * Uses ADD_VALUE modifier instead of setBaseValue — allows other mods'
     * base value changes (e.g. giant potion) to coexist with our bonus.
     * Players without Heaven Boots bonus are skipped entirely.
     */
    public static void onTickApply(net.neoforged.neoforge.event.tick.PlayerTickEvent.Pre event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        if (player.isSpectator()) return;

        int totalBonus = totalBonusCache.getOrDefault(player.getUUID(), 0);
        if (totalBonus <= 0) return;

        AttributeInstance stepAttr = player.getAttribute(Attributes.STEP_HEIGHT);
        if (stepAttr == null) return;

        applyStepModifier(stepAttr, totalBonus);
    }

    // ════════════════════════════════════════════════════════════
    //  安全网扫描（节流）：每 5 tick 全量扫描 + 更新缓存
    //  Safety scan (throttled): full inventory scan every 5 ticks, refreshes cache
    // ════════════════════════════════════════════════════════════

    /**
     * 每 5 tick 全量扫描背包 + 更新缓存 + 应用/移除修饰器。
     * NORMAL 优先级，不影响其他模组的基底修改（simple_enhancement 巨人药剂等）。
     * <p>
     * 无加成时清理缓存和修饰器，确保 {@code onTickApply} 和 {@code onPostLock}
     * 不做无效查询。
     * <p>
     * Full scan every 5 ticks: refreshes cache, applies/removes modifier.
     * Uses ADD_VALUE modifier so other mods' base changes are preserved.
     */
    public static void onSafetyScan(net.neoforged.neoforge.event.tick.PlayerTickEvent.Pre event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        if (player.isSpectator()) return;
        if (player.tickCount % SAFETY_THROTTLE != 0) return;

        int totalBonus = computeTotalBonus(player);
        updateCacheAndModifier(player, totalBonus);
    }

    // ════════════════════════════════════════════════════════════
    //  Post 安全网（节流）：兼容 simple_enhancement 等模组
    //  Post safety net (throttled): compatibility with simple_enhancement etc.
    // ════════════════════════════════════════════════════════════

    /**
     * Post LOWEST 安全网（每 2 tick）: 使用修饰器而非 setBaseValue 确保兼容。
     * 即使其他模组在 Post 修改基底（如 simple_enhancement 的巨人药剂），
     * 我们的 ADD_VALUE 修饰器不受影响，两个加成共存。
     * <p>
     * 无天堂之靴加成的玩家完全跳过。
     * <p>
     * Safety net at Post LOWEST (every 2 ticks): uses ADD_VALUE modifier,
     * so even if other mods change the base value at Post (e.g. giant potion),
     * our modifier remains and both effects stack.
     */
    public static void onPostLock(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        if (player.isSpectator()) return;
        if (player.tickCount % 2 != 0) return;

        int totalBonus = totalBonusCache.getOrDefault(player.getUUID(), 0);
        if (totalBonus <= 0) return;

        AttributeInstance stepAttr = player.getAttribute(Attributes.STEP_HEIGHT);
        if (stepAttr == null) return;

        applyStepModifier(stepAttr, totalBonus);
    }

    // ════════════════════════════════════════════════════════════
    //  事件驱动 — 即时刷新 / Event-driven — instant refresh
    // ════════════════════════════════════════════════════════════
    // ════════════════════════════════════════════════════════════

    /**
     * 登录 / 切维度时即时刷新。
     */
    public static void onPlayerJoinLevel(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        refreshPlayer(player);
    }

    /**
     * 装备变化时即时刷新。
     */
    public static void onEquipmentChange(net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;
        refreshPlayer(player);
    }

    /**
     * 公开方法：重新计算加成并刷新步高（供 HeavenBootsMenu GUI 调用）。
     * 使用修饰器而非 setBaseValue，兼容其他模组的基底修改。
     */
    public static void refreshPlayer(Player player) {
        if (player.level().isClientSide()) return;
        if (player.isSpectator()) return;

        int totalBonus = computeTotalBonus(player);
        updateCacheAndModifier(player, totalBonus);
    }

    // ════════════════════════════════════════════════════════════
    //  摔落伤害减免 / Fall damage reduction
    // ════════════════════════════════════════════════════════════

    /**
     * 从缓存读取加成值计算摔落减免，不重复扫描背包。
     */
    public static void onLivingFall(net.neoforged.neoforge.event.entity.living.LivingFallEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        int totalBonus = totalBonusCache.getOrDefault(player.getUUID(), 0);
        if (totalBonus <= 0) return;

        float safeDistance = 3.0F + totalBonus;
        float original = event.getDistance();

        if (original <= safeDistance) {
            event.setCanceled(true);
        } else {
            float excess = original - safeDistance;
            float reduction = Math.min(0.5F, (totalBonus * 2) / 20.0F);
            float finalDist = safeDistance + excess * (1.0F - reduction);
            event.setDistance(finalDist);
        }
    }

    // ════════════════════════════════════════════════════════════
    //  死亡后恢复解锁数据 + 刷新步高
    // ════════════════════════════════════════════════════════════

    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) return;
        var oldData = event.getOriginal().getPersistentData();
        var newData = event.getEntity().getPersistentData();
        if (oldData.contains(UNLOCK_KEY)) {
            newData.put(UNLOCK_KEY, oldData.get(UNLOCK_KEY).copy());
        }
        if (!event.getEntity().level().isClientSide()) {
            refreshPlayer(event.getEntity());
        }
    }

    // ════════════════════════════════════════════════════════════
    //  工具方法 / Helpers
    // ════════════════════════════════════════════════════════════

    /**
     * 核心方法：对步高属性应用/移除我们的 ADD_VALUE 修饰器。
     * 与 {@code setBaseValue} 不同，修饰器与基底独立，
     * simple_enhancement 的基底改动（如巨人药剂）不受影响，
     * 两个加成叠加共存。
     * <p>
     * Core method: applies or removes our ADD_VALUE modifier on STEP_HEIGHT.
     * Unlike setBaseValue, this modifier is independent from the base value,
     * so other mods' base changes (e.g. giant potion) coexist with ours.
     */
    private static void applyStepModifier(AttributeInstance stepAttr, int totalBonus) {
        if (totalBonus > 0) {
            // 先移除旧修饰器防止叠加，再添加新修饰器
            stepAttr.removeModifier(STEP_BONUS_ID);
            stepAttr.addTransientModifier(
                    new AttributeModifier(STEP_BONUS_ID, (double) totalBonus,
                            AttributeModifier.Operation.ADD_VALUE));
        } else {
            stepAttr.removeModifier(STEP_BONUS_ID);
        }
    }

    /**
     * 更新缓存并同步修饰器：在事件处理器（安全网扫描/装备变更等）中调用。
     * 缓存更新后立即应用/移除修饰器，不必等 onTickApply 下次 tick。
     */
    private static void updateCacheAndModifier(Player player, int totalBonus) {
        if (totalBonus > 0) {
            totalBonusCache.put(player.getUUID(), totalBonus);
        } else {
            totalBonusCache.remove(player.getUUID());
        }

        AttributeInstance stepAttr = player.getAttribute(Attributes.STEP_HEIGHT);
        if (stepAttr != null) {
            applyStepModifier(stepAttr, totalBonus);
        }
    }

    private static int computeTotalBonus(Player player) {
        return findHeavenBootsLevel(player) + getAutoStepEnchantLevel(player);
    }

    static int findHeavenBootsLevel(Player player) {
        int maxLevel = 0;

        // 优先扫护甲槽（靴子最常在这里），可快速提前返回
        for (ItemStack stack : player.getInventory().armor) {
            if (!stack.isEmpty() && stack.getItem() instanceof ModItems.HeavenBootsItem) {
                int level = stack.getOrDefault(ModDataComponents.UPGRADE_LEVEL.get(), 0);
                if (level > maxLevel) maxLevel = level;
                if (maxLevel >= SLOTS) return maxLevel;
            }
        }

        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && offhand.getItem() instanceof ModItems.HeavenBootsItem) {
            int level = offhand.getOrDefault(ModDataComponents.UPGRADE_LEVEL.get(), 0);
            if (level > maxLevel) maxLevel = level;
            if (maxLevel >= SLOTS) return maxLevel;
        }

        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && stack.getItem() instanceof ModItems.HeavenBootsItem) {
                int level = stack.getOrDefault(ModDataComponents.UPGRADE_LEVEL.get(), 0);
                if (level > maxLevel) maxLevel = level;
                if (maxLevel >= SLOTS) return maxLevel;
            }
        }

        int curiosLevel = findBootsInCurios(player);
        if (curiosLevel > maxLevel) maxLevel = curiosLevel;
        return maxLevel;
    }

    private static int findBootsInCurios(Player player) {
        if (curiosAvailable == null) {
            try {
                Class.forName("top.theillusivec4.curios.api.CuriosApi");
                curiosAvailable = true;
            } catch (ClassNotFoundException e) {
                curiosAvailable = false;
            }
        }
        if (!curiosAvailable) return 0;

        try {
            return top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player)
                    .map(handler -> {
                        int max = 0;
                        var slots = handler.getCurios().get("feet");
                        if (slots != null) {
                            for (int i = 0; i < slots.getSlots(); i++) {
                                ItemStack stack = slots.getStacks().getStackInSlot(i);
                                if (!stack.isEmpty() && stack.getItem() instanceof ModItems.HeavenBootsItem) {
                                    int level = stack.getOrDefault(ModDataComponents.UPGRADE_LEVEL.get(), 0);
                                    if (level > max) max = level;
                                }
                            }
                        }
                        return max;
                    }).orElse(0);
        } catch (NoClassDefFoundError e) {
            curiosAvailable = false;
            return 0;
        }
    }

    static int getAutoStepEnchantLevel(Player player) {
        ItemStack feet = player.getItemBySlot(EquipmentSlot.FEET);
        if (feet.isEmpty()) return 0;
        if (autoStepHolder == null) {
            autoStepHolder = player.level().registryAccess()
                    .registryOrThrow(Registries.ENCHANTMENT)
                    .getHolderOrThrow(AUTO_STEP_KEY);
        }
        return feet.getEnchantmentLevel(autoStepHolder);
    }
}
