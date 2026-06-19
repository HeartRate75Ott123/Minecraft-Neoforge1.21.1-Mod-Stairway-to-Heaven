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
     * <p>
     * <b>性能优化：</b>先检查 {@code hasModifier}，若修饰器已存在则直接跳过。
     * 修饰器由事件驱动（装备变更/GUI保存/登录）和全量扫描（每5tick）维护，
     * tick 处理器仅在其他模组移除了我们的修饰器时重新添加。
     * 绝大多数 tick 零分配、零调用链。
     * <p>
     * Uses ADD_VALUE modifier — allows other mods' base changes to coexist.
     * <b>Optimization:</b> checks hasModifier first; skips if already present.
     * Modifier is maintained by events + periodic scan; tick handler only
     * re-adds when another mod removed it. Zero allocation in common case.
     */
    public static void onTickApply(net.neoforged.neoforge.event.tick.PlayerTickEvent.Pre event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        if (player.isSpectator()) return;

        int totalBonus = totalBonusCache.getOrDefault(player.getUUID(), 0);
        if (totalBonus <= 0) return;

        AttributeInstance stepAttr = player.getAttribute(Attributes.STEP_HEIGHT);
        if (stepAttr == null) return;

        // 修饰器已在 → 事件/扫描已确保数量新鲜，跳过
        if (stepAttr.hasModifier(STEP_BONUS_ID)) return;

        // 被其他模组移除了 → 重新添加
        stepAttr.addTransientModifier(
                new AttributeModifier(STEP_BONUS_ID, (double) totalBonus,
                        AttributeModifier.Operation.ADD_VALUE));
    }

    // ════════════════════════════════════════════════════════════
    //  安全网扫描（节流）：每 5 tick 全量扫描 + 更新缓存
    //  Safety scan (throttled): full inventory scan every 5 ticks, refreshes cache
    // ════════════════════════════════════════════════════════════

    /**
     * 每 5 tick 全量扫描背包，缓存值未变时不触发修饰器更新。
     * <p>
     * <b>性能优化：</b>比较新旧缓存值，相同时跳过 {@code updateCacheAndModifier}
     * 和修饰器操作。在大多数 tick 内，玩家的天堂之靴配置不变，扫描仅检查
     * 背包（不可避免）但跳过所有后续调用。
     * <p>
     * Full scan every 5 ticks. Skips modifier update if cache hasn't changed.
     * <b>Optimization:</b> compares old and new bonus; skips all subsequent
     * modifier operations when unchanged (common case).
     */
    public static void onSafetyScan(net.neoforged.neoforge.event.tick.PlayerTickEvent.Pre event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        if (player.isSpectator()) return;
        if (player.tickCount % SAFETY_THROTTLE != 0) return;

        int totalBonus = computeTotalBonus(player);
        int prev = totalBonusCache.getOrDefault(player.getUUID(), 0);
        if (prev == totalBonus) return; // 未变化，跳过修饰器更新

        updateCacheAndModifier(player, totalBonus);
    }

    // ════════════════════════════════════════════════════════════
    //  Post 安全网（节流）：兼容 simple_enhancement 等模组
    //  Post safety net (throttled): compatibility with simple_enhancement etc.
    // ════════════════════════════════════════════════════════════

    /**
     * Post LOWEST 安全网（每 2 tick）: 确保修饰器存在，与 Pre 形成双重保障。
     * <p>
     * <b>性能优化：</b>与 {@code onTickApply} 相同，修饰器已存在时跳过，
     * 零分配。仅在其他模组移除了修饰器时重新添加。
     * <p>
     * Safety net at Post LOWEST (every 2 ticks): same hasModifier optimization —
     * zero allocation when modifier is already active (almost always).
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

        if (stepAttr.hasModifier(STEP_BONUS_ID)) return;

        stepAttr.addTransientModifier(
                new AttributeModifier(STEP_BONUS_ID, (double) totalBonus,
                        AttributeModifier.Operation.ADD_VALUE));
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
     * 应用或移除我们的 ADD_VALUE 修饰器。
     * <p>
     * <b>注意：</b>{@code addTransientModifier} 按 ResourceLocation ID 替换，
     * 无需先 {@code removeModifier}（两者指向同一内部映射表）。
     * tolBonus 为 0 时仅移除。
     * <p>
     * Applies or removes our ADD_VALUE modifier on STEP_HEIGHT.
     * addTransientModifier replaces by ResourceLocation ID internally,
     * so no prior removeModifier is needed.
     */
    private static void applyStepModifier(AttributeInstance stepAttr, int totalBonus) {
        // 先移除旧修饰器防止重复添加（addTransientModifier 内部可能按列表而非映射存储）
        stepAttr.removeModifier(STEP_BONUS_ID);
        if (totalBonus > 0) {
            stepAttr.addTransientModifier(
                    new AttributeModifier(STEP_BONUS_ID, (double) totalBonus,
                            AttributeModifier.Operation.ADD_VALUE));
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
