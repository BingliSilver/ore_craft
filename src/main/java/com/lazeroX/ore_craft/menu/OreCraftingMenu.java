package com.lazeroX.ore_craft.menu;

import com.lazeroX.ore_craft.block.entity.OreCraftingTableBlockEntity;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.register.ModBlocks;
import com.lazeroX.ore_craft.register.ModMenus;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * 在转化桌交易菜单上增加三阶合成区，学习、回收、提现和容器结算由父类处理。
 * 每位玩家拥有独立的临时合成区；基础款只共享持久供能槽，关闭界面时返还材料。
 * 自动补料偏好按玩家保存在当前世界中，基础款与 Plus 款共用，重新打开时恢复。
 * 填充与补料均先在副本上规划，再由服务端一次扣费并提交真实库存。
 */
public final class OreCraftingMenu extends OreConversionMenu {
    /** 清空合成网格的原版菜单按钮编号。 */
    public static final int CLEAR_GRID = 0;
    /** 切换合成后自动补料的按钮编号，未保存偏好时默认开启。 */
    public static final int TOGGLE_REFILL = 1;
    /** 当前交互玩家，用于世界、背包和账户归属检查。 */
    private final Player owner;
    /** 合成台的固定坐标，限制玩家只能在八格内交互。 */
    private final BlockPos position;
    /** 基础款实际绑定的方块实体；Plus 款及客户端为空。 */
    private final OreCraftingTableBlockEntity table;
    /** 玩家独占的九格真实合成材料。 */
    private final CraftingContainer grid = new TransientCraftingContainer(this, 3, 3);
    /** 原版结果库存，负责配方解锁和成就记录。 */
    private final ResultContainer result = new ResultContainer();
    /** 九格材料在菜单槽位列表中的起点，紧接父类交易槽。 */
    private final int gridStart;
    /** 成品槽的菜单索引，位于九格材料之后。 */
    private final int outputSlot;
    /** 阻止原版逐格消耗或批量提交时重复匹配不完整配方。 */
    private boolean updating;
    /** 从玩家存档恢复并同步至客户端的自动补料开关，0 为关闭、1 为开启。 */
    private final DataSlot refill = DataSlot.standalone();
    /** 最近一次有效配方；取走产物时保留其原料规则以继续补料。 */
    private CraftingRecipe currentRecipe;

