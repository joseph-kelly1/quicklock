package io.github.josephkelly.quicklock.mixin;

import io.github.josephkelly.quicklock.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

@Mixin(ServerExplosion.class)
public abstract class ExplosionMixin {
    @Shadow @Final private ServerLevel level;
    @Inject(method = "interactWithBlocks", at = @At("HEAD"))
    private void quicklock$filter(List<BlockPos> positions, CallbackInfo ci) {
        var p = Protection.active;
        if (p != null) positions.removeIf(pos -> p.lock(level, pos) != null);
    }
}
