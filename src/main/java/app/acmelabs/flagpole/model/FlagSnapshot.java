package app.acmelabs.flagpole.model;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * An immutable, fully validated view of the flags file at one point in time.
 *
 * @param flags    flag definitions keyed and sorted by name
 * @param version  SHA-256 (hex) of the raw file content
 * @param loadedAt when this snapshot was loaded
 */
public record FlagSnapshot(SortedMap<String, FlagDefinition> flags, String version, Instant loadedAt) {

    public FlagSnapshot {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(loadedAt, "loadedAt");
        flags = Collections.unmodifiableSortedMap(new TreeMap<>(flags));
    }

    public FlagSnapshot(Map<String, FlagDefinition> flags, String version, Instant loadedAt) {
        this(new TreeMap<>(flags), version, loadedAt);
    }

    public Optional<FlagDefinition> find(String name) {
        return Optional.ofNullable(flags.get(name));
    }
}
