package io.github.josephkelly.quicklock.mixin;

import io.github.josephkelly.quicklock.Protection;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(CompoundContainer.class)
public abstract class CompoundContainerMixin implements Container {
    @Override
    public boolean canTakeItem(Container into, int slot, ItemStack stack) {
        var p = Protection.active;
        return !Protection.hasToken(stack) && (p == null || p.canPull((Container) this, into));
    }
}
