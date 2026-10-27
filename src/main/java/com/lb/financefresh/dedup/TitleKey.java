package com.lb.financefresh.dedup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;

public final class TitleKey {

    private TitleKey() {}

    public static String normalize(String title) {
        String text = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFKC);
        text = text.toLowerCase(Locale.ROOT).replaceAll("\\p{P}+", " ");
        return text.replaceAll("\\s+", " ").trim();
    }

    public static String canonicalUrl(String url) {
        if (url == null) return "";
        String text = url.trim();
        int hash = text.indexOf('#');
        if (hash >= 0) text = text.substring(0, hash);
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text;
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
