package app.acmelabs.flagpole.loader;

import app.acmelabs.flagpole.model.FlagDefinition;
import app.acmelabs.flagpole.model.FlagSnapshot;
import app.acmelabs.flagpole.util.Sha256;
import jakarta.inject.Singleton;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Parses and validates raw flags file content into an immutable {@link FlagSnapshot}.
 * Uses SnakeYAML's safe constructor into plain maps and validates by hand, which keeps
 * the parser reflection-free (and therefore native-image friendly).
 */
@Singleton
public class FlagFileParser {

    static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_.-]*$");
    private static final Set<String> TOP_LEVEL_KEYS = Set.of("flags");
    private static final Set<String> FLAG_KEYS = Set.of("enabled", "rollout", "allow", "description");

    private final Clock clock;

    public FlagFileParser() {
        this(Clock.systemUTC());
    }

    FlagFileParser(Clock clock) {
        this.clock = clock;
    }

    public FlagSnapshot parse(byte[] content) {
        Object root = load(content);
        List<String> errors = new ArrayList<>();
        Map<String, FlagDefinition> flags = new TreeMap<>();

        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new InvalidFlagsException(List.of("top level must be a mapping with a 'flags' key"));
        }
        for (Object key : rootMap.keySet()) {
            if (!TOP_LEVEL_KEYS.contains(String.valueOf(key))) {
                errors.add("unknown top-level key '" + key + "'");
            }
        }
        if (!rootMap.containsKey("flags")) {
            errors.add("missing required top-level key 'flags'");
        } else {
            Object flagsNode = rootMap.get("flags");
            if (flagsNode instanceof Map<?, ?> flagsMap) {
                flagsMap.forEach((name, def) -> parseFlag(name, def, errors, flags));
            } else if (flagsNode != null) {
                errors.add("'flags' must be a mapping of flag name to definition");
            }
        }

        if (!errors.isEmpty()) {
            throw new InvalidFlagsException(errors);
        }
        return new FlagSnapshot(flags, Sha256.hex(content), clock.instant());
    }

    private static Object load(byte[] content) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try {
            return new Yaml(new SafeConstructor(options), new Representer(new DumperOptions()), new DumperOptions(),
                    options, new StrictBoolResolver()).load(new String(content, StandardCharsets.UTF_8));
        } catch (YAMLException e) {
            throw new InvalidFlagsException("malformed YAML: " + e.getMessage(), e);
        }
    }

    private static void parseFlag(Object rawName, Object rawDef, List<String> errors, Map<String, FlagDefinition> out) {
        if (!(rawName instanceof String name) || !NAME_PATTERN.matcher(name).matches()) {
            errors.add("flag name '" + rawName + "' must match " + NAME_PATTERN.pattern());
            return;
        }
        String prefix = "flag '" + name + "': ";
        if (!(rawDef instanceof Map<?, ?> def)) {
            errors.add(prefix + "definition must be a mapping");
            return;
        }
        int errorCount = errors.size();

        for (Object key : def.keySet()) {
            if (!FLAG_KEYS.contains(String.valueOf(key))) {
                errors.add(prefix + "unknown key '" + key + "' (allowed: enabled, rollout, allow, description)");
            }
        }

        Object enabled = def.get("enabled");
        if (enabled == null) {
            errors.add(prefix + "'enabled' is required");
        } else if (!(enabled instanceof Boolean)) {
            errors.add(prefix + "'enabled' must be true or false");
        }

        int rollout = FlagDefinition.FULL_ROLLOUT;
        Object rawRollout = def.get("rollout");
        if (rawRollout != null) {
            if (rawRollout instanceof Integer r && r >= 0 && r <= 100) {
                rollout = r;
            } else {
                errors.add(prefix + "'rollout' must be an integer between 0 and 100, got '" + rawRollout + "'");
            }
        }

        Set<String> allow = new LinkedHashSet<>();
        Object rawAllow = def.get("allow");
        if (rawAllow instanceof List<?> list) {
            for (Object id : list) {
                if (id instanceof String s && !s.isBlank()) {
                    allow.add(s);
                } else {
                    errors.add(prefix + "'allow' entries must be non-blank strings (quote numeric IDs), got '" + id + "'");
                }
            }
        } else if (rawAllow != null) {
            errors.add(prefix + "'allow' must be a list of user IDs");
        }

        String description = null;
        Object rawDescription = def.get("description");
        if (rawDescription instanceof String s) {
            description = s;
        } else if (rawDescription != null) {
            errors.add(prefix + "'description' must be a string");
        }

        if (errors.size() == errorCount) {
            out.put(name, new FlagDefinition(name, (Boolean) enabled, rollout, allow, description));
        }
    }

    /**
     * SnakeYAML follows YAML 1.1, where yes/no/on/off are booleans. That would let {@code enabled: yes}
     * through and turn {@code allow: [no]} or a flag named {@code on} into booleans. Like YAML 1.2,
     * only true/false (in any of the three standard spellings) resolve to booleans here; the rest stay strings.
     */
    static final class StrictBoolResolver extends Resolver {

        private static final Pattern BOOL = Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$");

        @Override
        protected void addImplicitResolvers() {
            // Same as Resolver.addImplicitResolvers(), with the YAML 1.1 BOOL pattern swapped for BOOL above.
            addImplicitResolver(Tag.BOOL, BOOL, "tTfF", 10);
            addImplicitResolver(Tag.INT, INT, "-+0123456789");
            addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
            addImplicitResolver(Tag.MERGE, MERGE, "<", 10);
            addImplicitResolver(Tag.NULL, NULL, "~nN\0", 10);
            addImplicitResolver(Tag.NULL, EMPTY, null, 10);
            addImplicitResolver(Tag.TIMESTAMP, TIMESTAMP, "0123456789", 50);
        }
    }
}
