package com.gizmodata.quack.jdbc.transport;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.codec.DecodeLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuackUriTest {

    @Test
    void acceptsCanonicalUrl() {
        assertTrue(QuackUri.acceptsUrl("jdbc:quack://localhost:9494"));
        assertTrue(QuackUri.acceptsUrl("jdbc:quack://h"));
        assertFalse(QuackUri.acceptsUrl("jdbc:postgresql://localhost:9494"));
        assertFalse(QuackUri.acceptsUrl(null));
    }

    @Test
    void parsesHostAndDefaultPort() {
        QuackUri u = QuackUri.parse("jdbc:quack://duck.example.com");
        assertEquals("duck.example.com", u.host());
        assertEquals(9494, u.port());
        assertTrue(u.database().isEmpty());
        assertFalse(u.tls());
    }

    @Test
    void parsesHostPortDatabaseToken() {
        QuackUri u = QuackUri.parse("jdbc:quack://h.example:1234/mydb?token=abc&tls=true");
        assertEquals("h.example", u.host());
        assertEquals(1234, u.port());
        assertEquals("mydb", u.database().orElseThrow());
        assertEquals("abc", u.token().orElseThrow());
        assertTrue(u.tls());
        assertEquals("https://h.example:1234/quack", u.httpUri().toString());
    }

    @Test
    void propertiesFillInMissingValues() {
        Properties props = new Properties();
        props.setProperty("token", "from-props");
        QuackUri u = QuackUri.parse("jdbc:quack://h:9494", props);
        assertEquals("from-props", u.token().orElseThrow());
    }

    @Test
    void acceptsPasswordAsTokenAlias() {
        Properties props = new Properties();
        props.setProperty("password", "from-password");
        QuackUri u = QuackUri.parse("jdbc:quack://h:9494", props);
        assertEquals("from-password", u.token().orElseThrow());
    }

    @Test
    void rejectsTokenEnvAndTokenFileOnTheUrl() {
        // A pasted URL must not be able to read a local secret and ship it
        // to whatever host the URL names.
        QuackException envEx = assertThrows(QuackException.class,
                () -> QuackUri.parse("jdbc:quack://h:9494?tokenEnv=AWS_SECRET_ACCESS_KEY"));
        assertTrue(envEx.getMessage().contains("tokenEnv"));

        QuackException fileEx = assertThrows(QuackException.class,
                () -> QuackUri.parse("jdbc:quack://h:9494?tokenFile=/etc/passwd"));
        assertTrue(fileEx.getMessage().contains("tokenFile"));
    }

    @Test
    void tlsFlagTogglesScheme() {
        QuackUri u = QuackUri.parse("jdbc:quack://h:9494?tls=false");
        assertEquals("http://h:9494/quack", u.httpUri().toString());
        QuackUri u2 = QuackUri.parse("jdbc:quack://h:9494?tls=true");
        assertEquals("https://h:9494/quack", u2.httpUri().toString());
    }

    @Test
    void parsesTimeoutProperties() {
        QuackUri u = QuackUri.parse("jdbc:quack://h:9494?connectTimeout=5&requestTimeout=PT30S");
        assertEquals(Duration.ofSeconds(5), u.connectTimeout());
        assertEquals(Duration.ofSeconds(30), u.requestTimeout());
    }

    @Test
    void timeoutPropertiesCanComeFromProperties() {
        Properties props = new Properties();
        props.setProperty("connectTimeout", "7");
        props.setProperty("requestTimeout", "PT45S");

        QuackUri u = QuackUri.parse("jdbc:quack://h:9494", props);

        assertEquals(Duration.ofSeconds(7), u.connectTimeout());
        assertEquals(Duration.ofSeconds(45), u.requestTimeout());
    }

    @Test
    void timeoutPropertiesRejectInvalidValues() {
        assertThrows(RuntimeException.class,
                () -> QuackUri.parse("jdbc:quack://h:9494?connectTimeout=0").connectTimeout());
        assertThrows(RuntimeException.class,
                () -> QuackUri.parse("jdbc:quack://h:9494?requestTimeout=forever").requestTimeout());
    }

    @Test
    void decodeLimitsDefaultIndependently() {
        assertEquals(DecodeLimits.DEFAULT, QuackUri.parse("jdbc:quack://h").decodeLimits());
        assertEquals(new DecodeLimits(1024, DecodeLimits.DEFAULT.maxDecodedBytes(),
                        DecodeLimits.DEFAULT.maxNestingDepth()),
                QuackUri.parse("jdbc:quack://h?maxResponseBytes=1024").decodeLimits());
        assertEquals(new DecodeLimits(DecodeLimits.DEFAULT.maxResponseBytes(), 4096,
                        DecodeLimits.DEFAULT.maxNestingDepth()),
                QuackUri.parse("jdbc:quack://h?maxDecodedBytes=4096").decodeLimits());
        assertEquals(new DecodeLimits(DecodeLimits.DEFAULT.maxResponseBytes(),
                        DecodeLimits.DEFAULT.maxDecodedBytes(), 8),
                QuackUri.parse("jdbc:quack://h?maxNestingDepth=8").decodeLimits());
    }

    @Test
    void decodeLimitsComeFromPropertiesWithUrlPrecedence() {
        Properties properties = propsOf("maxResponseBytes", " 4096 ");
        properties.setProperty("maxDecodedBytes", "5000000000");
        properties.setProperty("maxNestingDepth", "16");

        assertEquals(new DecodeLimits(4096, 5000000000L, 16),
                QuackUri.parse("jdbc:quack://h", properties).decodeLimits());
        assertEquals(new DecodeLimits(2048, 6000000000L, 32),
                QuackUri.parse("jdbc:quack://h?maxResponseBytes=2048"
                        + "&maxDecodedBytes=6000000000&maxNestingDepth=32", properties).decodeLimits());
    }

    @Test
    void decodeLimitsAcceptInclusiveNumericBounds() {
        assertEquals(new DecodeLimits(1, 1, 1), QuackUri.parse("jdbc:quack://h"
                + "?maxResponseBytes=1&maxDecodedBytes=1&maxNestingDepth=1").decodeLimits());
        assertEquals(new DecodeLimits(Integer.MAX_VALUE, Long.MAX_VALUE, 128),
                QuackUri.parse("jdbc:quack://h?maxResponseBytes=2147483647"
                        + "&maxDecodedBytes=9223372036854775807&maxNestingDepth=128").decodeLimits());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "0", "-1", "1.5", "64MiB", "NaN",
            "9223372036854775808", "sensitive-value"})
    void decodeLimitsRejectInvalidValuesWithoutCredentials(String value) {
        for (String key : new String[]{"maxResponseBytes", "maxDecodedBytes", "maxNestingDepth"}) {
            Properties properties = propsOf(key, value);
            properties.setProperty("password", "password-secret");
            properties.setProperty("httpHeader.Authorization", "Bearer header-secret");
            QuackUri uri = QuackUri.parse("jdbc:quack://h?token=url-secret", properties);

            QuackException error = assertThrows(QuackException.class, uri::decodeLimits);

            assertTrue(error.getMessage().contains(key));
            assertTrue(error.getMessage().contains("integer between 1 and"));
            assertFalse(error.getMessage().contains("secret"));
            assertFalse(error.getMessage().contains("sensitive-value"));
            assertNull(error.getCause(), "numeric parser causes must not expose the invalid value");
        }
    }

    @ParameterizedTest
    @CsvSource({"maxResponseBytes,2147483648,2147483647", "maxNestingDepth,129,128"})
    void decodeLimitsRejectOutOfRangeValues(String key, String value, String maximum) {
        QuackException error = assertThrows(QuackException.class,
                () -> QuackUri.parse("jdbc:quack://h?" + key + "=" + value).decodeLimits());
        assertTrue(error.getMessage().contains(key));
        assertTrue(error.getMessage().endsWith(maximum));
    }

    @Test
    void extraHttpHeadersComeFromProperties() {
        Properties props = new Properties();
        props.setProperty("httpHeader.X-Proxy-Auth", "s3cret");
        props.setProperty("httpHeader.X-Trace-Id", "abc123");
        props.setProperty("httpHeader.X-Cleared", "");

        QuackUri u = QuackUri.parse("jdbc:quack://h:9494", props);

        assertEquals("s3cret", u.extraHttpHeaders().get("X-Proxy-Auth"));
        assertEquals("abc123", u.extraHttpHeaders().get("X-Trace-Id"));
        assertFalse(u.extraHttpHeaders().containsKey("X-Cleared"),
                "empty-valued header should be omitted");
    }

    @Test
    void extraHttpHeadersRejectedOnUrl() {
        QuackException e = assertThrows(QuackException.class,
                () -> QuackUri.parse("jdbc:quack://h:9494?httpHeader.X-Evil=1"));
        assertTrue(e.getMessage().contains("connection Properties"));
    }

    @Test
    void extraHttpHeadersValidated() {
        assertThrows(QuackException.class, () -> QuackUri.parse("jdbc:quack://h:9494",
                propsOf("httpHeader.", "v")));                       // empty name
        assertThrows(QuackException.class, () -> QuackUri.parse("jdbc:quack://h:9494",
                propsOf("httpHeader.Bad Name", "v")));                // space in name
        assertThrows(QuackException.class, () -> QuackUri.parse("jdbc:quack://h:9494",
                propsOf("httpHeader.X-H", "a\r\nInjected: 1")));      // CRLF in value
        assertThrows(QuackException.class, () -> QuackUri.parse("jdbc:quack://h:9494",
                propsOf("httpHeader.Content-Type", "text/plain")));   // reserved
        assertThrows(QuackException.class, () -> QuackUri.parse("jdbc:quack://h:9494",
                propsOf("httpHeader.host", "evil.example")));         // reserved, case-insensitive
    }

    private static Properties propsOf(String key, String value) {
        Properties props = new Properties();
        props.setProperty(key, value);
        return props;
    }
}
