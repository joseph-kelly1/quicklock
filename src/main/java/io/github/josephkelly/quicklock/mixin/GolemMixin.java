package io.github.josephkelly.quicklock.mixin;

import io.github.josephkelly.quicklock.Protection;
import java.util.function.BiConsumer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TransportItemsBetweenContainers.class)
public abstract class GolemMixin {
    @Inject(method = "isContainerLocked", at = @At("HEAD"), cancellable = true)
    private void quicklock$target(TransportItemsBetweenContainers.TransportItemTarget target, CallbackInfoReturnable<Boolean> cir) {
        if (Protection.active != null && Protection.active.containerLock(target.container()) != null) cir.setReturnValue(true);
    }

    @Inject(method = "doReachedTargetInteraction", at = @At("HEAD"), cancellable = true)
    private void quicklock$mutation(PathfinderMob body, Container container, BiConsumer<PathfinderMob, Container> a,
                                    BiConsumer<PathfinderMob, Container> b, BiConsumer<PathfinderMob, Container> c,
                                    BiConsumer<PathfinderMob, Container> d, CallbackInfo ci) {
        if (Protection.active != null && Protection.active.containerLock(container) != null) ci.cancel();
    }
}
