package io.github.josephkelly.quicklock.mixin;

import io.github.josephkelly.quicklock.Protection;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "placeBlock", at = @At("HEAD"), cancellable = true)
    private void quicklock$check(BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (Protection.active != null && !Protection.active.canPlace(context, state)) cir.setReturnValue(false);
    }

    @Inject(method = "placeBlock", at = @At("RETURN"))
    private void quicklock$placed(BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && Protection.active != null) Protection.active.placed(context);
    }
}
