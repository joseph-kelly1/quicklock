package io.github.josephkelly.quicklock.mixin;

import io.github.josephkelly.quicklock.Protection;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Inject(method = "hasPickUpDelay", at = @At("HEAD"), cancellable = true)
    private void quicklock$noMobPickup(CallbackInfoReturnable<Boolean> cir) {
        // Player pickup checks the raw delay field, so this blocks mob sensors without delaying the owner.
        if (Protection.hasToken(((ItemEntity) (Object) this).getItem())) cir.setReturnValue(true);
    }

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void quicklock$pickup(Player player, CallbackInfo ci) {
        var p = Protection.active;
        if (p != null && !p.pickup(((ItemEntity) (Object) this).getItem(), player.getUUID())) ci.cancel();
    }
}
