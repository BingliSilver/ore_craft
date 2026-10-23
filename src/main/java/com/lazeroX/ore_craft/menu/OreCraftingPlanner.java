package com.lazeroX.ore_craft.menu;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraftforge.common.crafting.IShapedRecipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * 合成填充的无副作用规划器，供服务端交易与 JEI 客户端预检共用。
 * 只修改库存副本；完整配方、余额及退回材料的空间全部满足后，菜单才统一提交。
 * 原料匹配交给原版 Ingredient，保留模组标签、NBT 配方和自定义原料的判断规则。
 */
public final class OreCraftingPlanner {
    /** 原版三阶合成区的槽位数，也是库存快照中背包部分的起点。 */
    public static final int GRID_SIZE = 9;
    /** 玩家主背包及快捷栏共三十六格，不读取盔甲、副手或外部存储。 */
    public static final int INVENTORY_SIZE = 36;

    /** 工具类不可实例化，规划状态只在单次请求内存在。 */
    private OreCraftingPlanner() {}

    /**
     * 将配方映射到左上对齐的三阶网格，支持 Forge 的有序配方接口。
     * 没有静态原料声明的动态配方仍能手动合成，但无法仅凭配方 ID 自动填充。
     *
     * @param recipe 服务端配方或客户端同步配方
     * @return 九格原料列表；不适用于三阶网格时返回空列表
     */
    public static List<Ingredient> layout(CraftingRecipe recipe) {
        List<Ingredient> ingredients = recipe.getIngredients();
        if (!recipe.canCraftInDimensions(3, 3) || ingredients.isEmpty() || ingredients.size() > GRID_SIZE) return List.of();
        List<Ingredient> layout = new ArrayList<>(java.util.Collections.nCopies(GRID_SIZE, Ingredient.EMPTY));
        int width = recipe instanceof IShapedRecipe<?> shaped ? shaped.getRecipeWidth() : 3;
        int height = recipe instanceof IShapedRecipe<?> shaped ? shaped.getRecipeHeight() : 3;
        if (width < 1 || width > 3 || height < 1 || height > 3 || ingredients.size() > width * height) return List.of();
        for (int index = 0; index < ingredients.size(); index++) {
            layout.set(index % width + index / width * 3, ingredients.get(index));
        }
        return layout;
    }

    /**
     * 把配方条件对应到玩家取出成品前的实际摆法，供后续补料继续使用标签替代物。
     * 支持有序配方的平移、镜像及无序配方的任意摆放；无法还原时返回空列表。
     *
     * @param recipe 已成功匹配的配方
     * @param template 取出前的九格材料
     * @return 与实际网格对应的九格原料条件，特殊动态配方可能为空
     */
    public static List<Ingredient> refillLayout(CraftingRecipe recipe, List<ItemStack> template) {
        List<Ingredient> canonical = layout(recipe);
        if (canonical.isEmpty()) return List.of();
        if (recipe instanceof IShapedRecipe<?> shaped) {
            int width = shaped.getRecipeWidth();
            int height = shaped.getRecipeHeight();
            for (int x = 0; x <= 3 - width; x++) {
                for (int y = 0; y <= 3 - height; y++) {
                    for (int mirror = 0; mirror < 2; mirror++) {
                        List<Ingredient> shifted = new ArrayList<>(java.util.Collections.nCopies(GRID_SIZE, Ingredient.EMPTY));
                        for (int row = 0; row < height; row++) {
                            for (int column = 0; column < width; column++) {
                                int sourceColumn = mirror == 0 ? column : width - column - 1;
                                shifted.set(column + x + (row + y) * 3, canonical.get(sourceColumn + row * 3));
                            }
                        }
                        if (matchesLayout(shifted, template)) return shifted;
                    }
                }
            }
        } else if (recipe instanceof ShapelessRecipe) {
            // 每个非空格只作为一个来源，不能因某格有一整组就将多个配方条件匹配到同一格。
            List<ItemStack> samples = template.stream().map(stack -> stack.copyWithCount(1)).toList();
            int[] assigned = new int[GRID_SIZE];
            Arrays.fill(assigned, -1);
            for (int index = 0; index < GRID_SIZE; index++) {
                if (!canonical.get(index).isEmpty() && !assign(index, canonical, List.of(), samples,
                        assigned, new boolean[GRID_SIZE])) return List.of();
            }
            List<Ingredient> mapped = new ArrayList<>(java.util.Collections.nCopies(GRID_SIZE, Ingredient.EMPTY));
            for (int index = 0; index < GRID_SIZE; index++) {
                if (assigned[index] >= 0) mapped.set(assigned[index], canonical.get(index));
            }
            if (matchesLayout(mapped, template)) return mapped;
        } else if (matchesLayout(canonical, template)) return canonical;
        return List.of();
    }

