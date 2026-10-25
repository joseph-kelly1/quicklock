package io.github.josephkelly.quicklock;

import java.util.*;

/** Server-authoritative policy data. No Minecraft classes: the policy can be tested independently. */
public final class LockState {
    public int schema = 1;
    public Map<String, Placement> placements = new HashMap<>();
    public Map<UUID, Lock> locks = new HashMap<>();
    public Map<UUID, Set<UUID>> globalTrust = new HashMap<>();
    public Set<UUID> viewers = new HashSet<>();
    public Map<UUID, String> names = new HashMap<>();
    public Set<UUID> unlockedIds = new HashSet<>();

    public static final class Placement {
        public UUID placer;
        public String block;
        public UUID lock;
        public UUID stamp;

        public Placement(UUID placer, String block, UUID lock) {
            this.placer = placer;
            this.block = block;
            this.lock = lock;
            this.stamp = UUID.randomUUID();
        }
    }

    public static final class Lock {
        public UUID id;
        public UUID owner;
        public Set<UUID> trusted = new HashSet<>();
        public Set<String> positions = new HashSet<>();
        public boolean shulker;
        public boolean portable;

        public Lock(UUID owner, boolean shulker) {
            this.id = UUID.randomUUID();
            this.owner = owner;
            this.shulker = shulker;
        }
    }

    public Lock lockAt(String key) {
        Placement placement = placements.get(key);
        return placement == null || placement.lock == null ? null : locks.get(placement.lock);
    }

    public boolean localTrust(Lock lock, UUID player) {
        return lock.trusted.contains(player);
    }

    public boolean globalTrust(Lock lock, UUID player) {
        return globalTrust.getOrDefault(lock.owner, Set.of()).contains(player);
    }

    public boolean access(Lock lock, UUID player, boolean admin) {
        return lock == null || admin || lock.owner.equals(player) || localTrust(lock, player) || globalTrust(lock, player);
    }

    public boolean manage(Lock lock, UUID player, boolean admin) {
        return lock != null && (admin || lock.owner.equals(player));
    }

    public void placed(String key, UUID player, String block) {
        if (placements.containsKey(key)) throw new IllegalStateException("Placement already tracked: " + key);
        placements.put(key, new Placement(player, block, null));
    }

    public Lock protect(Collection<String> positions, UUID player, boolean admin, boolean shulker) {
        for (String key : positions) {
            Placement placement = placements.get(key);
            if (placement == null) throw new IllegalArgumentException("This container has no placement record.");
            if (placement.lock != null) throw new IllegalArgumentException("This container is already locked.");
            if (!admin && !placement.placer.equals(player)) {
                throw new IllegalArgumentException("You can only lock containers you placed. Double chest halves must share your placement ownership.");
            }
        }
        if (positions.isEmpty()) throw new IllegalArgumentException("No container targeted.");
        Lock lock = new Lock(player, shulker);
        locks.put(lock.id, lock);
        for (String key : positions) attach(lock, key);
        return lock;
    }

    /** Expansion explicitly incorporates the new half under the original lock owner. */
    public void attach(Lock lock, String key) {
        Placement placement = Objects.requireNonNull(placements.get(key));
        if (placement.lock != null && !placement.lock.equals(lock.id)) {
            throw new IllegalArgumentException("Cannot merge different locks.");
        }
        placement.lock = lock.id;
        placement.placer = lock.owner;
        lock.positions.add(key);
    }

    public void unlock(Lock lock) {
        for (String key : lock.positions) {
            Placement placement = placements.get(key);
            if (placement != null && lock.id.equals(placement.lock)) placement.lock = null;
        }
        locks.remove(lock.id);
        unlockedIds.add(lock.id);
    }

    public void removed(String key) {
        Placement placement = placements.remove(key);
        if (placement == null || placement.lock == null) return;
        Lock lock = Objects.requireNonNull(locks.get(placement.lock), "Missing lock record");
        lock.positions.remove(key);
        if (lock.positions.isEmpty()) {
            if (lock.shulker) lock.portable = true;
            else locks.remove(lock.id);
        }
    }

    public void replaceShulker(UUID token, String key, UUID player, String block, boolean admin) {
        Lock lock = locks.get(token);
        if (lock == null || !lock.shulker || !lock.portable || !lock.positions.isEmpty()) {
            throw new IllegalArgumentException("Invalid or already placed locked shulker identity. Ask an admin for recovery.");
        }
        if (!manage(lock, player, admin)) throw new IllegalArgumentException("Only the owner may place this locked shulker.");
        placed(key, lock.owner, block);
        attach(lock, key);
        lock.portable = false;
    }

    public boolean canExtract(Lock source, Lock destination, boolean destinationIsHopper) {
        return source == null || destinationIsHopper && destination != null && source.owner.equals(destination.owner);
    }

    /** Reject inconsistent records rather than silently reopening inventories. */
    public void validate() {
        if (schema != 1 || placements == null || locks == null || globalTrust == null || viewers == null || names == null || unlockedIds == null) {
            throw new IllegalStateException("Unsupported or incomplete QuickLock data");
        }
        placements.forEach((key, p) -> {
            if (key == null || p == null || p.placer == null || p.block == null || p.stamp == null) throw new IllegalStateException("Invalid placement");
            if (p.lock != null) {
                Lock lock = locks.get(p.lock);
                if (lock == null || lock.positions == null || !lock.positions.contains(key)) throw new IllegalStateException("Orphan placement lock");
            }
        });
        locks.forEach((id, lock) -> {
            if (lock == null || !id.equals(lock.id) || unlockedIds.contains(id) || lock.owner == null || lock.trusted == null || lock.positions == null
                    || lock.trusted.contains(null) || lock.positions.isEmpty() != lock.portable || lock.portable && !lock.shulker
                    || lock.shulker && lock.positions.size() > 1 || lock.positions.size() > 2) {
                throw new IllegalStateException("Invalid lock record");
            }
            for (String key : lock.positions) {
                Placement p = placements.get(key);
                if (p == null || !id.equals(p.lock)) throw new IllegalStateException("Orphan lock position");
            }
        });
        globalTrust.forEach((owner, grants) -> {
            if (owner == null || grants == null || grants.contains(null)) throw new IllegalStateException("Invalid global trust");
        });
        if (viewers.contains(null) || names.containsKey(null) || names.containsValue(null)) throw new IllegalStateException("Invalid player data");
    }
}
