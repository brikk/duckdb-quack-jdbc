package com.gizmodata.quack.jdbc.transport;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.sql.QuackDriver;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class QuackUriDiagnosticsTest {
    private static final String SECRET = "B40_SYNTHETIC_CREDENTIAL";

    @Test
    void summaryOmitsRawStringsWithoutChangingOperationalValues() {
        Properties properties = new Properties();
        properties.setProperty("token", SECRET + "_token");
        properties.setProperty("password", SECRET + "_password");
        properties.setProperty("httpHeader.Authorization", "Bearer " + SECRET + "_header");
        properties.setProperty(SECRET + "_key", SECRET + "_value");
        QuackUri parsed = QuackUri.parse("jdbc:quack://example.test:1234/" + SECRET + "_database?tls=true", properties);
        assertFalse(parsed.toString().contains(SECRET));
        assertTrue(parsed.toString().contains("<redacted>"));
        assertEquals("example.test", parsed.host());
        assertEquals(1234, parsed.port());
        assertEquals(Optional.of(SECRET + "_database"), parsed.database());
        assertEquals(Optional.of(SECRET + "_token"), parsed.token());
        assertEquals(SECRET + "_password", parsed.properties().get("password"));
        assertEquals(SECRET + "_value", parsed.properties().get(SECRET + "_key"));
        assertEquals("Bearer " + SECRET + "_header", parsed.extraHttpHeaders().get("Authorization"));
        assertEquals("https://example.test:1234/quack", parsed.httpUri().toString());
        assertEquals("quack:example.test:1234", parsed.quackUri());

        QuackUri direct = new QuackUri(SECRET + "_host", 1234, Optional.of(SECRET + "_database"), true,
                Optional.of(SECRET), Map.of(SECRET, SECRET));
        assertFalse(direct.toString().contains(SECRET));
        assertEquals(SECRET + "_host", direct.host());
    }

    @Test
    void malformedUrlsNeverSurviveInMessagesOrUriParserCauses() {
        String encoded = "%42%34%30_SYNTHETIC_CREDENTIAL";
        for (String url : new String[]{null, "other://h?token=" + SECRET,
                "jdbc:quack:h?password=" + SECRET, "jdbc:quack://?token=" + SECRET,
                "jdbc:quack://bad host?token=" + SECRET,
                "jdbc:quack://user:" + SECRET + "@bad host/",
                "jdbc:quack://h/" + SECRET + "%ZZ", "jdbc:quack://h?token=" + SECRET + "%ZZ",
                "jdbc:quack://h:bad-port?password=" + SECRET,
                "jdbc:quack://h?token=" + encoded + "&bad=%ZZ"}) {
            QuackException error = assertThrows(QuackException.class, () -> QuackUri.parse(url));
            assertSafe(error, SECRET, encoded);
            assertNull(error.getCause(), "URI parser exceptions retain their original input");
        }
    }

    @Test
    void directHttpUriFailureDoesNotExposeRecordOrParserInput() {
        QuackUri uri = new QuackUri("bad " + SECRET, 9494, Optional.of(SECRET), false,
                Optional.of(SECRET), Map.of("password", SECRET));
        QuackException error = assertThrows(QuackException.class, uri::httpUri);
        assertSafe(error, SECRET);
        assertNull(error.getCause());
    }

    @Test
    void timeoutValidationKeepsOptionNameButDropsValueAndParserCause() {
        for (String key : new String[]{"connectTimeout", "requestTimeout"}) {
            for (String value : new String[]{SECRET, "999999999999999999999999", "PT999999999999999999999999S",
                    "PT0S", "-PT1S", "0"}) {
                Properties properties = new Properties();
                properties.setProperty(key, value);
                QuackUri uri = QuackUri.parse("jdbc:quack://h?token=" + SECRET, properties);
                QuackException error = assertThrows(QuackException.class, () -> {
                    if (key.equals("connectTimeout")) uri.connectTimeout();
                    else uri.requestTimeout();
                });
                assertTrue(error.getMessage().contains(key));
                assertSafe(error, SECRET, "999999999999999999999999");
                assertNull(error.getCause(), "duration parser causes may retain supplied values");
            }
        }
        QuackUri valid = QuackUri.parse("jdbc:quack://h?connectTimeout=2&requestTimeout=PT0.5S");
        assertEquals(Duration.ofSeconds(2), valid.connectTimeout());
        assertEquals(Duration.ofMillis(500), valid.requestTimeout());
    }

    @Test
    void rejectedHeaderConfigurationNeverEchoesArbitraryNamesOrValues() {
        for (String url : new String[]{"jdbc:quack://h?httpHeader." + SECRET + "=value",
                "jdbc:quack://h?httpHeader.Bad%20" + SECRET + "=value"}) {
            assertSafe(assertThrows(QuackException.class, () -> QuackUri.parse(url)), SECRET);
        }
        for (Map.Entry<String, String> header : Map.of(
                "Bad " + SECRET, "value", "Authorization", SECRET + "\r\nInjected: x",
                "Content-Type", SECRET).entrySet()) {
            Properties properties = new Properties();
            properties.setProperty("httpHeader." + header.getKey(), header.getValue());
            assertSafe(assertThrows(QuackException.class, () -> QuackUri.parse("jdbc:quack://h", properties)), SECRET);
        }
    }

    @Test
    void jdbcWrappingKeepsLocalFailuresSafeAndDoesNotRewriteTransportErrors() {
        SQLException malformed = assertThrows(SQLException.class, () -> new QuackDriver().connect(
                "jdbc:quack://bad host?token=" + SECRET, new Properties(),
                uri -> { throw new AssertionError("invalid URL reached transport creation"); }));
        assertSafe(malformed, SECRET);
        assertNull(malformed.getNextException());
        Properties properties = new Properties();
        properties.setProperty("connectTimeout", SECRET);
        SQLException timeout = assertThrows(SQLException.class,
                () -> new QuackDriver().connect("jdbc:quack://example.test?token=" + SECRET, properties));
        assertSafe(timeout, SECRET);
        assertNull(timeout.getNextException());

        SQLException downstream = assertThrows(SQLException.class, () -> new QuackDriver().connect(
                "jdbc:quack://example.test", new Properties(),
                uri -> request -> { throw new QuackException("synthetic transport detail"); }));
        assertEquals("synthetic transport detail", downstream.getMessage());
    }

    private static void assertSafe(Throwable error, String... secrets) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        for (String secret : secrets) assertFalse(trace.toString().contains(secret), "diagnostic exposed a synthetic secret");
    }
}