    /** 完整校验九格及空格，避免把某个较小配方的局部匹配误当成原摆法。 */
    private static boolean matchesLayout(List<Ingredient> ingredients, List<ItemStack> stacks) {
        for (int index = 0; index < GRID_SIZE; index++) {
            if (!ingredients.get(index).test(stacks.get(index))) return false;
        }
        return true;
    }

    /**
     * 为完整一轮或最多一组原料规划库存变更，失败时不保留任何购买或移动。
     *
     * @param ingredients 固定九格原料，空格使用 Ingredient.EMPTY
     * @param currentGrid 当前合成区的九个真实物品栈
     * @param inventory 背包及快捷栏的三十六格快照
     * @param balance 本次最多可支付的非负 ME
     * @param purchasePrice 单件默认物品的可信价格；未学习或不可转化时返回负数
     * @param maximum 是否继续填充至各原料的堆叠上限
     * @param exact 需要保留状态的九格模板，空格表示不限 NBT；无约束时传空列表
     * @return 完整变更计划，或可本地化的失败原因
     */
    public static Evaluation plan(List<Ingredient> ingredients, List<ItemStack> currentGrid,
                                  List<ItemStack> inventory, long balance, ToLongFunction<ItemStack> purchasePrice,
                                  boolean maximum, List<ItemStack> exact) {
        if (ingredients.size() != GRID_SIZE || ingredients.stream().allMatch(Ingredient::isEmpty)) {
            return new Evaluation(null, "unsupported_recipe");
        }
        List<ItemStack> sources = copy(currentGrid);
        sources.addAll(copy(inventory));
        List<ItemStack> purchases = new ArrayList<>(java.util.Collections.nCopies(GRID_SIZE, ItemStack.EMPTY));
        long[] prices = new long[GRID_SIZE];
        Arrays.fill(prices, -1);
        List<Integer> order = new ArrayList<>();
        for (int cell = 0; cell < GRID_SIZE; cell++) {
            Ingredient ingredient = ingredients.get(cell);
            if (ingredient.isEmpty()) continue;
            order.add(cell);
            // 只购买默认状态，绝不把 JEI 展示的附魔、损伤或容器 NBT 复制成新物品。
            for (ItemStack candidate : ingredient.getItems()) {
                ItemStack plain = new ItemStack(candidate.getItem());
                if (!matches(ingredients, exact, cell, plain)) continue;
                long price = purchasePrice.applyAsLong(plain);
                if (price > 0 && (prices[cell] < 0 || price < prices[cell])) {
                    prices[cell] = price;
                    purchases.set(cell, plain);
                }
            }
        }

        // 先保证不能转化的原料，再让高价原料优先占用实物；增广匹配会挪动先前分配，
        // 因而“任意木板”和“橡木木板”等重叠条件不会简单地抢走彼此唯一的材料。
        order.sort(Comparator.<Integer>comparingInt(cell -> prices[cell] < 0 ? 0 : 1)
                .thenComparing((a, b) -> Long.compare(prices[b], prices[a])));
        int[] assigned = new int[GRID_SIZE];
        Arrays.fill(assigned, -1);
        for (int cell : order) {
            boolean found = assign(cell, ingredients, exact, sources, assigned, new boolean[sources.size()]);
            if (!found && prices[cell] < 0) return new Evaluation(null, "missing_materials");
        }

        List<ItemStack> grid = new ArrayList<>(java.util.Collections.nCopies(GRID_SIZE, ItemStack.EMPTY));
        long cost = 0;
        for (int cell : order) {
            int source = assigned[cell];
            if (source >= 0) {
                grid.set(cell, sources.get(source).copyWithCount(1));
                sources.get(source).shrink(1);
            } else {
                // 使用减法核验预算，价格和总价接近 long 上限时也不会溢出。
                if (prices[cell] > balance - cost) return new Evaluation(null, "insufficient_me");
                cost += prices[cell];
                grid.set(cell, purchases.get(cell).copy());
            }
        }

        // 原网格中未用完的同类堆叠尽量留在合成区，不把一组已有原料退回背包。
        for (int source = 0; source < GRID_SIZE; source++) {
            ItemStack remaining = sources.get(source);
            merge(grid, remaining, false);
        }
        List<ItemStack> remainingInventory = new ArrayList<>(sources.subList(GRID_SIZE, sources.size()));
        for (int source = 0; source < GRID_SIZE; source++) {
            ItemStack remaining = sources.get(source);
            if (!insert(remainingInventory, remaining)) return new Evaluation(null, "inventory_full");
        }

        if (maximum) {
            // 每次只尝试补齐一层；任一格不足时丢弃本层副本，保留已成功规划的完整层。
            for (int target = 2; target <= 64; target++) {
                List<ItemStack> nextGrid = copy(grid);
                List<ItemStack> nextInventory = copy(remainingInventory);
                long nextCost = cost;
                boolean complete = true;
                for (int cell : order) {
                    ItemStack stack = nextGrid.get(cell);
                    if (target > stack.getMaxStackSize()) { complete = false; break; }
                    if (stack.getCount() >= target) continue;
                    if (takeOne(nextInventory, stack)) stack.grow(1);
                    else {
                        long price = purchasePrice.applyAsLong(stack.copyWithCount(1));
                        if (price <= 0 || price > balance - nextCost) { complete = false; break; }
                        nextCost += price;
                        stack.grow(1);
                    }
                }
                if (!complete) break;
                grid = nextGrid;
                remainingInventory = nextInventory;
                cost = nextCost;
            }
        }
        return new Evaluation(new Plan(grid, remainingInventory, cost), "");
    }

