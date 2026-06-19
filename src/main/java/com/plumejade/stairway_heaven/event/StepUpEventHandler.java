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
 * <b>兼容策略 / Compatibility strategy:</b><br>
 * 不使用 Mixin，而是在 {@code PlayerTickEvent.Post} 阶段以 {@code EventPriority.LOWEST}
 * 优先级运行，在所有其他模组（如拔刀剑）处理完步高之后做最终覆盖。
 * No Mixin — runs at Post + LOWEST priority to override step height
 * after all other mods (e.g. SlashBlade) have finished their changes.
 * <p>
 * <b>性能优化 / Performance:</b><br>
 * — 降频：每 5 tick 执行一次，减少 80% 触发频率 / throttled to every 5 ticks (80% reduction)<br>
 * — 双处理器间缓存：NORMAL 计算后 LOWEST 共享，避免同一 tick 重复扫描<br>
 *   cross-handler cache: NORMAL computes, LOWEST reuses — no duplicate scan per tick<br>
 * — {@code onLivingFall} 直接读缓存，不独立扫描 / fall handler reads shared cache<br>
 * — 浮点精确比较避免无谓写入 / float-precise compare avoids useless attribute writes<br>
 * — 提前退出条件放在最前面 / early-exit guards at the top
 */
public final class StepUpEventHandler {

    /** 原版默认步高 / Vanilla default step height */
    private static final double VANILLA_STEP = 0.6;

    /** 自动上坡附魔的 ResourceKey — 附魔是 data JSON 注册的，运行时通过注册表查询 */
    private static final ResourceKey<Enchantment> AUTO_STEP_KEY =
            ResourceKey.create(Registries.ENCHANTMENT,
                    ResourceLocation.fromNamespaceAndPath("stairway_heaven", "auto_step"));

    /** 解锁数据持久键 / unlock data persistent key (public for HeavenBootsMenu) */
    public static final String UNLOCK_KEY = "stairway_heaven_unlocked";
    private static final int SLOTS = 9;

    /** 降频间隔（tick）：每 N 个 tick 执行一次逻辑 / Throttle: process every Nth tick */
    private static final int THROTTLE = 5;

    /** 双处理器间缓存：NORMAL 计算后 LOWEST 直接读取 / Tick-level bonus cache shared between handlers */
    private static final Map<UUID, Integer> bonusCache = new HashMap<>();

    /** 附魔 Holder 懒缓存 / Lazy-cached enchantment holder */
    private static Holder<Enchantment> autoStepHolder = null;

    /** Curios API 可用性（单次检测）/ Curios API availability (checked once) */
    private static Boolean curiosAvailable = null;

    private StepUpEventHandler() { /* 工具类不可实例化 / utility class */ }

    // ────────────────────────────────────────────────────────────
    //  死亡后恢复解锁数据 / Restore unlock data after death
    // ────────────────────────────────────────────────────────────

