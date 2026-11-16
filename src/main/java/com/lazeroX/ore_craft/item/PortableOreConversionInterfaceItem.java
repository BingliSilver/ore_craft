package com.lazeroX.ore_craft.item;

import com.lazeroX.ore_craft.menu.PortableOreConversionInterfaceMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;
import java.util.List;

/**
 * 随身矿质转化接口，只能把可回收物品兑换为 ME 并登记物品学习记录。
 * 矿质容器及其中余额保存在接口物品 NBT，接口不提供 ME 提取能力。
 */
public final class PortableOreConversionInterfaceItem extends Item {
    /**
     * 创建不可堆叠的便携接口。
     *
     * @param properties 注册物品属性
     */
    public PortableOreConversionInterfaceItem(Properties properties) {
        super(properties);
    }

    /**
     * 右键打开绑定当前持有手的菜单，避免切换物品后继续操作已打开界面。
     *
     * @param level 使用者所在世界
     * @param player 使用者
     * @param hand 持有接口的手
     * @return 保留接口物品的交互结果
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            // 菜单只接受服务端创建，物品状态与回收结算始终以服务器数据为准。
            NetworkHooks.openScreen(serverPlayer, new SimpleMenuProvider(
                    (id, inventory, owner) -> new PortableOreConversionInterfaceMenu(id, inventory, hand),
                    Component.translatable("container.ore_craft.portable_ore_conversion_interface")),
                    extra -> extra.writeEnum(hand));
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    /**
     * 显示接口的单向 ME 回收用途。
     *
     * @param stack 当前物品
     * @param context 提示所在世界
     * @param tooltip 待追加提示
     * @param flag 提示显示选项
     */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.ore_craft.portable_conversion.purpose")
                .withStyle(ChatFormatting.GRAY));
    }
}
