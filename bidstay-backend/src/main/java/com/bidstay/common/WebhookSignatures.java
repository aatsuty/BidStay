package com.bidstay.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class WebhookSignatures {
    private WebhookSignatures() {}

    public static boolean matches(String secret, byte[] payload, String hexSignature) {
        if (secret.isBlank() || hexSignature == null || hexSignature.length() != 64) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(payload), HexFormat.of().parseHex(hexSignature));
        } catch (IllegalArgumentException invalid) {
            return false;
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
