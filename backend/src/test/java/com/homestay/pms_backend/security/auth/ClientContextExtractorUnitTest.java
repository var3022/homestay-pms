package com.homestay.pms_backend.security.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Step 3.3C.4: Client Context Extractor Unit Tests")
class ClientContextExtractorUnitTest {

    @Test
    @DisplayName("Default Zero-Trust: Untrusted X-Forwarded-For is ignored; remote address is used")
    void shouldIgnoreUntrustedXForwardedForByDefault() {
        ClientContextExtractor extractor = new ClientContextExtractor(""); // No trusted proxies configured

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.50");
        request.addHeader("X-Forwarded-For", "203.0.113.195");
        request.addHeader("User-Agent", "Mozilla/5.0 UnitTester");

        ClientContext context = extractor.extract(request);

        assertThat(context.ipAddress()).isEqualTo("192.168.1.50");
        assertThat(context.userAgent()).isEqualTo("Mozilla/5.0 UnitTester");
    }

    @Test
    @DisplayName("Trusted Proxy: Inspects X-Forwarded-For when remote address is in trusted proxy list")
    void shouldExtractClientIpFromTrustedProxy() {
        ClientContextExtractor extractor = new ClientContextExtractor("10.0.0.1, 127.0.0.1");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1"); // Direct socket from trusted reverse proxy
        request.addHeader("X-Forwarded-For", "203.0.113.195, 10.0.0.1");
        request.addHeader("User-Agent", "Mozilla/5.0 TrustedClient");

        ClientContext context = extractor.extract(request);

        assertThat(context.ipAddress()).isEqualTo("203.0.113.195");
        assertThat(context.userAgent()).isEqualTo("Mozilla/5.0 TrustedClient");
    }

    @Test
    @DisplayName("Trusted Proxy: Falls back to remote address if X-Forwarded-For contains invalid format")
    void shouldFallbackToRemoteAddrIfForwardedForMalformed() {
        ClientContextExtractor extractor = new ClientContextExtractor("127.0.0.1");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "invalid<script>ip");

        ClientContext context = extractor.extract(request);

        assertThat(context.ipAddress()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("User-Agent Bounding: Truncates User-Agent exceeding database VARCHAR(255) constraint")
    void shouldTruncateExcessivelyLongUserAgent() {
        ClientContextExtractor extractor = new ClientContextExtractor("");

        String veryLongUserAgent = "A".repeat(400);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", veryLongUserAgent);

        ClientContext context = extractor.extract(request);

        assertThat(context.userAgent()).hasSize(255);
        assertThat(context.userAgent()).isEqualTo("A".repeat(255));
    }

    @Test
    @DisplayName("User-Agent Sanitization: Strips control characters from User-Agent")
    void shouldStripControlCharactersFromUserAgent() {
        ClientContextExtractor extractor = new ClientContextExtractor("");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "LegitAgent\u0000\u0007\u001b[31mExploit");

        ClientContext context = extractor.extract(request);

        assertThat(context.userAgent()).doesNotContain("\u0000");
        assertThat(context.userAgent()).doesNotContain("\u0007");
        assertThat(context.userAgent()).doesNotContain("\u001b");
        assertThat(context.userAgent()).contains("LegitAgent");
    }

    @Test
    @DisplayName("Null Safety: Safely handles null request or missing headers")
    void shouldHandleNullOrMissingHeadersSafely() {
        ClientContextExtractor extractor = new ClientContextExtractor("");

        ClientContext nullRequestContext = extractor.extract(null);
        assertThat(nullRequestContext.ipAddress()).isNull();
        assertThat(nullRequestContext.userAgent()).isEqualTo("Unknown");

        MockHttpServletRequest emptyRequest = new MockHttpServletRequest();
        emptyRequest.setRemoteAddr(null);
        ClientContext emptyContext = extractor.extract(emptyRequest);
        assertThat(emptyContext.ipAddress()).isNull();
        assertThat(emptyContext.userAgent()).isEqualTo("Unknown");
    }
}
