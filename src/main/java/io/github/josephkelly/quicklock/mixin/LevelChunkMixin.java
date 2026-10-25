package io.github.josephkelly.quicklock.mixin;

import io.github.josephkelly.quicklock.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"), cancellable = true)
    private void quicklock$protect(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        var self = (LevelChunk) (Object) this;
        if (Protection.active != null && !Protection.active.canReplace(self.getLevel(), pos, state)) cir.setReturnValue(null);
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void quicklock$remove(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        if (Protection.active != null && cir.getReturnValue() != null) {
            Protection.active.removed(((LevelChunk) (Object) this).getLevel(), pos, cir.getReturnValue(), state);
        }
    }
}
