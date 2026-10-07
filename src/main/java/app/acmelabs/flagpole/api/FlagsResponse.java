package app.acmelabs.flagpole.api;

import app.acmelabs.flagpole.model.FlagDefinition;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.List;

@Serdeable
public record FlagsResponse(String version, Instant loadedAt, List<FlagDefinition> flags) {
}
