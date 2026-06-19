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
 * <b>事件驱动 + 低频安全网 / Event-driven + low-frequency safety net:</b><br>
 * — {@code EntityJoinLevelEvent}: 玩家切维度时即时刷新步高 / instant refresh on dimension change<br>
 * — {@code LivingEquipmentChangeEvent}: 装备变化时即时刷新 / instant refresh on equipment change<br>
 * — {@code PlayerEvent.Clone}: 重生后即时刷新 / instant refresh on respawn<br>
 * — {@code HeavenBootsMenu.saveMoldsToBoots}: GUI 修改后即时刷新 / refresh after GUI changes<br>
 * — 安全网 tick 每 10 tick 扫描一次背包兜底 / safety net scans every 10 ticks (2x/sec)<br>
 * — {@code onLivingFall} 直接读缓存，不重复扫描 / fall handler reads shared cache<br>
 * <p>
 * <b>兼容策略 / Compatibility strategy:</b><br>
 * 安全网保留 NORMAL+LOWEST 优先级拆分，让其他改步高的模组可以覆盖。
 * Safety net keeps NORMAL+LOWEST split — other mods may override at LOWEST.
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

    /** 安全网降频间隔（tick）：每 N 个 tick 扫描一次背包兜底 / Safety net: scan every Nth tick */
    private static final int SAFETY_THROTTLE = 10;

    /** 总加成等级缓存（public 供 HeavenBootsMenu 调用后刷新）/
     *  Total bonus cache (populated by events & safety net, read by LivingFall) */
    private static final Map<UUID, Integer> totalBonusCache = new HashMap<>();

    /** 附魔 Holder 懒缓存 / Lazy-cached enchantment holder */
    private static Holder<Enchantment> autoStepHolder = null;

    /** Curios API 可用性（单次检测）/ Curios API availability (checked once) */
    private static Boolean curiosAvailable = null;

    private StepUpEventHandler() { /* 工具类不可实例化 / utility class */ }

    // ════════════════════════════════════════════════════════════
    //  事件驱动 — 即时刷新 / Event-driven — instant refresh
    // ════════════════════════════════════════════════════════════

    /**
     * 玩家加入世界（登录/切维度）时即时刷新步高。
     * Instant refresh when player joins a level (login / dimension change).
     */
    public static void onPlayerJoinLevel(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        refreshPlayer(player);
    }

    /**
     * 装备变化（盔甲/主手/副手）时即时刷新步高。
     * Instant refresh on equipment change (armor, mainhand, offhand).
     */
    public static void onEquipmentChange(net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;
        refreshPlayer(player);
    }

    /**
     * 从玩家背包、装备槽和 Curios 重新计算加成，并应用到步高属性。
     * 公开方法，供 HeavenBootsMenu 在 GUI 修改模具后直接调用。
     * <p>
     * Recompute bonus from inventory/equipment/Curios and apply to step height.
     * Public — called by HeavenBootsMenu after mold changes in the GUI.
     */
    public static void refreshPlayer(Player player) {
        if (player.level().isClientSide()) return;
        if (player.isSpectator()) return;

        AttributeInstance stepAttr = player.getAttribute(Attributes.STEP_HEIGHT);
        if (stepAttr == null) return;

        int totalBonus = computeTotalBonus(player);
        totalBonusCache.put(player.getUUID(), totalBonus);

        double target = totalBonus > 0 ? (double) totalBonus : VANILLA_STEP;
        if (Math.abs(stepAttr.getBaseValue() - target) > 0.001) {
            stepAttr.setBaseValue(target);
        }
    }

    // ════════════════════════════════════════════════════════════
    //  安全网 — 兜底扫描，保留优先级拆分供其他模组覆盖
    //  Safety net — periodic scan, keeps NORMAL/LOWEST split for mod compat
    // ════════════════════════════════════════════════════════════

    /**
     * NORMAL 优先级：无加成时复位步高至 0.6，给其他模组在 LOWEST 覆盖的空间。
     * Reset step height at NORMAL priority — other mods may override at LOWEST.
     */
    public static void onSafetyReset(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        if (player.isSpectator()) return;
        if (player.tickCount % SAFETY_THROTTLE != 0) return;

        AttributeInstance stepAttr = player.getAttribute(Attributes.STEP_HEIGHT);
        if (stepAttr == null) return;

        int totalBonus = computeTotalBonus(player);
        totalBonusCache.put(player.getUUID(), totalBonus);

        // 无加成才复位 / only reset when no bonus
        if (totalBonus <= 0 && Math.abs(stepAttr.getBaseValue() - VANILLA_STEP) > 0.001) {
            stepAttr.setBaseValue(VANILLA_STEP);
        }
    }

    /**
     * LOWEST 优先级：有加成时锁定步高，压制其他模组的修改。
     * Lock step height at LOWEST when bonus detected — final override.
     */
    public static void onSafetyLock(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        if (player.isSpectator()) return;
        if (player.tickCount % SAFETY_THROTTLE != 0) return;

        AttributeInstance stepAttr = player.getAttribute(Attributes.STEP_HEIGHT);
        if (stepAttr == null) return;

        // 直接读缓存（onSafetyReset 已在同一 tick 计算完毕）
        // Read from cache — onSafetyReset (NORMAL) already computed this tick
        int totalBonus = totalBonusCache.getOrDefault(player.getUUID(), 0);

        if (totalBonus > 0) {
            double target = totalBonus;
            if (Math.abs(stepAttr.getBaseValue() - target) > 0.001) {
                stepAttr.setBaseValue(target);
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  摔落伤害减免 / Fall damage reduction
    // ════════════════════════════════════════════════════════════

    /**
     * 从缓存读取加成值计算摔落减免，不重复扫描背包。
     * 缓存由事件驱动 + 安全网共同刷新，始终保有最新值。
     * <p>
     * Reads bonus from cache (no inventory scan) — cache is kept current by
     * event-driven refresh + safety net.
     */
    public static void onLivingFall(net.neoforged.neoforge.event.entity.living.LivingFallEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        int totalBonus = totalBonusCache.getOrDefault(player.getUUID(), 0);
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

    // ════════════════════════════════════════════════════════════
    //  死亡后恢复解锁数据 + 刷新步高
    //  Restore unlock data and refresh step height after death
    // ════════════════════════════════════════════════════════════

    /**
     * 玩家死亡重生后，将解锁槽位数据从旧玩家复制到新玩家，
     * 并重新计算步高。
     */
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) return;
        var oldData = event.getOriginal().getPersistentData();
        var newData = event.getEntity().getPersistentData();
        if (oldData.contains(UNLOCK_KEY)) {
            newData.put(UNLOCK_KEY, oldData.get(UNLOCK_KEY).copy());
        }

        // 重生后即时刷新步高 / refresh step height on respawn
        if (!event.getEntity().level().isClientSide()) {
            refreshPlayer(event.getEntity());
        }
    }

    // ════════════════════════════════════════════════════════════
    //  工具方法 / Helpers
    // ════════════════════════════════════════════════════════════

    /** 计算总加成 = 靴子最高等级 + 附魔等级 / boot level + enchant level */
    private static int computeTotalBonus(Player player) {
        return findHeavenBootsLevel(player) + getAutoStepEnchantLevel(player);
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
                if (maxLevel >= SLOTS) return maxLevel;
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

    /**
     * 从玩家脚部装备获取自动上坡附魔等级（附魔通过 data JSON 注册）。
     * Get auto-step enchant level from feet (enchantment registered via data JSON).
     */
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
