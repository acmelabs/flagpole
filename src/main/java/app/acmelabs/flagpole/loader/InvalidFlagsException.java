package app.acmelabs.flagpole.loader;

import java.util.List;

/**
 * Thrown when a flags file cannot be parsed or fails validation. Carries every problem found.
 */
public class InvalidFlagsException extends RuntimeException {

    private final List<String> errors;

    public InvalidFlagsException(List<String> errors) {
        super("Invalid flags file: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public InvalidFlagsException(String error, Throwable cause) {
        super("Invalid flags file: " + error, cause);
        this.errors = List.of(error);
    }

    public List<String> getErrors() {
        return errors;
    }
}
