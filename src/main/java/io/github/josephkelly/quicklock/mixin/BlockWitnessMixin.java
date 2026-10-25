package io.github.josephkelly.quicklock.mixin;

import io.github.josephkelly.quicklock.BlockWitness;
import io.github.josephkelly.quicklock.Protection;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class BlockWitnessMixin implements BlockWitness {
    @Unique private String quicklock$stamp = "";
    @Unique private String quicklock$lock = "";
    @Override public String quicklock$stamp() { return quicklock$stamp; }
    @Override public String quicklock$lock() { return quicklock$lock; }
    @Override public void quicklock$witness(String stamp, String lock) { quicklock$stamp = stamp; quicklock$lock = lock; }

    @Inject(method = "loadWithComponents", at = @At("HEAD"))
    private void quicklock$load(ValueInput input, CallbackInfo ci) {
        quicklock$stamp = input.getStringOr("quicklock_placement", "");
        quicklock$lock = input.getStringOr("quicklock_lock", "");
    }

    @Inject(method = "saveWithoutMetadata(Lnet/minecraft/world/level/storage/ValueOutput;)V", at = @At("HEAD"))
    private void quicklock$save(ValueOutput output, CallbackInfo ci) {
        if (!quicklock$stamp.isEmpty()) output.putString("quicklock_placement", quicklock$stamp);
        if (!quicklock$lock.isEmpty()) output.putString("quicklock_lock", quicklock$lock);
    }

    @Inject(method = "setLevel", at = @At("TAIL"))
    private void quicklock$check(Level level, CallbackInfo ci) {
        var p = Protection.active;
        if (p != null && p.here(level)) p.loaded((BlockEntity) (Object) this);
    }
}
