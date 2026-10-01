package com.confusecat.testmod;

import cn.breezeth.ordertocook.block.entity.RefrigeratorBlockEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 给 Order To Cook 的冰箱挂上 Forge 的 ITEM_HANDLER 能力，
 * 让原版漏斗（以及任何用 IItemHandler 的自动化）能把物品送进冰箱。
 *
 * 为什么这样就够了：Forge 的漏斗逻辑走
 *   net.minecraftforge.items.VanillaInventoryCodeHooks.insertHook(HopperBlockEntity)
 * 它用 getItemHandler(Level, x, y, z, Direction) 查询目标方块实体的 ITEM_HANDLER，
 * 查到就调 insertItem(...)。所以不需要改漏斗，只要这个能力挂得上。
 *
 * 事件泛型用 BlockEntity（而不是具体子类）：BlockEntity 的构造函数调用的是
 * CapabilityProvider.&lt;init&gt;(BlockEntity.class)（javap 实测），
 * 因此 Forge 用 BlockEntity.class 构造 AttachCapabilitiesEvent，
 * 监听 AttachCapabilitiesEvent&lt;BlockEntity&gt; 一定能收到。
 */
@Mod.EventBusSubscriber(modid = testMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RefrigeratorCapability {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation ID =
            new ResourceLocation(testMod.MODID, "refrigerator_item_handler");

    /** 反射失败只报一次，免得每个冰箱刷一次屏。 */
    private static boolean failureLogged = false;

    private RefrigeratorCapability() {
    }

    @SubscribeEvent
    public static void attachCapabilities(AttachCapabilitiesEvent<BlockEntity> event) {
        if (!(event.getObject() instanceof RefrigeratorBlockEntity refrigerator)) {
            return;
        }

        // LazyOptional 的供给器是惰性的：真正的反射发生在漏斗第一次查询能力时。
        // 这样即使 OTC 改了内部结构，也只会退化成"漏斗传不进去"，而不是让游戏崩掉。
        LazyOptional<IItemHandler> optional = LazyOptional.of(() -> createHandler(refrigerator));

        event.addCapability(ID, new ICapabilityProvider() {
            @Override
            public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
                if (capability == ForgeCapabilities.ITEM_HANDLER) {
                    return optional.cast();
                }
                return LazyOptional.empty();
            }
        });

        event.addListener(optional::invalidate);
    }

    private static IItemHandler createHandler(RefrigeratorBlockEntity refrigerator) {
        try {
            return new RefrigeratorItemHandler(refrigerator);
        } catch (Throwable t) {
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.error("[testmod] 无法包装 Order To Cook 冰箱的物品栏，"
                        + "漏斗将无法向冰箱传输物品。请把这条日志连同 OTC 版本一起反馈。", t);
            }
            return EMPTY_HANDLER;
        }
    }

    /** 反射失败时的兜底：0 格，表现为"冰箱已满"，游戏不会崩。 */
    private static final IItemHandler EMPTY_HANDLER = new IItemHandler() {
        @Override
        public int getSlots() {
            return 0;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 0;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }
    };
}
