package io.github.josephkelly.quicklock.mixin;

import io.github.josephkelly.quicklock.Protection;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BaseContainerBlockEntity.class)
public abstract class ContainerBlockEntityMixin implements Container {
    // Implement the Container default on the concrete base so optimized consumers also see the policy.
    @Override
    public boolean canTakeItem(Container into, int slot, ItemStack stack) {
        var p = Protection.active;
        return !Protection.hasToken(stack) && (p == null || p.canPull((Container) this, into));
    }

    @Inject(method = {"canOpen", "stillValid"}, at = @At("HEAD"), cancellable = true)
    private void quicklock$access(Player player, CallbackInfoReturnable<Boolean> cir) {
        var p = Protection.active;
        var self = (BaseContainerBlockEntity) (Object) this;
        if (p != null && self.getLevel() != null && p.here(self.getLevel()) && !p.access(player, self.getLevel(), self.getBlockPos())) {
            if (player instanceof ServerPlayer serverPlayer && p.lock(self.getLevel(), self.getBlockPos()) != null) p.deny(serverPlayer, p.lock(self.getLevel(), self.getBlockPos()));
            cir.setReturnValue(false);
        }
    }
}
