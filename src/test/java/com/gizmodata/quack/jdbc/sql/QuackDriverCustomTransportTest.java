package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.codec.DecodeLimits;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.transport.QuackHttpTransport;
import com.gizmodata.quack.jdbc.transport.QuackTransport;
import com.gizmodata.quack.jdbc.transport.QuackUri;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuackDriverCustomTransportTest {

    @Test
    void driverConnectUsesCustomTransportFactory() throws SQLException {
        QuackDriver driver = new QuackDriver();
        RecordingTransport transport = new RecordingTransport("custom-connection");
        AtomicReference<QuackUri> factoryUri = new AtomicReference<>();

        Properties properties = new Properties();
        properties.setProperty("token", "prop-token");
        properties.setProperty("tls", "true");
        properties.setProperty("connectTimeout", "99");
        properties.setProperty("requestTimeout", "99");
        properties.setProperty("maxResponseBytes", "4096");
        properties.setProperty("maxDecodedBytes", "5000000000");
        properties.setProperty("maxNestingDepth", "16");

        try (Connection connection = driver.connect(
                "jdbc:quack://example.test:1234/db?token=url-token&connectTimeout=3&requestTimeout=PT4S",
                properties,
                uri -> {
                    factoryUri.set(uri);
                    return transport;
                })) {
            QuackConnection quackConnection = assertInstanceOf(QuackConnection.class, connection);
            assertEquals("custom-connection", quackConnection.session().connectionId());
            assertEquals("db", connection.getCatalog());
        }

        QuackUri uri = factoryUri.get();
        assertEquals("example.test", uri.host());
        assertEquals(1234, uri.port());
        assertEquals(Optional.of("db"), uri.database());
        assertTrue(uri.tls());
        assertEquals(Optional.of("url-token"), uri.token());
        assertEquals(Duration.ofSeconds(3), uri.connectTimeout());
        assertEquals(Duration.ofSeconds(4), uri.requestTimeout());
        assertEquals(new DecodeLimits(4096, 5000000000L, 16), uri.decodeLimits());

        assertEquals(3, transport.requests.size());
        QuackMessage firstRequest = transport.requests.get(0);
        QuackMessage secondRequest = transport.requests.get(1);
        QuackMessage thirdRequest = transport.requests.get(2);
        assertInstanceOf(QuackMessage.ConnectionRequest.class, firstRequest);
        assertEquals(Optional.of("url-token"),
                ((QuackMessage.ConnectionRequest) firstRequest).authString());
        assertEquals("USE \"db\".\"main\"", assertInstanceOf(QuackMessage.PrepareRequest.class, secondRequest).sql());
        assertInstanceOf(QuackMessage.DisconnectMessage.class, thirdRequest);
        assertEquals(Optional.of("custom-connection"), thirdRequest.header().connectionId());
    }

    @Test
    void urlCatalogIsQuotedAndSelectedOnlyOnce() throws SQLException {
        RecordingTransport transport = new RecordingTransport("catalog-test");
        try (Connection c = new QuackDriver().connect("jdbc:quack://example.test/odd%20%22catalog",
                new Properties(), uri -> transport)) {
            assertEquals("odd \"catalog", c.getCatalog());
            c.setCatalog(c.getCatalog());
            assertEquals(2, transport.requests.size());
            assertEquals("USE \"odd \"\"catalog\".\"main\"",
                    assertInstanceOf(QuackMessage.PrepareRequest.class, transport.requests.get(1)).sql());
        }
    }

    @Test
    void failedCatalogSelectionDisconnectsTheNewSession() {
        RecordingTransport transport = new RecordingTransport("failed-catalog");
        transport.failCatalog = true;
        SQLException error = assertThrows(SQLException.class,
                () -> new QuackDriver().connect("jdbc:quack://example.test/missing",
                        new Properties(), uri -> transport));
        assertTrue(error.getMessage().contains("unknown catalog"));
        assertEquals(3, transport.requests.size());
        assertInstanceOf(QuackMessage.ConnectionRequest.class, transport.requests.get(0));
        assertInstanceOf(QuackMessage.PrepareRequest.class, transport.requests.get(1));
        assertInstanceOf(QuackMessage.DisconnectMessage.class, transport.requests.get(2));
    }

    @Test
    void nullTransportFromFactoryFailsCleanly() {
        QuackDriver driver = new QuackDriver();

        SQLException exception = assertThrows(SQLException.class,
                () -> driver.connect("jdbc:quack://example.test:1234", new Properties(), uri -> null));

        assertTrue(exception.getMessage().contains("transportFactory returned null"),
                "expected null factory result message, got: " + exception.getMessage());
    }

    @Test
    void propertyInfoIncludesTimeouts() {
        QuackDriver driver = new QuackDriver();

        DriverPropertyInfo[] propertyInfo = driver.getPropertyInfo("jdbc:quack://example.test:1234",
                new Properties());

        assertEquals("token", propertyInfo[0].name);
        assertEquals("password", propertyInfo[1].name);
        assertEquals("tokenEnv", propertyInfo[2].name);
        assertEquals("tokenFile", propertyInfo[3].name);
        assertEquals("tls", propertyInfo[4].name);
        assertEquals("connectTimeout", propertyInfo[5].name);
        assertEquals("requestTimeout", propertyInfo[6].name);
    }

    @Test
    void propertyInfoIncludesOptionalDecodeLimitsAndDefaults() {
        Properties properties = new Properties();
        properties.setProperty("maxResponseBytes", "4096");
        properties.setProperty("maxDecodedBytes", "5000000000");
        properties.setProperty("maxNestingDepth", "16");
        DriverPropertyInfo[] descriptors = new QuackDriver().getPropertyInfo(null, properties);

        String[] names = {"maxResponseBytes", "maxDecodedBytes", "maxNestingDepth"};
        String[] defaults = {Integer.toString(DecodeLimits.DEFAULT.maxResponseBytes()),
                Long.toString(DecodeLimits.DEFAULT.maxDecodedBytes()),
                Integer.toString(DecodeLimits.DEFAULT.maxNestingDepth())};
        assertEquals(10, descriptors.length);
        for (int i = 0; i < names.length; i++) {
            DriverPropertyInfo descriptor = descriptors[7 + i];
            assertEquals(names[i], descriptor.name);
            assertEquals(properties.getProperty(names[i]), descriptor.value);
            assertFalse(descriptor.required);
            assertTrue(descriptor.description.contains("default: " + defaults[i]));
        }
        assertTrue(descriptors[7].description.contains("bytes"));
        assertTrue(descriptors[8].description.contains("bytes"));
        assertTrue(descriptors[9].description.contains("1..128"));
        assertEquals(10, new QuackDriver().getPropertyInfo(null, null).length);
    }

    @Test
    void defaultHttpTransportRejectsInvalidLimitsBeforeConnecting() {
        for (String key : new String[]{"maxResponseBytes", "maxDecodedBytes", "maxNestingDepth"}) {
            Properties properties = new Properties();
            properties.setProperty(key, "invalid-sensitive-value");
            properties.setProperty("password", "password-secret");

            SQLException error = assertThrows(SQLException.class,
                    () -> new QuackDriver().connect("jdbc:quack://example.test?token=url-secret", properties));

            assertTrue(error.getMessage().contains(key));
            assertFalse(error.getMessage().contains("secret"));
            assertFalse(error.getMessage().contains("sensitive-value"));
            assertInstanceOf(QuackException.class, error.getCause());
        }
    }

    @Test
    void invalidTlsFailsBeforeTransportCreation() {
        for (String key : new String[]{"tls", "useEncryption"}) {
            for (boolean inUrl : new boolean[]{true, false}) {
                Properties properties = new Properties();
                if (!inUrl) properties.setProperty(key, "treu");
                SQLException error = assertThrows(SQLException.class, () -> new QuackDriver().connect(
                        "jdbc:quack://example.test?token=test-secret" + (inUrl ? "&" + key + "=treu" : ""),
                        properties, uri -> { throw new AssertionError("Must not create a transport for invalid TLS"); }));
                assertTrue(error.getMessage().contains(key));
                assertFalse(error.getMessage().contains("test-secret"));
                assertInstanceOf(QuackException.class, error.getCause());
            }
        }
    }

    @Test
    void sessionKeepsQuackHttpTransportConstructorForBinaryCompatibility() throws Exception {
        Constructor<QuackSession> constructor = QuackSession.class.getConstructor(
                QuackUri.class, QuackHttpTransport.class);
        QuackUri uri = QuackUri.parse("jdbc:quack://example.test:1234");
        QuackHttpTransport transport = new QuackHttpTransport(URI.create("http://example.test:1234/quack"));

        QuackSession session = constructor.newInstance(uri, transport);

        assertEquals(uri, session.uri());
    }

    private static final class RecordingTransport implements QuackTransport {

        private final String connectionId;
        private final List<QuackMessage> requests = new ArrayList<>();
        private boolean failCatalog;

        RecordingTransport(String connectionId) {
            this.connectionId = connectionId;
        }

        @Override
        public QuackMessage send(QuackMessage request) {
            requests.add(request);
            if (request instanceof QuackMessage.ConnectionRequest) {
                long clientQueryId = request.header().clientQueryId().orElse(0L);
                return new QuackMessage.ConnectionResponse(
                        MessageHeader.of(MessageType.CONNECTION_RESPONSE)
                                .withConnectionId(connectionId)
                                .withClientQueryId(clientQueryId),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
            }
            if (request instanceof QuackMessage.DisconnectMessage) {
                long clientQueryId = request.header().clientQueryId().orElse(0L);
                return new QuackMessage.SuccessResponse(
                        MessageHeader.of(MessageType.SUCCESS_RESPONSE)
                                .withConnectionId(connectionId)
                                .withClientQueryId(clientQueryId));
            }
            if (request instanceof QuackMessage.PrepareRequest) {
                if (failCatalog) throw new QuackException("unknown catalog");
                return new QuackMessage.PrepareResponse(
                        MessageHeader.of(MessageType.PREPARE_RESPONSE).withConnectionId(connectionId),
                        List.of(), List.of(), false, List.of(), new HugeIntParts(0, 0));
            }
            throw new AssertionError("unexpected request: " + request.getClass().getSimpleName());
        }
    }
}
