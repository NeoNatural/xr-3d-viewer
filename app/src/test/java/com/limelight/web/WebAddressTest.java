package com.limelight.web;

import org.junit.Test;

import static org.junit.Assert.*;

public class WebAddressTest {
    @Test public void opensCustomSitesWithoutLosingTheVideoLocation() {
        assertEquals("https://example.org/watch?v=a%2Fb&t=5#player",
                WebAddress.fromInput("  example.org/watch?v=a%2Fb&t=5#player  "));
        assertEquals("https://example.org/", WebAddress.fromInput("HTTPS://Example.org"));
        assertEquals("https://xn--bcher-kva.example/", WebAddress.fromInput("bücher.example"));
        assertEquals("http://192.168.1.2:8080/video", WebAddress.fromInput("http://192.168.1.2:8080/video"));
        assertEquals("https://localhost:4433/", WebAddress.fromInput("localhost:4433"));
        assertEquals("https://[::1]:8040/", WebAddress.fromInput("[::1]:8040"));
    }

    @Test public void rejectsInvalidInputsWithoutOpeningAnything() {
        for (String input : new String[] { "", "  ", "/video", "//example.org", "#clip", "?v=1",
                "https://", "https:///video", "not a host", "https://x.test:99999",
                "file:///clip", "intent://open", "javascript:alert(1)", "data:text/html,test" }) {
            assertNull(input, WebAddress.fromInput(input));
        }
    }

    @Test public void linksAndRedirectsUseOnlyWebSchemes() {
        assertTrue(WebAddress.isWebScheme("HTTP"));
        assertTrue(WebAddress.isWebScheme("https"));
        assertFalse(WebAddress.isWebScheme("file"));
        assertFalse(WebAddress.isWebScheme(null));
    }
}
