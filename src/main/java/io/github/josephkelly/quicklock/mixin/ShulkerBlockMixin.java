package io.github.josephkelly.quicklock.mixin;

import io.github.josephkelly.quicklock.Protection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShulkerBoxBlock.class)
public abstract class ShulkerBlockMixin {
    @Inject(method = "getDrops", at = @At("RETURN"))
    private void quicklock$drops(BlockState state, LootParams.Builder params, CallbackInfoReturnable<List<ItemStack>> cir) {
        var p = Protection.active;
        var entity = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (p == null || entity == null || entity.getLevel() == null) return;
        var lock = p.droppingLock(entity.getLevel(), entity.getBlockPos());
        for (ItemStack stack : cir.getReturnValue()) {
            if (stack.getItem() instanceof net.minecraft.world.item.BlockItem block && block.getBlock() instanceof ShulkerBoxBlock) {
                if (lock != null) Protection.mark(stack, lock.id);
                else {
                    var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                    tag.remove(Protection.TOKEN);
                    stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                }
            }
        }
    }

    @Inject(method = "playerWillDestroy", at = @At("HEAD"))
    private void quicklock$creativeEmpty(Level level, BlockPos pos, BlockState state, Player player, CallbackInfoReturnable<BlockState> cir) {
        var p = Protection.active;
        if (p == null || !p.here(level) || !player.preventsBlockDrops()) return;
        var lock = p.lock(level, pos);
        if (lock != null && level.getBlockEntity(pos) instanceof ShulkerBoxBlockEntity entity && entity.isEmpty()) {
            ItemStack stack = new ItemStack(state.getBlock());
            stack.applyComponents(entity.collectComponents());
            Protection.mark(stack, lock.id);
            ItemEntity drop = new ItemEntity(level, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, stack);
            drop.setDefaultPickUpDelay();
            level.addFreshEntity(drop);
        }
    }
}
