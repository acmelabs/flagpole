package app.acmelabs.flagpole.ui;

import app.acmelabs.flagpole.eval.FlagEvaluator;
import app.acmelabs.flagpole.loader.FlagStore;
import app.acmelabs.flagpole.model.FlagDefinition;
import app.acmelabs.flagpole.model.FlagSnapshot;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.views.View;
import io.swagger.v3.oas.annotations.Hidden;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Read-only UI. Flags are changed through pull requests to the flags file, never here.
 * <p>
 * Templates only read properties of the view records below and never call methods
 * (no {@code #lists}/{@code #sets}/{@code #strings}): OGNL resolves those reflectively on
 * JDK-internal classes, which fails in native image.
 */
@Controller
@Hidden
@Produces(MediaType.TEXT_HTML)
public class UiController {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC);

    @Serdeable
    @ReflectiveAccess
    public record FlagRow(String name, boolean enabled, int rollout, List<String> allow, int allowCount,
                          boolean hasAllow, @Nullable String description) {
    }

    @Serdeable
    @ReflectiveAccess
    public record EvaluationRow(String name, boolean enabled, String reason) {
    }

    private final FlagStore store;
    private final FlagEvaluator evaluator;

    public UiController(FlagStore store, FlagEvaluator evaluator) {
        this.store = store;
        this.evaluator = evaluator;
    }

    @View("index")
    @Get
    public Map<String, Object> index() {
        FlagSnapshot snapshot = store.snapshot();
        FlagStore.ReloadError error = store.lastError();
        List<FlagRow> flags = snapshot.flags().values().stream()
                .map(f -> new FlagRow(f.name(), f.enabled(), f.rollout(), List.copyOf(f.allow()), f.allow().size(),
                        !f.allow().isEmpty(), f.description()))
                .toList();
        return Map.of(
                "flags", flags,
                "noFlags", flags.isEmpty(),
                "version", snapshot.version(),
                "shortVersion", FlagStore.abbreviate(snapshot.version()),
                "loadedAt", TIME.format(snapshot.loadedAt()),
                "hasReloadError", error != null,
                "reloadError", error == null ? "" : error.message());
    }

    @View("evaluation")
    @Get("/ui/evaluate")
    public Map<String, Object> evaluate(@QueryValue @Nullable String userId) {
        FlagSnapshot snapshot = store.snapshot();
        List<EvaluationRow> rows = snapshot.flags().values().stream()
                .map(flag -> row(flag, userId))
                .toList();
        boolean anonymous = userId == null || userId.isBlank();
        return Map.of("userId", anonymous ? "" : userId, "anonymous", anonymous, "rows", rows);
    }

    private EvaluationRow row(FlagDefinition flag, @Nullable String userId) {
        FlagEvaluator.Decision decision = evaluator.decide(flag, userId);
        return new EvaluationRow(flag.name(), decision.enabled(), decision.reason());
    }
}
