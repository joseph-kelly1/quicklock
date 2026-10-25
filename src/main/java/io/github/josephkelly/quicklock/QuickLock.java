package io.github.josephkelly.quicklock;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.storage.LevelResource;

public final class QuickLock implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("quicklock");

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> LockCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            try {
                var config = QuickLockConfig.load(FabricLoader.getInstance().getConfigDir().resolve("quicklock.json"));
                var store = new StateStore(server.getWorldPath(LevelResource.ROOT).resolve("data/quicklock.json"));
                Protection.active = new Protection(server, store, config);
                Protection.active.save();
                LOGGER.info("QuickLock loaded {} locks and {} placements", store.state.locks.size(), store.state.placements.size());
            } catch (Exception e) { throw new IllegalStateException("QuickLock cannot load protection data; refusing startup", e); }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var p = Protection.active;
            if (p != null) {
                p.state.names.put(handler.player.getUUID(), handler.player.getGameProfile().name());
                p.forget(handler.player.getUUID());
                AccessLabels.forget(handler.player.getUUID());
                p.save();
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (Protection.active != null) Protection.active.forget(handler.player.getUUID());
            AccessLabels.forget(handler.player.getUUID());
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            AccessLabels.clear(newPlayer);
            if (Protection.active != null) Protection.active.forget(newPlayer.getUUID());
        });
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> {
            AccessLabels.clear(player);
            if (Protection.active != null) Protection.active.forget(player.getUUID());
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var p = Protection.active;
            if (p != null) {
                p.tick();
                if (server.getTickCount() % p.config.labelIntervalTicks == 0) AccessLabels.tick(p);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (Protection.active != null) Protection.active.save(); });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { Protection.active = null; AccessLabels.reset(); });
    }
}