    /**
     * 在现有实物中为一个合成格找位置，必要时重新分配其他合成格。
     * 每轮只访问每个来源一次，搜索量受九格与四十五个来源限制。
     */
    private static boolean assign(int cell, List<Ingredient> ingredients, List<ItemStack> exact,
                                  List<ItemStack> sources, int[] assigned, boolean[] visited) {
        for (int source = 0; source < sources.size(); source++) {
            if (visited[source] || !matches(ingredients, exact, cell, sources.get(source))) continue;
            visited[source] = true;
            int used = 0;
            for (int allocation : assigned) if (allocation == source) used++;
            if (used < sources.get(source).getCount()) {
                assigned[cell] = source;
                return true;
            }
            for (int other = 0; other < assigned.length; other++) {
                if (assigned[other] == source && assign(other, ingredients, exact, sources, assigned, visited)) {
                    assigned[cell] = source;
                    return true;
                }
            }
        }
        return false;
    }

    /** 检查配方原料，补料模板额外保留 NBT；不允许将特殊状态替换成默认物品。 */
    private static boolean matches(List<Ingredient> ingredients, List<ItemStack> exact, int cell, ItemStack stack) {
        return !stack.isEmpty() && ingredients.get(cell).test(stack)
                && (exact.isEmpty() || exact.get(cell).isEmpty() || ItemStack.isSameItemSameTags(exact.get(cell), stack));
    }

    /** 从背包副本取一件完全同类的物品；失败不修改任何栈。 */
    private static boolean takeOne(List<ItemStack> inventory, ItemStack target) {
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && ItemStack.isSameItemSameTags(stack, target)) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }

    /** 深复制物品列表，规划期间绝不修改菜单、背包或服务端同步的原栈。 */
    public static List<ItemStack> copy(List<ItemStack> stacks) {
        return new ArrayList<>(stacks.stream().map(ItemStack::copy).toList());
    }

    /** 向库存副本插入真实材料，先合并同类再占空格；返回是否已完全放入。 */
    public static boolean insert(List<ItemStack> inventory, ItemStack stack) {
        merge(inventory, stack, false);
        merge(inventory, stack, true);
        return stack.isEmpty();
    }

    /** 把余量移入同类堆叠或空槽，不超过物品自身及容器的六十四件上限。 */
    private static void merge(List<ItemStack> inventory, ItemStack stack, boolean emptySlots) {
        for (int slot = 0; slot < inventory.size() && !stack.isEmpty(); slot++) {
            ItemStack existing = inventory.get(slot);
            if (emptySlots ? !existing.isEmpty() : existing.isEmpty() || !ItemStack.isSameItemSameTags(existing, stack)) continue;
            int count = Math.min(stack.getCount(), Math.min(64, stack.getMaxStackSize()) - existing.getCount());
            if (count <= 0) continue;
            if (existing.isEmpty()) inventory.set(slot, stack.copyWithCount(count));
            else existing.grow(count);
            stack.shrink(count);
        }
    }

    /** 完整提交内容；grid 为九格真实材料，inventory 为玩家三十六格，cost 为总 ME。 */
    public record Plan(List<ItemStack> grid, List<ItemStack> inventory, long cost) {
        /** 构造已通过预算及空间检查的计划，持有本次规划独占的物品副本。 */
        public Plan {}
    }

    /** 规划结果；plan 为空代表失败，error 是合成台语言键后缀。 */
    public record Evaluation(Plan plan, String error) {
        /** 保存成功计划或失败原因；调用方在 plan 非空时才允许扣款。 */
        public Evaluation {}
    }
}
