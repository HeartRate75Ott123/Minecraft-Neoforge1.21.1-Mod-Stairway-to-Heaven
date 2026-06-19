package com.plumejade.stairway_heaven.event;

import com.plumejade.stairway_heaven.component.ModDataComponents;
import com.plumejade.stairway_heaven.item.ModItems;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
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
 * <b>事件驱动 + 即时修正 + 安全网 / Event-driven + instant correction + safety net:</b><br>
 * — {@code PlayerTickEvent.Pre LOWEST}（无节流）: 每 tick 从缓存读取加成并写回步高，
 *   确保移动引擎读取的是我们的值。不扫描背包，极轻量。<br>
 *   Reads bonus from cache every tick at Start LOWEST — ensures movement sees our value.
 *   No inventory scan, extremely lightweight.<br>
 * — {@code PlayerTickEvent.Pre NORMAL}（每 5 tick）: 全量扫描背包更新缓存。<br>
 *   Full inventory scan every 5 ticks to refresh the cache.<br>
 * — 事件驱动即时刷新 / Event-driven instant refresh on equipment/join/clone/GUI changes.<br>
 * — {@code onLivingFall} 直接读缓存，不重复扫描 / fall handler reads shared cache.
 * <p>
 * <b>兼容策略 / Compatibility strategy:</b><br>
 * 所有修正均在 Start LOWEST 阶段完成（移动引擎读取属性之前），
 * 确保即使其他模组在其它阶段修改步高，也不影响上坡体验。
 * All corrections happen at Start LOWEST (before movement reads the attribute),
 * so other mods modifying step height at other event phases have no effect.
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
    //  即时修正（无节流）：每 tick Start LOWEST 从缓存写回步高
    //  Instant correction (unthrottled): applies cached bonus every tick at Start LOWEST
    // ════════════════════════════════════════════════════════════

    /**
     * 每 tick 在 Start LOWEST 阶段从缓存读取加成值并写入步高属性，
     * 确保移动引擎在该 tick 读取到的是我们的值。
     * 不扫描背包，仅一次 {@code HashMap.getOrDefault} + 一次条件 {@code setBaseValue}。
     * <p>
     * Runs every tick at Start LOWEST — reads the cached bonus and sets step height
     * BEFORE movement processing. No inventory scan, just a cache lookup + conditional set.
     */
    public static void onTickApply(net.neoforged.neoforge.event.tick.PlayerTickEvent.Pre event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        if (player.isSpectator()) return;

        AttributeInstance stepAttr = player.getAttribute(Attributes.STEP_HEIGHT);
        if (stepAttr == null) return;

        int totalBonus = totalBonusCache.getOrDefault(player.getUUID(), 0);
        double target = totalBonus > 0 ? (double) totalBonus : VANILLA_STEP;

        if (Math.abs(stepAttr.getBaseValue() - target) > 0.001) {
            stepAttr.setBaseValue(target);
        }
    }

    // ════════════════════════════════════════════════════════════
    //  安全网扫描（节流）：每 5 tick 全量扫描 + 更新缓存
    //  Safety scan (throttled): full inventory scan every 5 ticks, refreshes cache
    // ════════════════════════════════════════════════════════════

    /**
     * 每 5 tick 全量扫描一次背包，更新缓存。NORMAL 优先级给其他模组覆盖空间。
     * 但即使被覆盖，{@code onTickApply}（LOWEST）也会在同一 tick 的 Start
     * 阶段把我们缓存的值写回步高。
     * <p>
     * Full scan every 5 ticks to refresh cache at NORMAL priority.
     * Even if overridden by other mods, {@code onTickApply} (LOWEST)
     * re-applies our cached value in the same Start phase.
     */
    public static void onSafetyScan(net.neoforged.neoforge.event.tick.PlayerTickEvent.Pre event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        if (player.isSpectator()) return;
        if (player.tickCount % SAFETY_THROTTLE != 0) return;

        int totalBonus = computeTotalBonus(player);
        totalBonusCache.put(player.getUUID(), totalBonus);
    }

    // ════════════════════════════════════════════════════════════
    //  Post 安全网（节流）：兼容 simple_enhancement 等模组
    //  Post safety net (throttled): compatibility with simple_enhancement etc.
    // ════════════════════════════════════════════════════════════

    /**
     * Post LOWEST 安全网（每 2 tick）: 从缓存读取加成并重新写回步高。
     * 确保即使其他模组在 Post 阶段修改步高属性，我们的值也会在
     * 当前 tick 结束时恢复。配合 {@code onTickApply} (Start LOWEST)
     * 一起工作：Start 保证移动引擎读到我们的值，Post 保证跨 tick 兼容性。
     * <p>
     * Safety net at Post LOWEST (every 2 ticks): re-applies cached step height
     * after other mods (e.g. simple_enhancement) may have modified it at Post.
     * Works alongside {@code onTickApply} (Start LOWEST): Start ensures movement
     * sees our value; Post ensures cross-tick compatibility.
     */
    public static void onPostLock(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        if (player.isSpectator()) return;
        if (player.tickCount % 2 != 0) return;

        AttributeInstance stepAttr = player.getAttribute(Attributes.STEP_HEIGHT);
        if (stepAttr == null) return;

        int totalBonus = totalBonusCache.getOrDefault(player.getUUID(), 0);
        double target = totalBonus > 0 ? (double) totalBonus : VANILLA_STEP;

        if (Math.abs(stepAttr.getBaseValue() - target) > 0.001) {
            stepAttr.setBaseValue(target);
        }
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
     */
    public static void refreshPlayer(Player player) {
        if (player.level().isClientSide()) return;
        if (player.isSpectator()) return;

        int totalBonus = computeTotalBonus(player);
        totalBonusCache.put(player.getUUID(), totalBonus);

        // 主动写回步高，不必等 onTickApply 下个 tick 再处理
        AttributeInstance stepAttr = player.getAttribute(Attributes.STEP_HEIGHT);
        if (stepAttr == null) return;
        double target = totalBonus > 0 ? (double) totalBonus : VANILLA_STEP;
        if (Math.abs(stepAttr.getBaseValue() - target) > 0.001) {
            stepAttr.setBaseValue(target);
        }
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

    private static int computeTotalBonus(Player player) {
        return findHeavenBootsLevel(player) + getAutoStepEnchantLevel(player);
    }

    static int findHeavenBootsLevel(Player player) {
        int maxLevel = 0;

        for (ItemStack stack : player.getInventory().items) {
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

        for (ItemStack stack : player.getInventory().armor) {
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