    /**
     * 玩家死亡重生后，将解锁槽位数据从旧玩家复制到新玩家。
     * 避免死亡后所有槽位重新锁定。
     * <p>
     * Copy unlocked slot data from dead player to respawned player
     * so slots don't re-lock after death.
     */
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) return;
        var oldData = event.getOriginal().getPersistentData();
        var newData = event.getEntity().getPersistentData();
        if (oldData.contains(UNLOCK_KEY)) {
            newData.put(UNLOCK_KEY, oldData.get(UNLOCK_KEY).copy());
        }
    }

    // ────────────────────────────────────────────────────────────
    //  步高重置：正常优先级，供其他模组覆盖 / Step reset at NORMAL priority
    // ────────────────────────────────────────────────────────────

    /**
     * 在正常优先级重置步高为 0.6。不使用 LOWEST，以便其他模组（如 simple_enhancement）
     * 可以在 LOWEST 覆盖这个重置值。
     * <p>
     * Reset step height at NORMAL priority — allows other mods to
     * override the reset at LOWEST if they want custom step height.
     */
    public static void onPlayerTickPostReset(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        if (player.isSpectator()) return;
        // 降频：每 THROTTLE 个 tick 执行一次 / throttle: only process every THROTTLE ticks
        if (player.tickCount % THROTTLE != 0) return;

        AttributeInstance stepAttr = player.getAttribute(Attributes.STEP_HEIGHT);
        if (stepAttr == null) return;

        int totalBonus = getTotalBonus(player);

        // 只有无加成时才重置 / only reset when no bonus
        if (totalBonus <= 0 && Math.abs(stepAttr.getBaseValue() - VANILLA_STEP) > 0.001) {
            stepAttr.setBaseValue(VANILLA_STEP);
        }
    }

    // ────────────────────────────────────────────────────────────
    //  步高锁定：最低优先级，压制其他模组 / Step override at LOWEST priority
    // ────────────────────────────────────────────────────────────

    /**
     * 以最低优先级锁定步高为 boots+附魔的加成值，覆盖其他模组的修改。
     * 仅在检测到加成时生效，无加成时不改写，留空给其他模组发挥。
     * <p>
     * Lock step height at LOWEST when boots/enchant detected.
     * When no bonus, does nothing — gives other mods room to set
     * their own step height (e.g. simple_enhancement giant mode).
     */
    public static void onPlayerTickPostLock(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        if (player.isSpectator()) return;
        // 降频：每 THROTTLE 个 tick 执行一次 / throttle: only process every THROTTLE ticks
        if (player.tickCount % THROTTLE != 0) return;

        AttributeInstance stepAttr = player.getAttribute(Attributes.STEP_HEIGHT);
        if (stepAttr == null) return;

        int totalBonus = getTotalBonus(player);

        // 仅在有加成时覆盖，无加成时跳过（让重置或其他模组决定）
        // only override when there's a bonus — leave it alone otherwise
        if (totalBonus > 0) {
            double target = totalBonus;
            if (Math.abs(stepAttr.getBaseValue() - target) > 0.001) {
                stepAttr.setBaseValue(target);
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    //  摔落伤害减免 / Fall damage reduction
    // ────────────────────────────────────────────────────────────

    /**
     * 随 boots 升级等级提高安全摔落高度，超出部分按比例减免。
     * 不是无条件免疫，等级越高保护越强（最高减免50%）。
     * <p>
     * <b>性能说明 / Performance note:</b><br>
     * 直接使用 tick 级缓存 {@code bonusCache} 中的加成值，不再重复扫描背包。
     * tick handler 每 5 tick 已自动刷新缓存，避免 {@code LivingFallEvent}
     * 和 {@code PlayerTickEvent} 在同一 tick 内三次扫描背包。<br>
     * Uses the tick-level {@code bonusCache} instead of re-scanning inventory —
     * the cache is refreshed by tick handlers every 5 ticks, preventing triple
     * inventory scans on the same tick.
     * <p>
     * Safe fall distance increases with upgrade level; excess damage
     * is reduced proportionally (up to 50%). Not unconditional immunity.
     */
    public static void onLivingFall(net.neoforged.neoforge.event.entity.living.LivingFallEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        // 使用 tick 级缓存避免重复扫描 / read from tick cache — no redundant scan
        int totalBonus = bonusCache.getOrDefault(player.getUUID(), 0);
        if (totalBonus <= 0) return;

        // 安全高度从3格提升 / safe distance raised from vanilla 3
        float safeDistance = 3.0F + totalBonus;
        float original = event.getDistance();

        if (original <= safeDistance) {
            // 完全免疫 / fully immune
            event.setCanceled(true);
        } else {
            // 超出部分按比例减免（最高50%）
            // reduce excess proportionally (max 50%)
            float excess = original - safeDistance;
            float reduction = Math.min(0.5F, (totalBonus * 2) / 20.0F);
            float finalDist = safeDistance + excess * (1.0F - reduction);
            event.setDistance(finalDist);
        }
    }

    // ────────────────────────────────────────────────────────────
    //  工具方法 / Helpers
    // ────────────────────────────────────────────────────────────

    /**
     * 获取当前玩家的总加成等级（靴子+附魔），带 tick 级缓存和降频。
     * NORMAL 处理器首次调用时计算并缓存，LOWEST 处理器直接读取，
     * 避免同一 tick 内重复扫描背包。
     * <p>
     * Get total bonus (boots + enchant) with tick-level caching.
     * NORMAL handler computes & caches; LOWEST handler reads the cache,
     * avoiding a redundant inventory scan within the same tick.
     */
    private static int getTotalBonus(Player player) {
        int tickNow = player.tickCount;
        // 刚到降频触发点 → 重新计算 / throttle tick → recompute
        if (tickNow % THROTTLE == 0) {
            int total = findHeavenBootsLevel(player) + getAutoStepEnchantLevel(player);
            bonusCache.put(player.getUUID(), total);
            return total;
        }
        // 非降频 tick → 读缓存 / non-throttle tick → read cache
        return bonusCache.getOrDefault(player.getUUID(), 0);
    }

    /**
     * 扫描玩家背包、装备槽和 Curios 足部槽中所有天堂之靴，返回最高升级等级。
     * Scan all Heaven Boots (inventory, armor slots, Curios), return highest upgrade level.
     */
    static int findHeavenBootsLevel(Player player) {
        int maxLevel = 0;

        // 主背包 + 快捷栏 / main inventory + hotbar (36 slots)
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && stack.getItem() instanceof ModItems.HeavenBootsItem) {
                int level = stack.getOrDefault(ModDataComponents.UPGRADE_LEVEL.get(), 0);
                if (level > maxLevel) maxLevel = level;
                if (maxLevel >= SLOTS) return maxLevel; // 已达上限，提前退出 / already at cap
            }
        }

        // 副手 / offhand
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && offhand.getItem() instanceof ModItems.HeavenBootsItem) {
            int level = offhand.getOrDefault(ModDataComponents.UPGRADE_LEVEL.get(), 0);
            if (level > maxLevel) maxLevel = level;
            if (maxLevel >= SLOTS) return maxLevel;
        }

        // 装备槽（兼容其他模组将靴放入装甲槽）/ armor slots (for mod compatibility)
        for (ItemStack stack : player.getInventory().armor) {
            if (!stack.isEmpty() && stack.getItem() instanceof ModItems.HeavenBootsItem) {
                int level = stack.getOrDefault(ModDataComponents.UPGRADE_LEVEL.get(), 0);
                if (level > maxLevel) maxLevel = level;
                if (maxLevel >= SLOTS) return maxLevel;
            }
        }

        // Curios 足部饰品槽 / Curios feet slot
        int curiosLevel = findBootsInCurios(player);
        if (curiosLevel > maxLevel) maxLevel = curiosLevel;
        return maxLevel;
    }

    /** 从 Curios 足部槽位查找天堂之靴 / Find Heaven Boots in Curios feet slot */
    private static int findBootsInCurios(Player player) {
        // 首次运行时一次性检测 Curios API 是否存在
        // One-time check: detect Curios API availability at first run
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
            // 此时 Curios 确认可用 / Curios is confirmed available
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
            curiosAvailable = false; // 运行时发现 Curios 不可用 / runtime — Curios unavailable
            return 0;
        }
    }

    /**
     * 从玩家脚部装备获取自动上坡附魔等级（附魔通过 data JSON 注册）。
     * Get auto-step enchant level from feet (enchantment registered via data JSON).
     */
    static int getAutoStepEnchantLevel(Player player) {
        ItemStack feet = player.getItemBySlot(EquipmentSlot.FEET);
        if (feet.isEmpty()) return 0;
        // Holder 在游戏生命周期内不变，首次使用时懒加载
        // Holder is stable for the game lifetime — lazy-init on first use
        if (autoStepHolder == null) {
            autoStepHolder = player.level().registryAccess()
                    .registryOrThrow(Registries.ENCHANTMENT)
                    .getHolderOrThrow(AUTO_STEP_KEY);
        }
        return feet.getEnchantmentLevel(autoStepHolder);
    }
}
