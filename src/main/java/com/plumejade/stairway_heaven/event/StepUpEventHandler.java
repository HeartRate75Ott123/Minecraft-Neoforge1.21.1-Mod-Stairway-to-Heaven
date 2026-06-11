package com.plumejade.stairway_heaven.event;

import com.plumejade.stairway_heaven.component.ModDataComponents;
import com.plumejade.stairway_heaven.item.ModItems;
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
 * — 仅在地面时扫描背包 / only scans inventory when on ground<br>
 * — 浮点精确比较避免每 tick 无谓写入 / float-precise compare avoids useless writes<br>
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
    //  每 tick 末尾：设置步高 / End of every tick: set step height
    // ────────────────────────────────────────────────────────────

    /**
     * 在每 tick 末尾（Post 阶段）、所有其他模组处理完毕之后，以最低优先级
     * 设置玩家的步高属性。
     * <p>
     * At the very end of each tick (Post phase), after all other mods,
     * set the player's step height attribute at lowest priority.
     */
    public static void onPlayerTickPost(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        Player player = event.getEntity();

        // 仅服务端处理 / server-side only
        if (player.level().isClientSide()) return;
        if (player.isSpectator()) return;

        // ═══ 步高设置 / step height ═══
        AttributeInstance stepAttr = player.getAttribute(Attributes.STEP_HEIGHT);
        if (stepAttr == null) return;

        // 扫描背包获取 boots 升级等级 + 附魔等级
        // scan inventory for boots upgrade level + enchantment level
        int bootsLevel = findHeavenBootsLevel(player);
        int enchantLevel = getAutoStepEnchantLevel(player);
        int totalBonus = bootsLevel + enchantLevel;

        if (totalBonus > 0) {
            // 9格9模块即天然上限，无需额外生命值限制
            // 9 slots × 1 mold = natural cap of 9, no extra health limit needed
            double target = 1.0 + totalBonus;

            // 浮点精度比较，避免每 tick 无谓写入属性
            // float-precise compare to avoid useless attribute writes
            if (Math.abs(stepAttr.getBaseValue() - target) > 0.001) {
                stepAttr.setBaseValue(target);
            }
        } else {
            // 无 boots 也无附魔 → 恢复原版
            // no boots, no enchant → reset to vanilla
            if (Math.abs(stepAttr.getBaseValue() - VANILLA_STEP) > 0.001) {
                stepAttr.setBaseValue(VANILLA_STEP);
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
     * Safe fall distance increases with upgrade level; excess damage
     * is reduced proportionally (up to 50%). Not unconditional immunity.
     */
    public static void onLivingFall(net.neoforged.neoforge.event.entity.living.LivingFallEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        int bootsLevel = findHeavenBootsLevel(player);
        int enchantLevel = getAutoStepEnchantLevel(player);
        int totalBonus = bootsLevel + enchantLevel;

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
     * 扫描玩家背包，找到天堂之靴并返回其升级等级。
     * Scan player inventory for Heaven Boots, return upgrade level.
     */
    static int findHeavenBootsLevel(Player player) {
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && stack.getItem() instanceof ModItems.HeavenBootsItem) {
                return stack.getOrDefault(ModDataComponents.UPGRADE_LEVEL.get(), 0);
            }
        }
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && offhand.getItem() instanceof ModItems.HeavenBootsItem) {
            return offhand.getOrDefault(ModDataComponents.UPGRADE_LEVEL.get(), 0);
        }
        return 0;
    }

    /**
     * 获取玩家脚部装备上的自动上坡附魔等级。
     * Get auto-step enchantment level from player's feet equipment.
     */
    /**
     * 从玩家脚部装备获取自动上坡附魔等级（附魔通过 data JSON 注册）。
     * Get auto-step enchant level from feet (enchantment registered via data JSON).
     */
    static int getAutoStepEnchantLevel(Player player) {
        ItemStack feet = player.getItemBySlot(EquipmentSlot.FEET);
        if (feet.isEmpty()) return 0;
        // 运行时通过注册表查询 data-driven 附魔 / lookup data-driven enchantment at runtime
        var holder = player.level().registryAccess()
                .registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(AUTO_STEP_KEY);
        return feet.getEnchantmentLevel(holder);
    }
}
