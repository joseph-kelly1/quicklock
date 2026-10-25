package io.github.josephkelly.quicklock.mixin;

import io.github.josephkelly.quicklock.Protection;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HopperBlockEntity.class)
public abstract class HopperMixin {
    @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
    private static void quicklock$pull(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
        var p = Protection.active;
        if (p == null || !p.here(level)) return;
        BlockPos source = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY() + 1, hopper.getLevelZ());
        var lock = p.lock(level, source);
        if (lock != null && !p.state.canExtract(lock, p.containerLock(hopper), hopper instanceof HopperBlockEntity)) cir.setReturnValue(false);
    }

    @Inject(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/entity/item/ItemEntity;)Z", at = @At("HEAD"), cancellable = true)
    private static void quicklock$collect(Container into, ItemEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (Protection.hasToken(entity.getItem())) cir.setReturnValue(false);
    }
}
