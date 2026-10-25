package io.github.josephkelly.quicklock;

import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;

/** Untracked vanilla displays: packets are sent to exactly one connection, never to a world tracker. */
public final class AccessLabels {
    private record Label(String signature, int entityId) {}
    private static final Map<UUID, Label> LABELS = new HashMap<>();
    private AccessLabels() {}

    public static void tick(Protection p) {
        for (ServerPlayer player : p.server.getPlayerList().getPlayers()) {
            if (!p.state.viewers.contains(player.getUUID()) || !player.isAlive()) { clear(player); continue; }
            BlockPos pos = p.target(player);
            var lock = pos == null ? null : p.lock(player.level(), pos);
            if (lock == null) { clear(player); continue; }
            boolean access = p.access(player, player.level(), pos);
            String signature = player.getId() + "/" + player.level().dimension().identifier() + "/" + lock.id + "/" + lock.positions + "/" + access;
            Label old = LABELS.get(player.getUUID());
            if (old != null && old.signature.equals(signature)) continue;
            clear(player);
            Vec3 center = Vec3.atCenterOf(pos);
            BlockPos other = Protection.partner(player.level(), pos);
            if (other != null) center = center.add(Vec3.atCenterOf(other)).scale(.5);
            Display.TextDisplay display = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, player.level());
            // Entity construction reserves a normal globally unique entity ID. Never add this entity to a level.
            display.setPos(center.x, pos.getY() + 1.25, center.z);
            display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
            display.setText(Component.literal(access ? "Unlocked" : "Locked").withStyle(access ? ChatFormatting.GREEN : ChatFormatting.RED));
            display.setFlags(Display.TextDisplay.FLAG_SHADOW);
            display.setViewRange(1);
            player.connection.send(new ClientboundAddEntityPacket(display.getId(), display.getUUID(), display.getX(), display.getY(), display.getZ(),
                    0, 0, EntityTypes.TEXT_DISPLAY, 0, Vec3.ZERO, 0));
            var metadata = display.getEntityData().getNonDefaultValues();
            if (metadata != null) player.connection.send(new ClientboundSetEntityDataPacket(display.getId(), metadata));
            LABELS.put(player.getUUID(), new Label(signature, display.getId()));
        }
    }

    public static void clear(ServerPlayer player) {
        Label old = LABELS.remove(player.getUUID());
        if (old != null) player.connection.send(new ClientboundRemoveEntitiesPacket(old.entityId));
    }
    public static void forget(UUID player) { LABELS.remove(player); }
    public static void reset() { LABELS.clear(); }
}
