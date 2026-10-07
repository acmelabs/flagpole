package app.acmelabs.flagpole.eval;

import app.acmelabs.flagpole.model.FlagDefinition;
import app.acmelabs.flagpole.model.FlagSnapshot;
import app.acmelabs.flagpole.util.Sha256;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stateless flag evaluation of a known flag (unknown flags are a 404 at the API). Rules, in order:
 * <ol>
 *   <li>{@code enabled: false}: false</li>
 *   <li>user in allow list: true</li>
 *   <li>rollout 100: true; no user and rollout &lt; 100: false</li>
 *   <li>otherwise: {@link #bucket(String, String)} &lt; rollout</li>
 * </ol>
 */
@Singleton
public class FlagEvaluator {

    public record Decision(boolean enabled, String reason) {
    }

    public boolean evaluate(FlagDefinition flag, @Nullable String userId) {
        return decide(flag, userId).enabled();
    }

    public Decision decide(FlagDefinition flag, @Nullable String userId) {
        if (!flag.enabled()) {
            return new Decision(false, "disabled");
        }
        boolean hasUser = userId != null && !userId.isBlank();
        if (hasUser && flag.allow().contains(userId)) {
            return new Decision(true, "allow list");
        }
        if (flag.rollout() >= FlagDefinition.FULL_ROLLOUT) {
            return new Decision(true, "full rollout");
        }
        if (!hasUser) {
            return new Decision(false, "no user, partial rollout");
        }
        int bucket = bucket(flag.name(), userId);
        boolean enabled = bucket < flag.rollout();
        return new Decision(enabled, "bucket " + bucket + (enabled ? " < " : " >= ") + flag.rollout());
    }

    /**
     * Evaluates every flag in the snapshot for one user, in flag name order.
     */
    public Map<String, Boolean> evaluateAll(FlagSnapshot snapshot, @Nullable String userId) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        snapshot.flags().forEach((name, flag) -> result.put(name, evaluate(flag, userId)));
        return result;
    }

    /**
     * First 4 bytes of SHA-256("flagName:userId") as an unsigned int, mod 100.
     * Stable across JVMs, languages and restarts (unlike {@link String#hashCode()}).
     */
    public static int bucket(String flagName, String userId) {
        byte[] digest = Sha256.digest((flagName + ":" + userId).getBytes(StandardCharsets.UTF_8));
        long unsigned = Integer.toUnsignedLong(ByteBuffer.wrap(digest, 0, 4).getInt());
        return (int) (unsigned % 100);
    }
}
