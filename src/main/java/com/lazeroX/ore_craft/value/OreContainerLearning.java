package com.lazeroX.ore_craft.value;

import com.lazeroX.ore_craft.block.entity.OreConversionMachineBlockEntity;
import com.lazeroX.ore_craft.block.entity.OreConverterBlockEntity;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 学习宝典的服务端批量扫描流程，读取目标容器或玩家背包并将可学习的物品类型登记到共享账户。
 * 支持原版容器、双箱、玩家自己的末影箱及暴露物品能力的模组容器；不转移或消耗样本。
 */
public final class OreContainerLearning {
    /** 纯静态工具，不持有容器引用或跨游戏刻的临时状态。 */
    private OreContainerLearning() {}

    /**
     * 处理一次潜行右键：识别完整库存、校验访问权限、去重学习并发送一次结果。
     * 未识别为容器时返回 false，调用方可继续打开宝典；已识别但被锁定时消费交互。
     *
     * @param player 正在手持宝典的服务端玩家
     * @param context 包含目标位置和世界的方块交互上下文
     * @return 已识别容器或拒绝越界请求时为 true，无容器时为 false
     */
    public static boolean tryLearn(ServerPlayer player, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        // 批量学习不依赖客户端提交的槽位或物品 ID，且仅接受正常方块交互范围。
        if (!player.canInteractWithBlock(pos, 4.0)) return true;
        BlockState state = level.getBlockState(pos);
        BlockEntity entity = level.getBlockEntity(pos);
        Container container = null;
        // 双箱的两个实体都需校验锁和战利品访问条件，再读取合并后的 54 格库存。
        List<BlockEntity> checkedEntities = new ArrayList<>();
        if (entity != null) checkedEntities.add(entity);
        if (state.getBlock() instanceof ChestBlock chest) {
            // 学习只需读取库存，不要求箱盖能够打开；仍然遵守两个半箱各自的锁。
            container = ChestBlock.getContainer(chest, state, level, pos, true);
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockEntity other = level.getBlockEntity(pos.relative(ChestBlock.getConnectedDirection(state)));
                if (other != null) checkedEntities.add(other);
            }
        } else if (state.getBlock() instanceof EnderChestBlock) {
            // 末影箱内容属于操作者本人，方块实体本身不保存玩家的物品。
            container = player.getEnderChestInventory();
        } else if (entity instanceof Container inventory) {
            container = inventory;
        }

        List<IItemHandler> handlers = container == null ? itemHandlers(level, pos) : List.of();
        if (container == null && handlers.isEmpty()) return false;
        for (BlockEntity checked : checkedEntities) {
            if (!canRead(player, checked)) return true;
        }
        if (container != null && !container.stillValid(player)) return true;
        // 全部权限检查通过后才生成尚未展开的战利品，沿用玩家运气等原版生成规则。
        for (BlockEntity checked : checkedEntities) {
            if (checked instanceof RandomizableContainerBlockEntity lootContainer) {
                lootContainer.unpackLootTable(player);
            }
        }