    /**
     * 创建带完整转化交易槽的合成菜单。
     *
     * @param id 菜单同步编号
     * @param inventory 玩家背包
     * @param pos 方块坐标
     * @param containerBacked 基础款为 true，Plus 款为 false
     */
    public OreCraftingMenu(int id, Inventory inventory, BlockPos pos, boolean containerBacked) {
        super(containerBacked ? ModMenus.ORE_CRAFTING_MENU.get() : ModMenus.ORE_CRAFTING_PLUS_MENU.get(),
                id, inventory, pos, containerBacked, true);
        owner = inventory.player;
        position = pos.immutable();
        table = containerBacked && !owner.level().isClientSide()
                && owner.level().getBlockEntity(pos) instanceof OreCraftingTableBlockEntity entity ? entity : null;
        gridStart = slots.size();
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                addSlot(new Slot(grid, column + row * 3, 30 + column * 18, 64 + row * 18));
            }
        }
        outputSlot = slots.size();
        addSlot(new ReplenishingResultSlot(inventory.player));
        // 客户端先使用默认值，再通过 DataSlot 接收服务端从玩家存档读取的真实偏好。
        refill.set(1);
        if (owner instanceof ServerPlayer player) {
            refill.set(OreConversionSavedData.get(player).account(player).craftingAutoRefill() ? 1 : 0);
        }
        addDataSlot(refill);
    }

    /** 检查方块版本、交互距离和持久库存身份，阻止替换方块后继续操作旧容器。 */
    @Override
    public boolean stillValid(Player player) {
        return owner != null && owner.level().getBlockState(position).is(containerBacked()
                ? ModBlocks.ORE_CRAFTING_TABLE.get() : ModBlocks.ORE_CRAFTING_TABLE_PLUS.get())
                && player.distanceToSqr(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5) <= 64
                && (!containerBacked() || owner.level().isClientSide() || table != null
                && owner.level().getBlockEntity(position) == table);
    }

    /** 真实材料改变后只在服务端匹配配方；批量操作结束时统一刷新。 */
    @Override
    public void slotsChanged(Container container) {
        if (!updating && owner instanceof ServerPlayer player) updateResult(player);
    }

    /**
     * 按原版工作台规则产生结果预览，并显式同步结果槽，保留限制作业配方规则。
     * 客户端不能凭配方预览创建真实产物，取走结果仍通过 ResultSlot 消耗材料。
     */
    private void updateResult(ServerPlayer player) {
        Level level = player.level();
        currentRecipe = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, level).orElse(null);
        ItemStack output = ItemStack.EMPTY;
        if (currentRecipe != null && result.setRecipeUsed(level, player, currentRecipe)) {
            ItemStack assembled = currentRecipe.assemble(grid, level.registryAccess());
            if (assembled.isItemEnabled(level.enabledFeatures())) output = assembled;
        }
        result.setItem(0, output);
        setRemoteSlot(outputSlot, output);
        player.connection.send(new ClientboundContainerSetSlotPacket(containerId, incrementStateId(), outputSlot, output));
    }

    /**
     * 处理 JEI 的配方 ID 请求；服务端重新读取配方、学习记录、物品价格与全部库存。
     * 最大填充最多购买各原料一组，所有输入不足或背包无法容纳旧材料时整笔拒绝。
     *
     * @param player 发起填充的玩家
     * @param recipeId 服务端配方注册 ID
     * @param maximum 是否请求批量填充
     */
    public void transferRecipe(ServerPlayer player, ResourceLocation recipeId, boolean maximum) {
        if (!validRequest(player) || !getCarried().isEmpty()) return;
        var recipe = player.level().getRecipeManager().byKey(recipeId).orElse(null);
        if (!(recipe instanceof CraftingRecipe crafting)) { feedback(player, "unsupported_recipe"); return; }
        OreCraftingPlanner.Evaluation evaluation = preview(crafting, tradeBalance(player),
                stack -> purchasePrice(player, stack), maximum);
        if (evaluation.plan() == null) { feedback(player, evaluation.error()); return; }
        if (!matchesPlan(player, crafting, evaluation.plan())) { feedback(player, "unsupported_recipe"); return; }
        commit(player, evaluation.plan());
    }

    /**
     * 根据当前库存规划配方，供服务端填充和 JEI 的只读检查共用。
     *
     * @param recipe 同步的合成配方
     * @param balance 当前结算来源余额
     * @param prices 当前侧可信的学习及价格查询
     * @param maximum 是否尽量填满
     * @return 成功的库存计划或本地化失败原因
     */
    public OreCraftingPlanner.Evaluation preview(CraftingRecipe recipe, long balance,
                                                ToLongFunction<ItemStack> prices, boolean maximum) {
        if (!getCarried().isEmpty()) return new OreCraftingPlanner.Evaluation(null, "cursor_occupied");
        return OreCraftingPlanner.plan(OreCraftingPlanner.layout(recipe), grid.getItems(), inventorySnapshot(),
                balance, prices, maximum, List.of());
    }

    /** 查询单个默认物品的合法购买价格；特殊 NBT、未学习、无效价格一律不能凭 ME 生成。 */
    private long purchasePrice(ServerPlayer player, ItemStack stack) {
        ItemStack plain = new ItemStack(stack.getItem());
        if (!ItemStack.isSameItemSameTags(stack, plain) || !OreConversionPrices.canLearn(plain)
                || !OreConversionSavedData.get(player).account(player).knows(BuiltInRegistries.ITEM.getKey(stack.getItem()))) return -1;
        return OreConversionPrices.price(stack.getItem()).orElse(-1);
    }

    /** 从父类前三十六个槽位复制背包，保持与提交时完全相同的槽位顺序。 */
    private List<ItemStack> inventorySnapshot() {
        List<ItemStack> inventory = new ArrayList<>(36);
        for (int index = 0; index < 36; index++) inventory.add(getSlot(index).getItem().copy());
        return inventory;
    }

    /** 用独立网格校验实际配方与产物，阻止自定义配方只声明原料但无法匹配时扣款。 */
    private boolean matchesPlan(ServerPlayer player, CraftingRecipe recipe, OreCraftingPlanner.Plan plan) {
        NonNullList<ItemStack> stacks = NonNullList.withSize(9, ItemStack.EMPTY);
        for (int index = 0; index < 9; index++) stacks.set(index, plan.grid().get(index).copy());
        CraftingContainer probe = new TransientCraftingContainer(this, 3, 3, stacks);
        // 模组配方可能要求先 matches 再 assemble，不能用尚未合法的材料调用其产物计算。
        if (!recipe.matches(probe, player.level())) return false;
        ItemStack output = recipe.assemble(probe, player.level().registryAccess());
        return !output.isEmpty() && output.isItemEnabled(player.level().enabledFeatures());
    }

    /** 在服务端同一线程先扣款再一次写回全部库存；零费用的纯背包合成无需供能容器。 */
    private boolean commit(ServerPlayer player, OreCraftingPlanner.Plan plan) {
        if (plan.cost() > 0 && !debitTrade(player, plan.cost())) return false;
        updating = true;
        try {
            for (int index = 0; index < 36; index++) getSlot(index).set(plan.inventory().get(index));
            for (int index = 0; index < 9; index++) grid.setItem(index, plan.grid().get(index));
            player.getInventory().setChanged();
        } finally {
            updating = false;
        }
        updateResult(player);
        broadcastChanges();
        if (plan.cost() > 0) sync(player);
        return true;
    }

    /**
     * 取走成品后恢复一轮原料，保留镜像和偏移，同时允许背包中的标签替代原料。
     * 返还的桶、催化剂和损伤工具先经原版 ResultSlot 处理，再参与新的库存规划。
     */
    private void replenish(ServerPlayer player, CraftingRecipe recipe, List<ItemStack> template) {
        if (!autoRefill() || recipe == null || !validRequest(player)) return;
        List<Ingredient> ingredients = OreCraftingPlanner.refillLayout(recipe, template);
        List<ItemStack> exact;
        if (ingredients.isEmpty()) {
            // 没有静态原料的动态配方只按真实样本补料，不能推测它对 NBT 或位置的额外要求。
            ingredients = template.stream().map(stack -> stack.isEmpty() ? Ingredient.EMPTY : Ingredient.of(stack)).toList();
            exact = template;
        } else {
            // 带状态的样本锁定 NBT；普通材料可使用配方标签内的其他背包物品，避免过早扣 ME。
            exact = template.stream().map(stack -> OreConversionPrices.isPlain(stack) ? ItemStack.EMPTY : stack).toList();
        }
        OreCraftingPlanner.Evaluation evaluation = OreCraftingPlanner.plan(ingredients, grid.getItems(), inventorySnapshot(),
                tradeBalance(player), stack -> purchasePrice(player, stack), false, exact);
        if (evaluation.plan() != null && matchesPlan(player, recipe, evaluation.plan())) commit(player, evaluation.plan());
    }

    /**
     * 保留背包 Shift 点击的转化行为；材料格 Shift 点击退回背包，成品最多合成一组。
     * 返回空栈终止原版外层快速移动循环，防止自动补料令一次操作持续耗尽全部 ME。
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (!(player instanceof ServerPlayer serverPlayer) || !validRequest(serverPlayer)
                || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        if (index < gridStart) return super.quickMoveStack(player, index);
        if (index == outputSlot) {
            craftStack(serverPlayer);
            return ItemStack.EMPTY;
        }
        Slot slot = getSlot(index);
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (stack.isEmpty() || !moveItemStackTo(stack, 0, 36, false)) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        slotsChanged(grid);
        return original;
    }

    /** 在背包能完整接收一轮产物时才合成，物品或配方改变、材料不足时立即结束。 */
    private void craftStack(ServerPlayer player) {
        Slot slot = getSlot(outputSlot);
        ItemStack first = slot.getItem().copy();
        if (first.isEmpty()) return;
        int limit = Math.min(64, first.getMaxStackSize());
        for (int made = 0; made < limit;) {
            ItemStack output = slot.getItem();
            if (output.isEmpty() || !ItemStack.isSameItemSameTags(first, output)
                    || output.getCount() > limit - made) break;
            List<ItemStack> inventory = inventorySnapshot();
            if (!OreCraftingPlanner.insert(inventory, output.copy())) break;
            ItemStack original = output.copy();
            // 与原版工作台一致，在物品进入背包前触发物品级回调；地图等物品在这里生成 NBT。
            output.getItem().onCraftedBy(output, player.level(), player);
            if (!OreCraftingPlanner.insert(inventorySnapshot(), output.copy())) break;
            moveItemStackTo(output, 0, 36, true);
            slot.onQuickCraft(output, original);
            slot.set(ItemStack.EMPTY);
            slot.onTake(player, output);
            made += original.getCount();
        }
        broadcastChanges();
    }

    /** 禁止双击收集把预览成品当成普通库存，避免绕过 ResultSlot 的原料消耗。 */
    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.container != result && super.canTakeItemForPickAll(stack, slot);
    }

    /** 校验清空和补料开关请求；切换时保存玩家偏好，清空失败时保持整个网格不动。 */
    @Override
    public boolean clickMenuButton(Player player, int button) {
        if (!(player instanceof ServerPlayer serverPlayer) || !validRequest(serverPlayer)) return false;
        if (button == TOGGLE_REFILL) {
            boolean enabled = !autoRefill();
            // 在服务端确认点击时写入偏好，避免关闭菜单或退出游戏后丢失本次选择。
            OreConversionSavedData.get(serverPlayer).setCraftingAutoRefill(serverPlayer, enabled);
            refill.set(enabled ? 1 : 0);
            return true;
        }
        if (button != CLEAR_GRID) return false;
        List<ItemStack> inventory = inventorySnapshot();
        for (ItemStack stack : grid.getItems()) {
            if (!OreCraftingPlanner.insert(inventory, stack.copy())) { feedback(serverPlayer, "inventory_full"); return false; }
        }
        commit(serverPlayer, new OreCraftingPlanner.Plan(
                new ArrayList<>(java.util.Collections.nCopies(9, ItemStack.EMPTY)), inventory, 0));
        return true;
    }

    /** 关闭时沿用转化桌充提口返还规则，并返还全部合成材料；基础款供能容器留在方块内。 */
    @Override
    public void removed(Player player) {
        updating = true;
        try {
            super.removed(player);
            if (!player.level().isClientSide()) clearContainer(player, grid);
            result.clearContent();
        } finally {
            updating = false;
        }
    }

    /** 返回当前自动补料开关，供界面显示服务端确认后的状态。 */
    public boolean autoRefill() { return refill.get() != 0; }
    /** 返回结果槽索引；基础款和 Plus 的交易槽数量不同，不能写死。 */
    public int outputSlot() { return outputSlot; }

    /** 向合成台界面发送简短操作结果，沿用共享网络通道及菜单编号校验。 */
    private void feedback(ServerPlayer player, String key) {
        OreConversionNetwork.sendStatus(player, containerId, "craft_" + key, 0);
    }

    /**
     * 原版结果槽的补料扩展，保留合成事件、统计、配方解锁及容器返还处理。
     * 在材料消耗与返还全部完成后才补料，防止中间状态产生错误配方或重复扣款。
     */
    private final class ReplenishingResultSlot extends ResultSlot {
        /** 将结果槽放在三阶网格右侧，使用本菜单真实的原料和结果库存。 */
        private ReplenishingResultSlot(Player player) {
            super(player, grid, result, 0, 156, 82);
        }

        /** 拒绝过期菜单或客户端伪造的取出操作，真实合成始终由服务端处理。 */
        @Override
        public boolean mayPickup(Player player) {
            return stillValid(player) && super.mayPickup(player);
        }

        /** 保留完整的原版结算，再以取出前网格为模板尝试补齐下一轮。 */
        @Override
        public void onTake(Player player, ItemStack stack) {
            if (!(player instanceof ServerPlayer serverPlayer)) return;
            CraftingRecipe recipe = currentRecipe;
            List<ItemStack> template = OreCraftingPlanner.copy(grid.getItems());
            updating = true;
            try {
                super.onTake(player, stack);
            } finally {
                updating = false;
            }
            replenish(serverPlayer, recipe, template);
            updateResult(serverPlayer);
        }
    }
}
