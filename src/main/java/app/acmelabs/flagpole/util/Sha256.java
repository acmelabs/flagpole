package app.acmelabs.flagpole.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256, used for the config version, ETags and rollout bucketing.
 */
public final class Sha256 {

    private Sha256() {
    }

    public static byte[] digest(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * @return the digest as lowercase hex (64 characters)
     */
    public static String hex(byte[] content) {
        return HexFormat.of().formatHex(digest(content));
    }
}
