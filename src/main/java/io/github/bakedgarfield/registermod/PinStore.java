package io.github.bakedgarfield.registermod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Fail closed on any IO/JSON error rather than overwriting or bypassing credentials. */
public final class PinStore {
    private final Path folder;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Account {
        public int format = 1;
        public String salt;
        public String hash;
        public int failedAttempts;
        public long lockedUntilEpochMs;
    }

    public PinStore(Path folder) throws IOException {
        this.folder = folder;
        Files.createDirectories(folder);
    }

    private Path path(UUID uuid) {
        return folder.resolve(uuid.toString() + ".json");
    }

    public boolean exists(UUID uuid) {
        return Files.exists(path(uuid));
    }

    public Account load(UUID uuid) throws IOException {
        Path path = path(uuid);
        if (!Files.exists(path)) return null;
        try {
            Account account = JSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Account.class);
            if (account == null || account.format != 1 || account.salt == null || account.hash == null)
                throw new IOException("PIN account record is invalid: " + uuid);
            return account;
        } catch (RuntimeException error) {
            throw new IOException("PIN account record could not be decoded: " + uuid, error);
        }
    }

    public void create(UUID uuid, Account account) throws IOException {
        if (exists(uuid)) throw new IOException("PIN account already exists");
        Path dest = path(uuid);
        Path temp = Files.createTempFile(folder, "pin-", ".tmp");
        try {
            Files.writeString(temp, JSON.toJson(account), StandardCharsets.UTF_8);
            try {
                Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, dest);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public void update(UUID uuid, Account account) throws IOException {
        if (!exists(uuid)) throw new IOException("PIN account missing");
        Path dest = path(uuid);
        Path temp = Files.createTempFile(folder, "pin-", ".tmp");
        try {
            Files.writeString(temp, JSON.toJson(account), StandardCharsets.UTF_8);
            try {
                Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
