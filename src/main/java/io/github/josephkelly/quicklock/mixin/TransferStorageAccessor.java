package io.github.josephkelly.quicklock.mixin;

import net.fabricmc.fabric.impl.transfer.item.ContainerStorageImpl;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ContainerStorageImpl.class, remap = false)
public interface TransferStorageAccessor {
    @Accessor("container") Container quicklock$container();
}
