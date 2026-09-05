package com.gizmodata.quack.jdbc.transport;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.codec.DecodeLimits;
import com.gizmodata.quack.jdbc.codec.QuackConstants;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/** Parsed {@code jdbc:quack://...} URL. */
public record QuackUri(String host,
                       int port,
                       Optional<String> database,
                       boolean tls,
                       Optional<String> token,
                       Map<String, String> properties) {

    public static final String URL_PREFIX = "jdbc:quack:";

    @Override
    public String toString() {
        // Arbitrary fields and even property names can contain misplaced credentials.
        return "QuackUri[host=<redacted>, port=" + port + ", database=<redacted>, tls=" + tls
                + ", token=<redacted>, properties=<redacted>]";
    }

    /**
     * Prefix for extra-HTTP-header connection properties:
     * {@code httpHeader.<Header-Name>=<value>} sends the header with
     * every request (proxy/LB auth). Mirrors duckdb-quack's
     * {@code EXTRA_HTTP_HEADERS} secret parameter. Accepted via
     * connection {@link Properties} only — rejected on the JDBC URL so
     * a pasted URL cannot inject headers into the driver's requests.
     */
    public static final String HTTP_HEADER_PREFIX = "httpHeader.";

    public static boolean acceptsUrl(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    public static QuackUri parse(String url) {
        return parse(url, new Properties());
    }

    public static QuackUri parse(String url, Properties properties) {
        if (!acceptsUrl(url)) {
            throw new QuackException("Not a Quack JDBC URL");
        }
        String stripped = url.substring(URL_PREFIX.length());
        if (!stripped.startsWith("//")) {
            throw new QuackException("Quack JDBC URL must start with jdbc:quack://");
        }

        URI parsed;
        try {
            parsed = new URI("http:" + stripped);
        } catch (URISyntaxException e) {
            // URISyntaxException retains the complete input, including authentication data.
            throw new QuackException("Invalid Quack JDBC URL syntax at index " + e.getIndex());
        }

        String host = parsed.getHost();
        if (host == null || host.isEmpty()) {
            throw new QuackException("Quack JDBC URL is missing a valid host");
        }
        int port = parsed.getPort();
        if (port <= 0) {
            port = QuackConstants.DEFAULT_QUACK_PORT;
        }

        Optional<String> database = Optional.empty();
        String path = parsed.getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            String trimmed = path.startsWith("/") ? path.substring(1) : path;
            if (!trimmed.isEmpty()) {
                database = Optional.of(URLDecoder.decode(trimmed, StandardCharsets.UTF_8));
            }
        }

        Map<String, String> params = new LinkedHashMap<>();
        if (parsed.getRawQuery() != null) {
            for (String pair : parsed.getRawQuery().split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                params.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        }
        // tokenEnv/tokenFile make the driver read a local secret and send it
        // to the host in the URL — accepting them from the URL would let a
        // pasted or shared URL exfiltrate an arbitrary env var or file to an
        // attacker-chosen server. Connection Properties only.
        for (String key : new String[]{QuackTokenResolver.TOKEN_ENV, QuackTokenResolver.TOKEN_FILE}) {
            if (params.containsKey(key)) {
                throw new QuackException("Quack JDBC property " + key
                        + " is only accepted via connection Properties, not the JDBC URL");
            }
        }
        for (String key : params.keySet()) {
            if (key.startsWith(HTTP_HEADER_PREFIX)) {
                throw new QuackException("Quack JDBC httpHeader.* properties"
                        + " are only accepted via connection Properties, not the JDBC URL");
            }
        }
        for (String key : properties.stringPropertyNames()) {
            params.putIfAbsent(key, properties.getProperty(key));
            if (key.startsWith(HTTP_HEADER_PREFIX)) {
                validateHeader(key.substring(HTTP_HEADER_PREFIX.length()),
                        properties.getProperty(key));
            }
        }

        String tlsKey = params.containsKey("tls") ? "tls" : "useEncryption";
        boolean tls = parseBool(tlsKey, params.get(tlsKey));
        Optional<String> token = QuackTokenResolver.resolve(params);

        return new QuackUri(host, port, database, tls, token, params);
    }

    public URI httpUri() {
        try {
            return new URI((tls ? "https" : "http") + "://" + host + ":" + port + QuackConstants.QUACK_ENDPOINT);
        } catch (URISyntaxException e) {
            throw new QuackException("Failed to build Quack HTTP URI");
        }
    }

    public Duration connectTimeout() {
        return parseDurationProperty("connectTimeout", QuackHttpTransport.DEFAULT_CONNECT_TIMEOUT);
    }

    public Duration requestTimeout() {
        return parseDurationProperty("requestTimeout", QuackHttpTransport.DEFAULT_REQUEST_TIMEOUT);
    }

    public DecodeLimits decodeLimits() {
        return new DecodeLimits(
                (int) parseLimitProperty("maxResponseBytes", DecodeLimits.DEFAULT.maxResponseBytes(),
                        Integer.MAX_VALUE),
                parseLimitProperty("maxDecodedBytes", DecodeLimits.DEFAULT.maxDecodedBytes(), Long.MAX_VALUE),
                (int) parseLimitProperty("maxNestingDepth", DecodeLimits.DEFAULT.maxNestingDepth(), 128));
    }

    public String quackUri() {
        return "quack:" + host + ":" + port;
    }

    /**
     * Extra HTTP headers from {@code httpHeader.*} properties, in
     * property order. An empty value clears/omits the header.
     */
    public Map<String, String> extraHttpHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (!entry.getKey().startsWith(HTTP_HEADER_PREFIX)) {
                continue;
            }
            String name = entry.getKey().substring(HTTP_HEADER_PREFIX.length()).trim();
            String value = entry.getValue();
            if (value == null || value.isEmpty()) {
                continue;
            }
            headers.put(name, value);
        }
        return headers;
    }

    private static void validateHeader(String name, String value) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.chars().anyMatch(c ->
                c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == ':')) {
            throw new QuackException("Invalid HTTP header name in httpHeader.* property");
        }
        if (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
            throw new QuackException("HTTP header value must not contain CR/LF");
        }
        switch (trimmed.toLowerCase(Locale.ROOT)) {
            case "content-type", "accept", "content-length", "host" ->
                    throw new QuackException("HTTP header is reserved by the Quack protocol");
            default -> { }
        }
    }

    private static boolean parseBool(String key, String value) {
        if (value == null) return false;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off", "" -> false;
            default -> throw new QuackException("Quack JDBC property " + key
                    + " must be true/false, 1/0, yes/no, or on/off");
        };
    }

    private Duration parseDurationProperty(String key, Duration defaultValue) {
        String value = properties.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String trimmed = value.trim();
        try {
            Duration duration;
            if (trimmed.chars().allMatch(Character::isDigit)) {
                duration = Duration.ofSeconds(Long.parseLong(trimmed));
            } else {
                duration = Duration.parse(trimmed);
            }
            if (duration.isZero() || duration.isNegative()) {
                throw new QuackException("Quack JDBC property " + key + " must be positive");
            }
            return duration;
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new QuackException("Quack JDBC property " + key
                    + " must be a positive number of seconds or ISO-8601 duration");
        }
    }

    private long parseLimitProperty(String key, long defaultValue, long maximum) {
        String value = properties.get(key);
        if (value == null) return defaultValue;
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed > 0 && parsed <= maximum) return parsed;
        } catch (NumberFormatException ignored) {
            // Do not echo property values or retain a cause that could contain credentials.
        }
        throw new QuackException("Quack JDBC property " + key
                + " must be an integer between 1 and " + maximum);
    }
}
