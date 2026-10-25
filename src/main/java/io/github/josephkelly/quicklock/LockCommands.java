package io.github.josephkelly.quicklock;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import java.util.*;
import io.github.josephkelly.quicklock.mixin.CommandSourceAccessor;

public final class LockCommands {
    private LockCommands() {}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String literal : List.of("lock", "unlock", "lockinfo", "lockhelp", "lockview")) {
            dispatcher.register(Commands.literal(literal).requires(s -> s.getEntity() instanceof ServerPlayer)
                    .executes(c -> run(c, literal)));
        }
        for (String literal : List.of("trust", "untrust", "trust-all", "untrust-all")) {
            dispatcher.register(Commands.literal(literal).requires(s -> s.getEntity() instanceof ServerPlayer)
                    .then(Commands.argument("player", StringArgumentType.word())
                            .suggests((c, b) -> {
                                var p = Protection.active;
                                return SharedSuggestionProvider.suggest(p == null ? List.of() : p.state.names.values(), b);
                            }).executes(c -> run(c, literal))));
        }
        dispatcher.register(Commands.literal("quicklockadmin")
                .requires(s -> Protection.active != null && (isConsole(s)
                        || s.getEntity() instanceof ServerPlayer p && Protection.active.admin(p.getUUID())))
                .then(Commands.literal("list").executes(c -> admin(c, "list")))
                .then(Commands.literal("unlock-id").then(Commands.argument("id", StringArgumentType.word()).executes(c -> admin(c, "unlock"))))
                .then(Commands.literal("recover-shulker").then(Commands.argument("id", StringArgumentType.word()).executes(c -> admin(c, "recover"))))
                .then(Commands.literal("release-held").executes(c -> admin(c, "release"))));
    }

    private static boolean isConsole(CommandSourceStack source) {
        var original = ((CommandSourceAccessor) source).quicklock$source();
        return source.getEntity() == null && (original == source.getServer()
                || original instanceof net.minecraft.server.rcon.RconConsoleSource);
    }

    private static int run(CommandContext<CommandSourceStack> context, String command) throws CommandSyntaxException {
        var p = Protection.active;
        if (p == null) return feedback(context, "QuickLock is not ready.");
        ServerPlayer player = context.getSource().getPlayerOrException();
        UUID user = player.getUUID();
        if (command.equals("lockhelp")) return feedback(context,
                "/lock: protect a container you placed. /unlock: make it public. /trust <player> and /untrust <player>: targeted container access. "
                + "/trust-all <player> and /untrust-all <player>: access to your existing and future locks. "
                + "Either trust grant allows use and chest expansion, never breaking or lock management. "
                + "/lockinfo: owner. /lockview: private access labels. Machine output into public storage or the world is public.");
        if (command.equals("lockview")) {
            boolean on = p.state.viewers.add(user);
            if (!on) p.state.viewers.remove(user);
            p.save();
            AccessLabels.clear(player);
            return feedback(context, "Access labels " + (on ? "enabled." : "disabled."));
        }
        UUID subject = null;
        if (command.contains("trust")) {
            String name = StringArgumentType.getString(context, "player");
            var online = p.server.getPlayerList().getPlayer(name);
            var matches = p.state.names.entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(name)).map(Map.Entry::getKey).toList();
            if (online == null && matches.size() > 1) return feedback(context, "Name is ambiguous after a rename. Ask that player to join before granting trust.");
            subject = online != null ? online.getUUID() : matches.isEmpty() ? null : matches.getFirst();
            if (subject == null) return feedback(context, "Unknown player. They must have joined this server before.");
            if (subject.equals(user)) return feedback(context, "Owners already have access to their containers.");
        }
        if (command.endsWith("-all")) {
            Set<UUID> grants = p.state.globalTrust.computeIfAbsent(user, ignored -> new HashSet<>());
            boolean grant = command.equals("trust-all");
            boolean changed = grant ? grants.add(subject) : grants.remove(subject);
            p.save(); p.tick();
            boolean local = false;
            for (var lock : p.state.locks.values()) if (lock.owner.equals(user) && lock.trusted.contains(subject)) { local = true; break; }
            return feedback(context, (changed ? "Owner-wide trust " + (grant ? "granted." : "removed.") : "Owner-wide trust was already " + (grant ? "granted." : "absent."))
                    + (!grant && local ? " Individual container grants still allow access where present." : "")
                    + (!grant && p.admin(subject) ? " Admin override still allows access." : ""));
        }
        BlockPos pos = p.target(player);
        if (pos == null) return feedback(context, "Look at a container within interaction reach.");
        var block = player.level().getBlockState(pos);
        if (!Protection.eligible(block)) return feedback(context, command.equals("lock") ? "This is not lockable" : "Look at a lockable container.");
        var lock = p.lock(player.level(), pos);
        if (command.equals("lock")) {
            if (lock != null) return feedback(context, "This container is already locked.");
            try { p.state.protect(p.keys(player.level(), pos), user, p.admin(user), block.getBlock() instanceof ShulkerBoxBlock); }
            catch (IllegalArgumentException e) { return feedback(context, e.getMessage()); }
            p.syncWitnesses(player.level(), pos);
            p.save(); p.tick();
            return feedback(context, "Container locked.");
        }
        if (lock == null) return feedback(context, "This container is not locked.");
        if (command.equals("lockinfo")) return feedback(context, "Owner: " + p.ownerName(lock.owner));
        if (!p.state.manage(lock, user, p.admin(user))) return feedback(context, "Only the owner or an allowlisted admin can manage this lock.");
        if (command.equals("unlock")) {
            if (!lock.owner.equals(user)) QuickLock.LOGGER.info("Admin {} unlocked {}", user, lock.id);
            p.state.unlock(lock); p.syncWitnesses(player.level(), pos); p.save(); p.tick();
            return feedback(context, "Container unlocked and public.");
        }
        if (lock.owner.equals(subject)) return feedback(context, "The owner already has access.");
        boolean grant = command.equals("trust");
        boolean changed = grant ? lock.trusted.add(subject) : lock.trusted.remove(subject);
        if (!lock.owner.equals(user)) QuickLock.LOGGER.warn("Admin {} changed local trust on {}", user, lock.id);
        p.save(); p.tick();
        return feedback(context, (changed ? "Container trust " + (grant ? "granted." : "removed.") : "Container trust was already " + (grant ? "granted." : "absent."))
                + (!grant && p.state.globalTrust(lock, subject) ? " Owner-wide trust still allows access." : "")
                + (!grant && p.admin(subject) ? " Admin override still allows access." : ""));
    }

    private static int admin(CommandContext<CommandSourceStack> context, String command) throws CommandSyntaxException {
        var p = Protection.active;
        if (p == null) return feedback(context, "QuickLock is not ready.");
        if (command.equals("list")) {
            if (p.state.locks.isEmpty()) return feedback(context, "No locks.");
            for (var lock : p.state.locks.values()) feedback(context, lock.id + " owner=" + p.ownerName(lock.owner)
                    + " " + (lock.portable ? "portable shulker" : lock.positions));
            return 1;
        }
        if (command.equals("release")) {
            ServerPlayer player = context.getSource().getPlayerOrException();
            var stack = player.getMainHandItem();
            if (!Protection.hasToken(stack)) return feedback(context, "Hold a locked shulker in your main hand.");
            var lock = p.state.locks.get(Protection.token(stack));
            if (lock != null && !lock.portable) return feedback(context, "Identity is still placed. Resolve its recorded position first.");
            if (lock != null) p.state.unlock(lock);
            var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            tag.remove(Protection.TOKEN);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            p.save();
            QuickLock.LOGGER.warn("Admin {} released held shulker identity", player.getUUID());
            return feedback(context, "Held shulker is now public. Other copies of its old identity remain invalid.");
        }
        UUID id;
        try { id = UUID.fromString(StringArgumentType.getString(context, "id")); }
        catch (IllegalArgumentException e) { return feedback(context, "Invalid lock UUID."); }
        var lock = p.state.locks.get(id);
        if (lock == null) return feedback(context, "Unknown lock UUID.");
        if (command.equals("unlock")) {
            if (lock.portable) return feedback(context, "For portable shulkers, use release-held while holding the item.");
            p.state.unlock(lock); p.save(); p.tick();
        } else {
            if (!lock.shulker) return feedback(context, "This is not a shulker identity.");
            for (String key : lock.positions) {
                String[] bits = key.split("\\|", 2);
                var level = p.server.getAllLevels().iterator();
                boolean checked = false;
                while (level.hasNext()) {
                    var world = level.next();
                    if (!world.dimension().identifier().toString().equals(bits[0])) continue;
                    BlockPos pos = BlockPos.of(Long.parseLong(bits[1]));
                    if (!world.hasChunkAt(pos)) return feedback(context, "Load the recorded chunk before recovery.");
                    if (world.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock) return feedback(context, "A shulker still exists at the recorded position. Inspect it first.");
                    checked = true;
                }
                if (!checked) return feedback(context, "Recorded dimension unavailable; recovery refused.");
            }
            for (String key : new HashSet<>(lock.positions)) p.state.removed(key);
            lock.portable = true;
            p.save();
        }
        QuickLock.LOGGER.warn("Admin recovery {} by {} for {}", command, context.getSource().getTextName(), id);
        return feedback(context, "Recovery complete. No item or contents were created.");
    }

    private static int feedback(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }
}
