package io.github.josephkelly.quicklock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LockStateTest {
    final UUID owner = UUID.randomUUID(), friend = UUID.randomUUID(), stranger = UUID.randomUUID();

    LockState state() {
        LockState state = new LockState();
        state.placed("overworld|1", owner, "minecraft:chest");
        return state;
    }

    @Test void independentTrustGrantsAndManagement() {
        LockState s = state();
        var lock = s.protect(List.of("overworld|1"), owner, false, false);
        assertTrue(s.access(lock, owner, false));
        assertFalse(s.access(lock, stranger, false));
        lock.trusted.add(friend);
        s.globalTrust.put(owner, new HashSet<>(Set.of(friend)));
        lock.trusted.remove(friend);
        assertTrue(s.access(lock, friend, false));
        lock.trusted.add(friend);
        s.globalTrust.get(owner).remove(friend);
        assertTrue(s.access(lock, friend, false));
        assertFalse(s.manage(lock, friend, false));
        lock.trusted.remove(friend);
        assertFalse(s.access(lock, friend, false));
        assertTrue(s.manage(lock, stranger, true));
        assertTrue(s.access(lock, stranger, true));
    }

    @Test void globalTrustIncludesFutureLocks() {
        LockState s = state();
        s.globalTrust.put(owner, new HashSet<>(Set.of(friend)));
        assertTrue(s.access(s.protect(List.of("overworld|1"), owner, false, false), friend, false));
        s.placed("nether|1", owner, "minecraft:barrel");
        assertTrue(s.access(s.protect(List.of("nether|1"), owner, false, false), friend, false));
    }

    @Test void placementDoesNotProtectAndCannotBeClaimed() {
        LockState s = state();
        assertNull(s.lockAt("overworld|1"));
        assertThrows(IllegalArgumentException.class, () -> s.protect(List.of("missing"), owner, true, false));
        assertThrows(IllegalArgumentException.class, () -> s.protect(List.of("overworld|1"), stranger, false, false));
        s.removed("overworld|1");
        s.placed("overworld|1", stranger, "minecraft:chest");
        assertEquals(stranger, s.protect(List.of("overworld|1"), stranger, false, false).owner);
    }

    @Test void mixedPlacerDoubleChestCannotBeLockedPartially() {
        LockState s = state();
        s.placed("overworld|2", friend, "minecraft:chest");
        assertThrows(IllegalArgumentException.class, () -> s.protect(List.of("overworld|1", "overworld|2"), owner, false, false));
        assertTrue(s.locks.isEmpty());
        assertNull(s.lockAt("overworld|1"));
    }

    @Test void doubleChestExpansionSplitAndUnlock() {
        LockState s = state();
        var lock = s.protect(List.of("overworld|1"), owner, false, false);
        lock.trusted.add(friend);
        s.placed("overworld|2", friend, "minecraft:chest");
        s.attach(lock, "overworld|2");
        assertEquals(owner, s.placements.get("overworld|2").placer);
        s.removed("overworld|1");
        assertSame(lock, s.lockAt("overworld|2"));
        assertTrue(s.access(lock, friend, false));
        s.validate();
        s.unlock(lock);
        assertNull(s.lockAt("overworld|2"));
        assertTrue(s.locks.isEmpty());
        s.validate();
    }

    @Test void portableShulkerRetainsTrustAndRejectsCopies() {
        LockState s = state();
        var lock = s.protect(List.of("overworld|1"), owner, false, true);
        lock.trusted.add(friend);
        s.removed("overworld|1");
        s.validate();
        assertThrows(IllegalArgumentException.class, () -> s.replaceShulker(lock.id, "overworld|2", friend, "minecraft:shulker_box", false));
        s.replaceShulker(lock.id, "overworld|2", owner, "minecraft:shulker_box", false);
        assertTrue(s.access(s.lockAt("overworld|2"), friend, false));
        assertThrows(IllegalArgumentException.class, () -> s.replaceShulker(lock.id, "overworld|3", owner, "minecraft:shulker_box", false));
        assertThrows(IllegalArgumentException.class, () -> s.replaceShulker(UUID.randomUUID(), "overworld|3", owner, "minecraft:shulker_box", false));
        s.validate();
    }

    @Test void onlyLockedSameOwnerHopperMayPull() {
        LockState s = state();
        var source = s.protect(List.of("overworld|1"), owner, false, false);
        var own = new LockState.Lock(owner, false);
        var foreign = new LockState.Lock(friend, false);
        source.trusted.add(friend);
        assertFalse(s.canExtract(source, null, true));
        assertFalse(s.canExtract(source, foreign, true));
        assertFalse(s.canExtract(source, own, false));
        assertTrue(s.canExtract(source, own, true));
        assertTrue(s.canExtract(null, foreign, false));
    }

    @Test void corruptionDoesNotOpenLocks() {
        LockState s = state();
        var lock = s.protect(List.of("overworld|1"), owner, false, false);
        s.locks.remove(lock.id);
        assertThrows(IllegalStateException.class, s::validate);
    }

    @Test void persistenceRoundTripAndBackupGuard(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("quicklock.json");
        StateStore store = new StateStore(file);
        store.state.placed("overworld|1", owner, "minecraft:shulker_box");
        var lock = store.state.protect(List.of("overworld|1"), owner, false, true);
        lock.trusted.add(friend);
        store.state.globalTrust.put(owner, new HashSet<>(Set.of(stranger)));
        store.state.viewers.add(owner);
        store.save();
        store.state.removed("overworld|1");
        store.save();
        var loaded = new StateStore(file).state;
        assertTrue(loaded.locks.get(lock.id).portable);
        assertTrue(loaded.locks.get(lock.id).trusted.contains(friend));
        assertTrue(loaded.globalTrust(loaded.locks.get(lock.id), stranger));
        assertTrue(loaded.viewers.contains(owner));
        Files.delete(file);
        assertThrows(java.io.IOException.class, () -> new StateStore(file));
    }
}
