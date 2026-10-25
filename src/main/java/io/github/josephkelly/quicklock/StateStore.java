package io.github.josephkelly.quicklock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Atomic world-local state. Save failure stops the server instead of permitting an unsaved lock. */
public final class StateStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path;
    public final LockState state;

    public StateStore(Path path) throws IOException {
        this.path = path;
        if (Files.exists(path)) {
            var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            for (String field : java.util.List.of("schema", "placements", "locks", "globalTrust", "viewers", "names", "unlockedIds")) {
                if (!json.has(field)) throw new IOException("Incomplete QuickLock state (missing " + field + "): " + path);
            }
            state = GSON.fromJson(json, LockState.class);
            if (state == null) throw new IOException("Empty QuickLock state: " + path);
            state.validate();
        } else {
            if (Files.exists(path.resolveSibling(path.getFileName() + ".bak"))) {
                throw new IOException("QuickLock primary state missing but backup exists. Restore a consistent world backup before starting.");
            }
            state = new LockState();
        }
    }

    public void save() {
        state.validate();
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(state), StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            if (Files.exists(path)) Files.copy(path, path.resolveSibling(path.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("QuickLock cannot save protection data. Stop and repair storage before continuing.", e);
        }
    }
}
