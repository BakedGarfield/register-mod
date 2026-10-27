package io.github.bakedgarfield.lobbymanager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** All player files are saved atomically, and corrupt files are never overwritten. */
public final class PlayerStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path directory = FabricLoader.getInstance().getConfigDir().resolve("lobbymanager/players");
    private final Logger logger;
    public PlayerStore(Logger logger) { this.logger = logger; }

    public PlayerRecord load(UUID uuid) throws IOException {
        Path path = directory.resolve(uuid + ".json");
        if (!Files.exists(path)) return new PlayerRecord();
        try {
            PlayerRecord record = GSON.fromJson(Files.readString(path), PlayerRecord.class);
            if (record == null || record.activeGroup == null) throw new IOException("Missing player record");
            return record;
        } catch (RuntimeException e) {
            throw new IOException("Invalid player save (not overwriting): " + path, e);
        }
    }

    public void save(UUID uuid, PlayerRecord record) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(uuid + ".json");
        Path temp = directory.resolve(uuid + ".json.tmp");
        Files.writeString(temp, GSON.toJson(record), StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
