package com.limelight.web;

import java.util.Locale;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

final class WebAddress {
    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");
    private static final Pattern HOST_PORT = Pattern.compile("^[^/:?#]+:[0-9]+(?:[/?#].*)?$");

    static boolean isWebScheme(String scheme) {
        return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
    }

    /** Accept typed hostnames and pasted links. Navigation always receives an absolute web URL. */
    static String fromInput(String input) {
        String address = input.trim();
        if (address.isEmpty() || address.startsWith("/") || address.startsWith("?")
                || address.startsWith("#") || address.contains("\\")) return null;

        String lower = address.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            if (SCHEME.matcher(address).find() && !HOST_PORT.matcher(address).matches()) return null;
            address = "https://" + address;
        }
        String afterScheme = address.substring(address.indexOf("://") + 3);
        if (afterScheme.startsWith("/") || afterScheme.isEmpty()) return null;

        HttpUrl url = HttpUrl.parse(address);
        return url == null ? null : url.toString();
    }
}
