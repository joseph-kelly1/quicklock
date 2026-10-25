package io.github.josephkelly.quicklock.mixin;

import io.github.josephkelly.quicklock.Protection;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.impl.transfer.item.ContainerStorageImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fabric extraction has no destination identity; it cannot authorize a protected source. */
@Mixin(targets = "net.fabricmc.fabric.impl.transfer.item.ContainerSlotWrapper", remap = false)
public abstract class TransferSlotMixin {
    @Shadow @Final private ContainerStorageImpl storage;

    @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
    private void quicklock$extract(ItemVariant variant, long amount, TransactionContext transaction, CallbackInfoReturnable<Long> cir) {
        var p = Protection.active;
        var container = ((TransferStorageAccessor) storage).quicklock$container();
        if (Protection.hasToken(variant.toStack()) || p != null && p.containerLock(container) != null) cir.setReturnValue(0L);
    }
}
