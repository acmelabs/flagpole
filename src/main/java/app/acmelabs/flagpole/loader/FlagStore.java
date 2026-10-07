package app.acmelabs.flagpole.loader;

import app.acmelabs.flagpole.config.FlagsConfig;
import app.acmelabs.flagpole.model.FlagSnapshot;
import app.acmelabs.flagpole.util.Sha256;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the current flag snapshot and reloads it from disk.
 * <p>
 * Eagerly created ({@link Context}) so that a missing or invalid file fails startup.
 * After startup, a failed reload never replaces the last good snapshot.
 */
@Context
public class FlagStore {

    private static final Logger LOG = LoggerFactory.getLogger(FlagStore.class);

    public enum ReloadResult { UNCHANGED, RELOADED, FAILED }

    /**
     * Outcome of the most recent reload attempt that failed, if the failure is still current.
     */
    public record ReloadError(String message, Instant at) {
    }

    private final Path file;
    private final FlagFileParser parser;
    private final AtomicReference<FlagSnapshot> current = new AtomicReference<>();
    private volatile ReloadError lastError;
    // When the file was last read and found valid (changed or not); shows how stale the served config may be.
    private volatile Instant lastSuccessfulCheckAt;
    // Identifies the current failure (content hash, or the read error), so it is reported once, not every tick.
    private String lastFailureKey;

    public FlagStore(FlagsConfig config, FlagFileParser parser) {
        if (config.getFile() == null || config.getFile().isBlank()) {
            throw new ConfigurationException("No flags file configured: set the FLAGS_FILE environment variable");
        }
        this.file = Path.of(config.getFile());
        this.parser = parser;
        try {
            current.set(parser.parse(read()));
            lastSuccessfulCheckAt = snapshot().loadedAt();
        } catch (NoSuchFileException e) {
            throw new ConfigurationException("Flags file not found: " + file);
        } catch (IOException e) {
            throw new ConfigurationException("Cannot read flags file " + file + ": " + e.getMessage(), e);
        } catch (InvalidFlagsException e) {
            throw new ConfigurationException(file + ": " + e.getMessage(), e);
        }
        LOG.info("Loaded {} flags from {} (version {})", snapshot().flags().size(), file, shortVersion());
    }

    public FlagSnapshot snapshot() {
        return current.get();
    }

    public Path file() {
        return file;
    }

    /**
     * @return the error from the latest reload attempt, or {@code null} if the latest attempt succeeded
     * or found no change
     */
    @Nullable
    public ReloadError lastError() {
        return lastError;
    }

    /**
     * @return when the file was last read and validated successfully, whether or not it had changed
     */
    public Instant lastSuccessfulCheckAt() {
        return lastSuccessfulCheckAt;
    }

    /**
     * Re-reads the file and swaps in a new snapshot if, and only if, the content changed and is valid.
     * Content is compared by hash, so Kubernetes ConfigMap symlink swaps are detected regardless of mtime.
     */
    public synchronized ReloadResult reload() {
        byte[] content;
        try {
            content = read();
        } catch (IOException e) {
            return fail("read:" + e, "cannot read " + file + ": " + e);
        }
        String hash = Sha256.hex(content);
        if (hash.equals(snapshot().version())) {
            succeeded(Instant.now());
            return ReloadResult.UNCHANGED;
        }
        if (hash.equals(lastFailureKey)) {
            return ReloadResult.FAILED;
        }
        FlagSnapshot next;
        try {
            next = parser.parse(content);
        } catch (InvalidFlagsException e) {
            return fail(hash, e.getMessage());
        }
        FlagSnapshot previous = current.getAndSet(next);
        succeeded(next.loadedAt());
        LOG.info("Reloaded {} flags from {} (version {} -> {})",
                next.flags().size(), file, abbreviate(previous.version()), abbreviate(next.version()));
        return ReloadResult.RELOADED;
    }

    private void succeeded(Instant at) {
        lastSuccessfulCheckAt = at;
        lastError = null;
        lastFailureKey = null;
    }

    private ReloadResult fail(String failureKey, String message) {
        if (!failureKey.equals(lastFailureKey)) {
            lastFailureKey = failureKey;
            lastError = new ReloadError(message, Instant.now());
            LOG.error("Flags reload failed, keeping version {}: {}", shortVersion(), message);
        }
        return ReloadResult.FAILED;
    }

    private byte[] read() throws IOException {
        return Files.readAllBytes(file);
    }

    private String shortVersion() {
        return abbreviate(snapshot().version());
    }

    public static String abbreviate(String version) {
        return version.length() > 12 ? version.substring(0, 12) : version;
    }
}
