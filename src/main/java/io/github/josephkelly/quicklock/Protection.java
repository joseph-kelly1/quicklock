package io.github.josephkelly.quicklock;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import io.github.josephkelly.quicklock.mixin.CompoundContainerAccessor;

public final class Protection {
    public static Protection active;
    public static final String TOKEN = "quicklock_id";
    private static final Set<Block> ELIGIBLE = Collections.newSetFromMap(new IdentityHashMap<>());
    static {
        ELIGIBLE.addAll(List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.FURNACE,
                Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.BREWING_STAND, Blocks.HOPPER,
                Blocks.DROPPER, Blocks.DISPENSER, Blocks.CRAFTER, Blocks.SHULKER_BOX));
        ELIGIBLE.addAll(Blocks.DYED_SHULKER_BOX.asList());
    }

    public final MinecraftServer server;
    public final StateStore store;
    public final LockState state;
    public final QuickLockConfig config;
    private final Map<UUID, Long> denials = new HashMap<>();
    public final Map<UUID, Opened> opened = new HashMap<>();
    public final ThreadLocal<Break> breaking = new ThreadLocal<>();
    private boolean failed;
    public record Opened(ServerLevel level, BlockPos pos, int menuId) {}
    public record Break(String key, LockState.Lock lock) {}

    public Protection(MinecraftServer server, StateStore store, QuickLockConfig config) {
        this.server = server; this.store = store; this.state = store.state; this.config = config;
    }

    public static boolean eligible(BlockState state) { return ELIGIBLE.contains(state.getBlock()); }
    public static String blockId(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(); }
    public static String key(Level level, BlockPos pos) { return level.dimension().identifier() + "|" + pos.asLong(); }
    public boolean admin(UUID id) { return config.adminUuids.contains(id); }
    public boolean here(Level level) { return level instanceof ServerLevel && level.getServer() == server; }

    public void save() {
        try { store.save(); }
        catch (RuntimeException e) {
            failed = true;
            server.halt(false);
            throw e;
        }
    }

    public void syncWitness(Level level, BlockPos pos) {
        var placement = state.placements.get(key(level, pos));
        var entity = level.getBlockEntity(pos);
        if (entity instanceof BlockWitness witness) {
            witness.quicklock$witness(placement == null ? "" : placement.stamp.toString(),
                    placement == null || placement.lock == null ? "" : placement.lock.toString());
            entity.setChanged();
        }
    }

    public void syncWitnesses(Level level, BlockPos pos) {
        syncWitness(level, pos);
        var other = partner(level, pos);
        if (other != null) syncWitness(level, other);
    }

    public void loaded(BlockEntity entity) {
        if (!eligible(entity.getBlockState())) return;
        var witness = (BlockWitness) entity;
        var placement = state.placements.get(key(entity.getLevel(), entity.getBlockPos()));
        if (placement != null && !placement.block.equals(blockId(entity.getBlockState()))) {
            if (placement.lock != null) { inconsistent(entity); return; }
            state.placements.remove(key(entity.getLevel(), entity.getBlockPos()));
            placement = null;
            save();
        }
        if (!witness.quicklock$lock().isEmpty()) {
            UUID id;
            try { id = UUID.fromString(witness.quicklock$lock()); }
            catch (IllegalArgumentException e) { inconsistent(entity); return; }
            if (!state.unlockedIds.contains(id) && (placement == null || !id.equals(placement.lock))) {
                inconsistent(entity); return;
            }
        }
        if (placement != null && !witness.quicklock$stamp().isEmpty() && !placement.stamp.toString().equals(witness.quicklock$stamp())) {
            inconsistent(entity); return;
        }
        // Reapply the authoritative state after an unlock/lock saved before its chunk was flushed.
        if (placement != null) {
            witness.quicklock$witness(placement.stamp.toString(), placement.lock == null ? "" : placement.lock.toString());
            entity.setChanged();
        } else if (!witness.quicklock$stamp().isEmpty()) {
            witness.quicklock$witness("", "");
            entity.setChanged();
        }
    }

    private void inconsistent(BlockEntity entity) {
        failed = true;
        server.halt(false);
        throw new IllegalStateException("QuickLock chunk/data mismatch at " + key(entity.getLevel(), entity.getBlockPos())
                + ". Restore a consistent world and data backup; protection has stopped the server.");
    }

    public LockState.Lock lock(Level level, BlockPos pos) {
        if (!here(level)) return null;
        // Include the other half so a malformed/stale pair can never open through an unindexed half.
        var own = state.lockAt(key(level, pos));
        if (own != null) return own;
        var partner = partner(level, pos);
        return partner == null ? null : state.lockAt(key(level, partner));
    }

    public static BlockPos partner(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (!(s.getBlock() == Blocks.CHEST || s.getBlock() == Blocks.TRAPPED_CHEST)
                || s.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return null;
        BlockPos other = pos.relative(ChestBlock.getConnectedDirection(s));
        if (!level.hasChunkAt(other)) return null;
        BlockState adjacent = level.getBlockState(other);
        return adjacent.is(s.getBlock()) && adjacent.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                && other.relative(ChestBlock.getConnectedDirection(adjacent)).equals(pos) ? other : null;
    }

    public List<String> keys(Level level, BlockPos pos) {
        BlockPos other = partner(level, pos);
        return other == null ? List.of(key(level, pos)) : List.of(key(level, pos), key(level, other));
    }

    public boolean access(Player player, Level level, BlockPos pos) {
        return !failed && state.access(lock(level, pos), player.getUUID(), admin(player.getUUID()));
    }

    public void deny(ServerPlayer player, LockState.Lock lock) {
        long now = server.getTickCount();
        if (now - denials.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2) >= config.denialCooldownTicks) {
            player.sendSystemMessage(Component.literal("This container is locked by " + ownerName(lock.owner) + "."));
            denials.put(player.getUUID(), now);
        }
    }

    public String ownerName(UUID id) { return state.names.getOrDefault(id, id.toString()); }

    public BlockPos target(ServerPlayer player) {
        HitResult hit = player.pick(player.blockInteractionRange(), 1, false);
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK ? block.getBlockPos() : null;
    }

    public boolean canPlace(BlockPlaceContext context, BlockState placement) {
        if (!here(context.getLevel())) return true;
        if (failed) return false;
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        if (hasToken(stack)) {
            UUID token = token(stack);
            var record = state.locks.get(token);
            if (!(placement.getBlock() instanceof ShulkerBoxBlock) || player == null || record == null
                    || !record.shulker || !record.portable || !record.positions.isEmpty()
                    || !state.manage(record, player.getUUID(), admin(player.getUUID()))) {
                if (player instanceof ServerPlayer p) p.sendSystemMessage(Component.literal("Invalid, already placed, or foreign locked shulker. Ask an admin for recovery."));
                return false;
            }
        }
        if ((placement.is(Blocks.CHEST) || placement.is(Blocks.TRAPPED_CHEST)) && placement.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = context.getClickedPos().relative(ChestBlock.getConnectedDirection(placement));
            var existing = lock(context.getLevel(), other);
            if (existing != null && (player == null || !state.access(existing, player.getUUID(), admin(player.getUUID())))) {
                if (player instanceof ServerPlayer p) deny(p, existing);
                return false;
            }
        }
        return true;
    }

    /** Runs before item consumption but after all block-entity components have been applied. */
    public void placed(BlockPlaceContext context) {
        if (!here(context.getLevel())) return;
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState block = level.getBlockState(pos);
        if (!eligible(block)) return;
        String key = key(level, pos);
        if (state.placements.containsKey(key)) throw new IllegalStateException("Stale QuickLock placement at " + key);
        UUID token = token(context.getItemInHand());
        if (hasToken(context.getItemInHand())) {
            Player player = Objects.requireNonNull(context.getPlayer());
            state.replaceShulker(token, key, player.getUUID(), blockId(block), admin(player.getUUID()));
        } else if (context.getPlayer() != null) {
            state.placed(key, context.getPlayer().getUUID(), blockId(block));
            BlockPos other = partner(level, pos);
            var existing = other == null ? null : state.lockAt(key(level, other));
            if (existing != null) state.attach(existing, key);
        }
        syncWitnesses(level, pos);
        save();
    }

    public boolean canReplace(Level level, BlockPos pos, BlockState replacement) {
        if (!here(level) || level.getBlockState(pos).is(replacement.getBlock())) return true;
        var lock = lock(level, pos);
        Break scope = breaking.get();
        return !failed && (lock == null || scope != null && scope.key.equals(key(level, pos)));
    }

    public void removed(Level level, BlockPos pos, BlockState old, BlockState replacement) {
        if (here(level) && !old.is(replacement.getBlock()) && state.placements.containsKey(key(level, pos))) {
            state.removed(key(level, pos));
            save();
        }
    }

    public LockState.Lock containerLock(Container container) {
        if (container instanceof BlockEntity entity && entity.getLevel() != null) return lock(entity.getLevel(), entity.getBlockPos());
        if (container instanceof CompoundContainer compound) {
            var halves = (CompoundContainerAccessor) compound;
            var a = containerLock(halves.quicklock$first());
            return a != null ? a : containerLock(halves.quicklock$second());
        }
        return null;
    }

    public boolean canPull(Container from, Container into) {
        return !failed && state.canExtract(containerLock(from), containerLock(into), into instanceof HopperBlockEntity);
    }

    public static boolean hasToken(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().contains(TOKEN);
    }

    public static UUID token(ItemStack stack) {
        try {
            return UUID.fromString(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr(TOKEN, ""));
        } catch (IllegalArgumentException e) { return null; }
    }

    public static CustomData tokenData(CustomData original, UUID token) {
        CompoundTag tag = original.copyTag();
        tag.putString(TOKEN, token.toString());
        return CustomData.of(tag);
    }

    public static void mark(ItemStack stack, UUID token) {
        stack.set(DataComponents.CUSTOM_DATA, tokenData(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY), token));
    }

    public LockState.Lock droppingLock(Level level, BlockPos pos) {
        Break scope = breaking.get();
        return scope != null && scope.key.equals(key(level, pos)) ? scope.lock : lock(level, pos);
    }

    public boolean pickup(ItemStack stack, UUID player) {
        if (!hasToken(stack)) return true;
        if (admin(player)) return !failed;
        var record = state.locks.get(token(stack));
        return !failed && record != null && record.shulker && record.portable && record.owner.equals(player);
    }

    public void tick() {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Opened tracked = opened.get(player.getUUID());
            if (tracked == null) continue;
            if (player.containerMenu == player.inventoryMenu || tracked.menuId != player.containerMenu.containerId) {
                opened.remove(player.getUUID());
            } else if (tracked.level != player.level()
                    || !access(player, tracked.level, tracked.pos)) {
                player.closeContainer();
                opened.remove(player.getUUID());
            }
        }
    }

    public void forget(UUID player) { denials.remove(player); opened.remove(player); }
}
