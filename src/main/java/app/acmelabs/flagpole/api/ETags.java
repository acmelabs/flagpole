package app.acmelabs.flagpole.api;

import app.acmelabs.flagpole.util.Sha256;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.MutableHttpResponse;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Config-version based ETags, so clients can poll cheaply with If-None-Match.
 */
final class ETags {

    private ETags() {
    }

    static String of(String version) {
        return "\"" + version + "\"";
    }

    /**
     * ETag for a per-user response: the config version plus a hash of the userId, so an ETag from one user's
     * response never yields a 304 for another user. The userId is hashed because it may contain characters
     * (such as quotes) that are not allowed in an ETag.
     */
    static String forUser(String version, @Nullable String userId) {
        if (userId == null) {
            return of(version);
        }
        String userHash = Sha256.hex(userId.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        return of(version + "-" + userHash);
    }

    /**
     * True if the If-None-Match header matches the given ETag (weak comparison, '*' and lists supported).
     */
    static boolean matches(@Nullable String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        return Arrays.stream(ifNoneMatch.split(","))
                .map(String::trim)
                .map(tag -> tag.startsWith("W/") ? tag.substring(2) : tag)
                .anyMatch(tag -> tag.equals("*") || tag.equals(etag));
    }

    /**
     * Returns 304 if the client already has this version, otherwise 200 with the body; both carry the ETag.
     */
    static <T> MutableHttpResponse<T> conditional(@Nullable String ifNoneMatch, String etag, T body) {
        MutableHttpResponse<T> response = matches(ifNoneMatch, etag) ? HttpResponse.notModified() : HttpResponse.ok(body);
        return response.header(HttpHeaders.ETAG, etag).header(HttpHeaders.CACHE_CONTROL, "no-cache");
    }
}
