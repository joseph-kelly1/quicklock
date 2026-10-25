package io.github.josephkelly.quicklock.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.josephkelly.quicklock.Protection;
import io.github.josephkelly.quicklock.QuickLock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerPlayerGameMode.class)
public abstract class PlayerGameModeMixin {
    @Shadow protected ServerLevel level;
    @Shadow protected ServerPlayer player;

    @WrapMethod(method = "destroyBlock")
    private boolean quicklock$break(BlockPos pos, Operation<Boolean> original) {
        var p = Protection.active;
        if (p == null) return original.call(pos);
        var lock = p.lock(level, pos);
        if (lock != null && !p.state.manage(lock, player.getUUID(), p.admin(player.getUUID()))) {
            p.deny(player, lock);
            return false;
        }
        if (lock != null && !lock.owner.equals(player.getUUID())) QuickLock.LOGGER.warn("Admin {} breaking lock {} at {}", player.getUUID(), lock.id, pos);
        var previous = p.breaking.get();
        p.breaking.set(new Protection.Break(Protection.key(level, pos), lock));
        try { return original.call(pos); }
        finally {
            if (previous == null) p.breaking.remove(); else p.breaking.set(previous);
        }
    }

    @WrapMethod(method = "useItemOn")
    private InteractionResult quicklock$use(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
                                           BlockHitResult hit, Operation<InteractionResult> original) {
        var p = Protection.active;
        if (p != null && p.here(level) && !p.access(player, level, hit.getBlockPos())) {
            var lock = p.lock(level, hit.getBlockPos());
            if (lock != null) p.deny(player, lock);
            return InteractionResult.FAIL;
        }
        int before = player.containerMenu.containerId;
        InteractionResult result = original.call(player, level, stack, hand, hit);
        if (p != null && p.here(level) && player.containerMenu.containerId != before && Protection.eligible(level.getBlockState(hit.getBlockPos()))) {
            p.opened.put(player.getUUID(), new Protection.Opened(player.level(), hit.getBlockPos().immutable(), player.containerMenu.containerId));
        }
        return result;
    }
}
