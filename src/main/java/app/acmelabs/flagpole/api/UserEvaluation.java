package app.acmelabs.flagpole.api;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;

@Serdeable
public record UserEvaluation(String version, @Nullable String userId, Map<String, Boolean> flags) {
}
