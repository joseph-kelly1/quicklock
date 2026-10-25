package io.github.josephkelly.quicklock.mixin;

import io.github.josephkelly.quicklock.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonBaseBlock.class)
public abstract class PistonMixin {
    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private static void quicklock$immovable(BlockState state, Level level, BlockPos pos, Direction move, boolean destroy,
                                           Direction pistonDirection, CallbackInfoReturnable<Boolean> cir) {
        if (Protection.active != null && Protection.active.lock(level, pos) != null) cir.setReturnValue(false);
    }
}
