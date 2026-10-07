package app.acmelabs.flagpole.loader;

import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;

/**
 * Periodically reloads the flags file. Disable with {@code flags.reload-enabled: false} (used by tests).
 */
@Singleton
@Requires(property = "flags.reload-enabled", notEquals = "false")
public class FlagReloadJob {

    private final FlagStore store;

    public FlagReloadJob(FlagStore store) {
        this.store = store;
    }

    @Scheduled(fixedDelay = "${flags.reload-interval:5s}", initialDelay = "${flags.reload-interval:5s}")
    void reload() {
        store.reload();
    }
}
