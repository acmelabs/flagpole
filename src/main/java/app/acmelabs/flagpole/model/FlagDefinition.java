package app.acmelabs.flagpole.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A single, validated flag definition. Immutable.
 */
@Serdeable
public record FlagDefinition(
        String name,
        boolean enabled,
        int rollout,
        @JsonInclude(JsonInclude.Include.ALWAYS) Set<String> allow,
        @Nullable String description) {

    public static final int FULL_ROLLOUT = 100;

    public FlagDefinition {
        Objects.requireNonNull(name, "name");
        allow = allow == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(allow));
    }
}
