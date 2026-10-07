package app.acmelabs.flagpole.api;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record FlagEvaluation(String flag, boolean enabled) {
}