        // 按库存扫描顺序保留唯一类型，多个栈、多个方向或双箱重复物品只学习一次。
        Set<ResourceLocation> candidates = new LinkedHashSet<>();
        if (container != null) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                collect(container.getItem(slot), candidates);
            }
        } else {
            for (IItemHandler handler : handlers) {
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    collect(handler.getStackInSlot(slot), candidates);
                }
            }
        }
        learn(player, candidates, "message.ore_craft.learning.container_empty");
        return true;
    }

    /**
     * 潜行右键空气时扫描玩家自己的主背包和快捷栏，按容器批量学习规则登记物品类型。
     * 与宝典菜单一致只读取背包 36 格，不扫描防具、副手、鼠标持有物或物品内部库存。
     * 样本数量、附魔、耐久、ME 等组件均保持原样，仅学习对应物品的默认类型。
     *
     * @param player 学习记录所属的服务端玩家，调用方须确认其正在潜行使用宝典
     */
    public static void learnInventory(ServerPlayer player) {
        // items 是主背包及快捷栏的真实物品集合，按槽位顺序去重，不移动或改写原始物品栈。
        Set<ResourceLocation> candidates = new LinkedHashSet<>();
        for (ItemStack sample : player.getInventory().items) {
            collect(sample, candidates);
        }
        // 复用登记、容量限制、一次性网络同步及结果统计，仅按扫描来源区分空库存提示。
        learn(player, candidates, "message.ore_craft.learning.inventory_empty");
    }

    /**
     * 校验原版容器锁及本模组机器的放置者权限，拒绝操作时复用已有提示。
     *
     * @param player 尝试学习的玩家
     * @param entity 库存所属方块实体
     * @return 玩家可以读取该库存时为 true
     */
    private static boolean canRead(ServerPlayer player, BlockEntity entity) {
        if (entity instanceof BaseContainerBlockEntity container && !container.canOpen(player)) return false;
        String denied = null;
        if (entity instanceof OreConverterBlockEntity converter && !player.getUUID().equals(converter.owner())) {
            denied = "message.ore_craft.converter.not_owner";
        } else if (entity instanceof OreConversionMachineBlockEntity machine && !player.getUUID().equals(machine.owner())) {
            denied = "message.ore_craft.machine.not_owner";
        }
        if (denied == null) return true;
        player.displayClientMessage(Component.translatable(denied), true);
        return false;
    }

    /**
     * 收集非原版容器的无方向和六个面的物品视图，兼容输入、输出分面暴露的机器。
     * 只读取槽位，不调用模拟或真实的插入、提取；相同处理器引用仅扫描一次。
     *
     * @param level 目标所在世界
     * @param pos 目标方块位置
     * @return 当前方块可读取的物品处理器，未提供能力时为空
     */
    private static List<IItemHandler> itemHandlers(Level level, BlockPos pos) {
        List<IItemHandler> handlers = new ArrayList<>();
        Set<IItemHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        IItemHandler unsided = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (unsided != null && seen.add(unsided)) handlers.add(unsided);
        for (Direction side : Direction.values()) {
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
            if (handler != null && seen.add(handler)) handlers.add(handler);
        }
        return handlers;
    }

    /**
     * 按单件学习规则检查默认物品状态，将可学习类型加入去重集合。
     * 附魔、耐久或容器 ME 等组件不进入学习目录，原栈始终保持原样。
     *
     * @param sample 库存中的原始样本，只读
     * @param candidates 本次可学习物品的唯一 ID 集合
     */
    private static void collect(ItemStack sample, Set<ResourceLocation> candidates) {
        if (sample.isEmpty()) return;
        if (OreConversionPrices.canLearn(new ItemStack(sample.getItem()))) {
            candidates.add(BuiltInRegistries.ITEM.getKey(sample.getItem()));
        }
    }

    /**
     * 一次性登记所有新增类型，达到共享上限后继续统计重复和未能新增的类型。
     * 容器与背包扫描共用此流程，最后仅同步一次完整状态，避免逐槽触发网络刷新。
     *
     * @param player 学习记录所属玩家
     * @param candidates 已校验且去重的可学习物品 ID
     * @param emptyMessage 没有可学习物品时的翻译键，按容器或玩家背包区分提示来源
     */
    private static void learn(ServerPlayer player, Set<ResourceLocation> candidates, String emptyMessage) {
        if (candidates.isEmpty()) {
            player.displayClientMessage(Component.translatable(emptyMessage), true);
            return;
        }
        OreConversionSavedData data = OreConversionSavedData.get(player);
        // 只复制一次账户集合，避免批量学习时反复复制最多 2048 条学习记录。
        Set<ResourceLocation> known = new HashSet<>(data.account(player).learned());
        int learned = 0;
        int alreadyKnown = 0;
        int limited = 0;
        for (ResourceLocation id : candidates) {
            if (known.contains(id)) {
                alreadyKnown++;
            } else if (known.size() >= OreConversionSavedData.MAX_LEARNED) {
                limited++;
            } else if (data.learn(player, id)) {
                known.add(id);
                learned++;
            }
        }
        OreConversionNetwork.sendState(player, -1);
        // 潜行右键不打开菜单，使用快捷栏上方的短提示反馈本次学习结果。
        String key = limited > 0 ? "message.ore_craft.learning.container_limit"
                : "message.ore_craft.learning.container_learned";
        player.displayClientMessage(Component.translatable(key, learned, alreadyKnown, limited), true);
    }
}
