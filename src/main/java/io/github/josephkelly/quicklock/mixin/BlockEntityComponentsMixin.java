package io.github.josephkelly.quicklock.mixin;

import io.github.josephkelly.quicklock.Protection;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockEntity.class)
public abstract class BlockEntityComponentsMixin {
    @Inject(method = "collectComponents", at = @At("RETURN"), cancellable = true)
    private void quicklock$components(CallbackInfoReturnable<DataComponentMap> cir) {
        var self = (BlockEntity) (Object) this;
        var p = Protection.active;
        if (!(self instanceof ShulkerBoxBlockEntity) || p == null || self.getLevel() == null || !p.here(self.getLevel())) return;
        var lock = p.droppingLock(self.getLevel(), self.getBlockPos());
        var original = cir.getReturnValue();
        var data = original.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (lock != null) data = Protection.tokenData(data, lock.id);
        else {
            var tag = data.copyTag();
            tag.remove(Protection.TOKEN);
            data = CustomData.of(tag);
        }
        cir.setReturnValue(DataComponentMap.builder().addAll(original).set(DataComponents.CUSTOM_DATA, data).build());
    }
}
