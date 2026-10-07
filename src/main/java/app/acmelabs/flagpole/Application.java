package app.acmelabs.flagpole;

import io.micronaut.runtime.Micronaut;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

import java.util.Set;

@OpenAPIDefinition(info = @Info(
        title = "flagpole",
        // Filled in from the Gradle project version at compile time (see build.gradle.kts).
        version = "${api.version}",
        description = "Read-only feature flag service. Flags are changed via pull requests to the flags file."))
public class Application {

    private static final Set<String> LOG_FORMATS = Set.of("text", "json");

    public static void main(String[] args) {
        // logback.xml includes logback-<LOG_FORMAT>.xml; any other value would silently disable all logging.
        String logFormat = System.getenv("LOG_FORMAT");
        if (logFormat != null && !LOG_FORMATS.contains(logFormat)) {
            System.err.println("Invalid LOG_FORMAT '" + logFormat + "': must be one of " + LOG_FORMATS);
            System.exit(1);
        }
        // The ASCII banner would be the only non-JSON output.
        Micronaut.build(args).mainClass(Application.class).banner(!"json".equals(logFormat)).start();
    }
}
