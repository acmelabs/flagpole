package app.acmelabs.flagpole.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.annotation.Nullable;

import java.time.Duration;

/**
 * Flag file settings. {@code flags.file} is normally supplied through the {@code FLAGS_FILE} env var.
 */
@ConfigurationProperties("flags")
public interface FlagsConfig {

    @Nullable
    String getFile();

    @Bindable(defaultValue = "5s")
    Duration getReloadInterval();
}
