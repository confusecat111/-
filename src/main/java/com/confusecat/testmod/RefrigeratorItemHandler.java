package com.confusecat.testmod;

import cn.breezeth.ordertocook.block.entity.RefrigeratorBlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把 Order To Cook 冰箱内部的两个 Container 包装成 Forge 的 IItemHandler，
 * 这样原版漏斗就能把物品送进冰箱。
 *
 * 漏斗为什么能用：Forge 的
 * net.minecraftforge.items.VanillaInventoryCodeHooks.insertHook(HopperBlockEntity)
 * 会通过 getItemHandler(Level, x, y, z, Direction) 查询目标方块实体的 ITEM_HANDLER 能力，
 * 查到后调用 insertItem(...) 推物品。所以只需要把能力挂上去，不用改漏斗。
 *
 * OTC 1.3.6 的 RefrigeratorBlockEntity 结构（用 javap 实测确认，不是猜的）：
 *   private final NonNullList&lt;ItemStack&gt; upperInventory;  // 27 格，不是 Container
 *   private final NonNullList&lt;ItemStack&gt; lowerInventory;  // 54 格，不是 Container
 *   private final RefrigeratorContainer upperContainer;   // 27 格，implements Container
 *   private final RefrigeratorContainer lowerContainer;   // 54 格，implements Container
 *
 * 行为（同样 javap 确认）：
 *   canPlaceItem(i, stack) -&gt; RefrigeratorBlockEntity.isAllowedFoodStorage(stack)
 *   getMaxStackSize()      -&gt; 128
 *   setItem / removeItem 内部会 setChanged() 并调用 RefrigeratorBlockEntity.sync()，
 *   所以我们全程只通过 Container 接口读写，OTC 自己的同步逻辑会自动保留。
 *
 * 为什么这里可以用反射：RefrigeratorBlockEntity 是 final，无法继承；
 * 但 ModLauncher 用 ModuleDescriptor.newOpenModule(...) 把每个 mod 定义为 open module，
 * 因此对其它 mod 的私有成员 setAccessible 是允许的，不需要 --add-opens。
 */
public class RefrigeratorItemHandler implements IItemHandler {

    /** 优先按字段名定位；OTC 一旦改名就回退到按类型扫描，避免直接失效。 */
    private static final String UPPER_FIELD = "upperContainer";
    private static final String LOWER_FIELD = "lowerContainer";

    private final Container upper;
    private final Container lower;

    public RefrigeratorItemHandler(RefrigeratorBlockEntity refrigerator) {
        Container namedUpper = findContainerField(refrigerator, UPPER_FIELD);
        Container namedLower = findContainerField(refrigerator, LOWER_FIELD);

        if (namedUpper != null && namedLower != null
                && namedUpper != namedLower
                && namedUpper.getContainerSize() != namedLower.getContainerSize()) {
            // 按格子数决定上下层，不依赖"字段名"与"尺寸"的固定对应关系
            if (namedUpper.getContainerSize() < namedLower.getContainerSize()) {
                this.upper = namedUpper;
                this.lower = namedLower;
            } else {
                this.upper = namedLower;
                this.lower = namedUpper;
            }
            return;
        }

        // 回退路径：扫描所有 Container 类型的字段（含父类）
        List<Container> found = new ArrayList<>();

        for (Field field : allFields(RefrigeratorBlockEntity.class)) {
            if (!Container.class.isAssignableFrom(field.getType())) {
                continue;
            }

            if (!field.trySetAccessible()) {
                throw new IllegalStateException(
                        "无法访问 OTC 冰箱字段 " + field + "（模块未被开放，或 OTC 结构已改变）");
            }

            try {
                if (field.get(refrigerator) instanceof Container container) {
                    found.add(container);
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("读取 OTC 冰箱字段 " + field + " 失败", e);
            }
        }

        if (found.size() != 2) {
            throw new IllegalStateException(
                    "OTC 冰箱内部 Container 数量异常，找到 " + found.size() + " 个（期望 2 个）；OTC 结构可能已改变");
        }

        found.sort(Comparator.comparingInt(Container::getContainerSize));
        this.upper = found.get(0);
        this.lower = found.get(1);
    }

    // ------------------------------------------------------------- 反射工具

    private static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static Container findContainerField(Object target, String name) {
        for (Field field : allFields(RefrigeratorBlockEntity.class)) {
            if (!field.getName().equals(name) || !Container.class.isAssignableFrom(field.getType())) {
                continue;
            }
            if (!field.trySetAccessible()) {
                return null;
            }
            try {
                return field.get(target) instanceof Container container ? container : null;
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ IItemHandler

    @Override
    public int getSlots() {
        return upper.getContainerSize() + lower.getContainerSize();
    }

    private Container containerFor(int slot) {
        if (slot < 0 || slot >= getSlots()) {
            throw new IndexOutOfBoundsException("无效的冰箱槽位: " + slot + "（共 " + getSlots() + " 格）");
        }
        return slot < upper.getContainerSize() ? upper : lower;
    }

    private int localSlot(int slot) {
        return slot < upper.getContainerSize() ? slot : slot - upper.getContainerSize();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return containerFor(slot).getItem(localSlot(slot)).copy();
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        Container container = containerFor(slot);
        int local = localSlot(slot);

        // 让 OTC 自己判断这个物品能不能进冰箱（isAllowedFoodStorage）
        if (!container.canPlaceItem(local, stack)) {
            return stack.copy();
        }

        ItemStack existing = container.getItem(local);
        int limit = Math.min(getSlotLimit(slot), stack.getMaxStackSize());

        if (!existing.isEmpty()) {
            // 不同物品、或 NBT 不同，不能合并
            if (!ItemStack.isSameItemSameTags(existing, stack)) {
                return stack.copy();
            }

            int space = limit - existing.getCount();
            if (space <= 0) {
                return stack.copy();
            }

            int moved = Math.min(space, stack.getCount());

            if (!simulate) {
                ItemStack merged = existing.copy();
                merged.grow(moved);
                // 通过 OTC 的 Container#setItem 写入，保留它的 setChanged()/sync()
                container.setItem(local, merged);
            }

            return remainder(stack, moved);
        }

        int moved = Math.min(limit, stack.getCount());

        if (!simulate) {
            ItemStack placed = stack.copy();
            placed.setCount(moved);
            container.setItem(local, placed);
        }

        return remainder(stack, moved);
    }

    private static ItemStack remainder(ItemStack original, int inserted) {
        int left = original.getCount() - inserted;
        if (left <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack result = original.copy();
        result.setCount(left);
        return result;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0) {
            return ItemStack.EMPTY;
        }

        Container container = containerFor(slot);
        int local = localSlot(slot);

        ItemStack existing = container.getItem(local);
        if (existing.isEmpty()) {
            return ItemStack.EMPTY;
        }

        int moved = Math.min(amount, existing.getCount());

        ItemStack result = existing.copy();
        result.setCount(moved);

        if (!simulate) {
            // 通过 OTC 的 removeItem，保留它的 setChanged()/sync()
            container.removeItem(local, moved);
        }

        return result;
    }

    @Override
    public int getSlotLimit(int slot) {
        // OTC 自己返回 128；真正上限还会被物品自身的最大堆叠数收紧
        return containerFor(slot).getMaxStackSize();
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return containerFor(slot).canPlaceItem(localSlot(slot), stack);
    }
}
