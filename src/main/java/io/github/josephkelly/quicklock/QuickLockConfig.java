package io.github.josephkelly.quicklock;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class QuickLockConfig {
    public Set<UUID> adminUuids = new HashSet<>();
    public int denialCooldownTicks = 40;
    public int labelIntervalTicks = 2;

    public static QuickLockConfig load(Path file) throws IOException {
        var gson = new GsonBuilder().setPrettyPrinting().create();
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(new QuickLockConfig()));
        }
        QuickLockConfig config = gson.fromJson(Files.readString(file), QuickLockConfig.class);
        if (config == null || config.adminUuids == null || config.adminUuids.contains(null)
                || config.denialCooldownTicks < 1 || config.labelIntervalTicks < 1 || config.labelIntervalTicks > 20) {
            throw new IOException("Invalid QuickLock configuration: " + file);
        }
        return config;
    }
}
