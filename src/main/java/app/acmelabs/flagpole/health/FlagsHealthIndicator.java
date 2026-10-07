package app.acmelabs.flagpole.health;

import app.acmelabs.flagpole.loader.FlagStore;
import app.acmelabs.flagpole.model.FlagSnapshot;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.AbstractHealthIndicator;
import jakarta.inject.Singleton;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports the served config version and reload state. Stays UP after a failed reload because
 * the last good config keeps being served; the failure is visible in {@code lastReloadError}.
 */
@Singleton
public class FlagsHealthIndicator extends AbstractHealthIndicator<Map<String, Object>> {

    private final FlagStore store;

    public FlagsHealthIndicator(FlagStore store) {
        this.store = store;
    }

    @Override
    public String getName() {
        return "flags";
    }

    @Override
    protected Map<String, Object> getHealthInformation() {
        FlagSnapshot snapshot = store.snapshot();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("version", snapshot.version());
        details.put("lastReloadAt", snapshot.loadedAt().toString());
        details.put("lastSuccessfulCheckAt", store.lastSuccessfulCheckAt().toString());
        details.put("flagCount", snapshot.flags().size());
        details.put("file", store.file().toString());
        FlagStore.ReloadError error = store.lastError();
        if (error != null) {
            details.put("lastReloadError", error.message());
            details.put("lastReloadErrorAt", error.at().toString());
        }
        healthStatus = HealthStatus.UP;
        return details;
    }
}
