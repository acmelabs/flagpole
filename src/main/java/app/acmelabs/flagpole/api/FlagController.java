package app.acmelabs.flagpole.api;

import app.acmelabs.flagpole.eval.FlagEvaluator;
import app.acmelabs.flagpole.loader.FlagStore;
import app.acmelabs.flagpole.model.FlagDefinition;
import app.acmelabs.flagpole.model.FlagSnapshot;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.QueryValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.Optional;

@Controller("/api")
@Tag(name = "flags", description = "Read and evaluate feature flags")
public class FlagController {

    private static final String ETAG_DESCRIPTION = "Config version in quotes. Send it back as If-None-Match.";
    private static final String USER_ETAG_DESCRIPTION =
            "Config version plus a hash of the userId, in quotes. Send it back as If-None-Match.";
    private static final String IF_NONE_MATCH_DESCRIPTION = "ETag from a previous response; returns 304 if the config is unchanged";
    private static final String USER_ID_DESCRIPTION = "User to evaluate for. Optional: without it, only flags at 100% rollout are on.";

    private final FlagStore store;
    private final FlagEvaluator evaluator;

    public FlagController(FlagStore store, FlagEvaluator evaluator) {
        this.store = store;
        this.evaluator = evaluator;
    }

    @Get("/flags")
    @Operation(operationId = "listFlags", summary = "List all flag definitions",
            description = "All flags plus the config version. Supports ETag/If-None-Match for cheap polling.")
    @ApiResponse(responseCode = "200", description = "All flags",
            headers = @io.swagger.v3.oas.annotations.headers.Header(name = HttpHeaders.ETAG, description = ETAG_DESCRIPTION),
            content = @Content(schema = @Schema(implementation = FlagsResponse.class)))
    @ApiResponse(responseCode = "304", description = "Config unchanged since the given ETag",
            headers = @io.swagger.v3.oas.annotations.headers.Header(name = HttpHeaders.ETAG, description = ETAG_DESCRIPTION))
    public HttpResponse<FlagsResponse> list(
            @Parameter(description = IF_NONE_MATCH_DESCRIPTION)
            @Header(HttpHeaders.IF_NONE_MATCH) @Nullable String ifNoneMatch) {
        FlagSnapshot snapshot = store.snapshot();
        List<FlagDefinition> flags = List.copyOf(snapshot.flags().values());
        return ETags.conditional(ifNoneMatch, ETags.of(snapshot.version()),
                new FlagsResponse(snapshot.version(), snapshot.loadedAt(), flags));
    }

    // Returning an empty Optional makes Micronaut respond with 404.
    @Get("/flags/{name}")
    @Operation(operationId = "getFlag", summary = "Get one flag definition")
    @ApiResponse(responseCode = "200", description = "The flag",
            content = @Content(schema = @Schema(implementation = FlagDefinition.class)))
    @ApiResponse(responseCode = "404", description = "Unknown flag")
    public Optional<FlagDefinition> get(@Parameter(description = "Flag name", example = "new-checkout") String name) {
        return store.snapshot().find(name);
    }

    @Get("/flags/{name}/evaluate")
    @Operation(operationId = "evaluateFlag", summary = "Evaluate one flag for a user")
    @ApiResponse(responseCode = "200", description = "Evaluation result",
            content = @Content(schema = @Schema(implementation = FlagEvaluation.class)))
    @ApiResponse(responseCode = "404", description = "Unknown flag")
    public Optional<FlagEvaluation> evaluate(
            @Parameter(description = "Flag name", example = "new-checkout") String name,
            @Parameter(description = USER_ID_DESCRIPTION, example = "user_42") @QueryValue @Nullable String userId) {
        return store.snapshot().find(name)
                .map(flag -> new FlagEvaluation(name, evaluator.evaluate(flag, userId)));
    }

    @Get("/evaluate")
    @Operation(operationId = "evaluateAll", summary = "Evaluate every flag for a user",
            description = "One call returning all flags for the user, meant to be cached client-side. "
                    + "Supports ETag/If-None-Match.")
    @ApiResponse(responseCode = "200", description = "Every flag evaluated",
            headers = @io.swagger.v3.oas.annotations.headers.Header(name = HttpHeaders.ETAG, description = USER_ETAG_DESCRIPTION),
            content = @Content(schema = @Schema(implementation = UserEvaluation.class)))
    @ApiResponse(responseCode = "304", description = "Config unchanged since the given ETag",
            headers = @io.swagger.v3.oas.annotations.headers.Header(name = HttpHeaders.ETAG, description = USER_ETAG_DESCRIPTION))
    public HttpResponse<UserEvaluation> evaluateAll(
            @Parameter(description = USER_ID_DESCRIPTION, example = "user_42") @QueryValue @Nullable String userId,
            @Parameter(description = IF_NONE_MATCH_DESCRIPTION)
            @Header(HttpHeaders.IF_NONE_MATCH) @Nullable String ifNoneMatch) {
        // A blank userId is anonymous, so report it as absent rather than echoing "".
        String user = userId == null || userId.isBlank() ? null : userId;
        FlagSnapshot snapshot = store.snapshot();
        return ETags.conditional(ifNoneMatch, ETags.forUser(snapshot.version(), user),
                new UserEvaluation(snapshot.version(), user, evaluator.evaluateAll(snapshot, user)));
    }
}
