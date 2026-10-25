package io.github.josephkelly.quicklock.mixin;

import io.github.josephkelly.quicklock.Protection;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Container.class)
public interface ContainerValidityMixin {
    @Inject(method = "stillValidBlockEntity(Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/player/Player;F)Z", at = @At("HEAD"), cancellable = true)
    private static void quicklock$valid(BlockEntity entity, Player player, float buffer, CallbackInfoReturnable<Boolean> cir) {
        var p = Protection.active;
        if (p != null && entity.getLevel() != null && p.here(entity.getLevel()) && !p.access(player, entity.getLevel(), entity.getBlockPos())) cir.setReturnValue(false);
    }
}
