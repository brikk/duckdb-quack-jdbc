package com.gizmodata.quack.jdbc.transport;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.QuackServerException;
import com.gizmodata.quack.jdbc.codec.DecodeLimits;
import com.gizmodata.quack.jdbc.codec.QuackConstants;
import com.gizmodata.quack.jdbc.message.MessageCodec;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * HTTP transport for the Quack protocol. Sends Quack messages as
 * {@code application/duckdb} request bodies to {@code POST /quack} and
 * returns the decoded server response (or raises a
 * {@link QuackServerException} for {@code ERROR_RESPONSE}).
 *
 * <p>For plain HTTP endpoints, every address returned by
 * {@link InetAddress#getAllByName(String)} is tried in order. This is
 * essential for hosts like {@code localhost} that resolve to both IPv4
 * and IPv6 — JDK {@link HttpClient} otherwise gives up after the first
 * address fails, even if a server is reachable on one of the other
 * addresses.
 *
 * <p>HTTPS endpoints keep the original hostname. Replacing the URI host
 * with a resolved IP address breaks TLS SNI and hostname verification.
 */
public final class QuackHttpTransport implements QuackTransport {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final String METADATA_HEADER = "X-Quack-Result-Metadata";
    private static final String KIND_HEADER = "X-Quack-Result-Kind";

    private final URI endpoint;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Map<String, String> extraHeaders;
    private final DecodeLimits decodeLimits;
    private final boolean requiresResultMetadata;

    public QuackHttpTransport(URI endpoint) {
        this(endpoint, HttpClient.newBuilder()
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                .build(),
                DEFAULT_REQUEST_TIMEOUT);
    }

    public QuackHttpTransport(URI endpoint, HttpClient httpClient, Duration requestTimeout) {
        this(endpoint, httpClient, requestTimeout, Map.of());
    }

    public QuackHttpTransport(URI endpoint, HttpClient httpClient, Duration requestTimeout,
                              Map<String, String> extraHeaders) {
        this(endpoint, httpClient, requestTimeout, extraHeaders, DecodeLimits.DEFAULT);
    }

    public QuackHttpTransport(URI endpoint, HttpClient httpClient, Duration requestTimeout,
                              Map<String, String> extraHeaders, DecodeLimits decodeLimits) {
        this(endpoint, httpClient, requestTimeout, extraHeaders, decodeLimits, true);
    }

    public QuackHttpTransport(URI endpoint, HttpClient httpClient, Duration requestTimeout,
                               Map<String, String> extraHeaders, DecodeLimits decodeLimits,
                               boolean requiresResultMetadata) {
        this.endpoint = endpoint;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
        this.extraHeaders = Map.copyOf(extraHeaders);
        this.decodeLimits = Objects.requireNonNull(decodeLimits, "decodeLimits");
        this.requiresResultMetadata = requiresResultMetadata;
    }

    public static QuackHttpTransport from(QuackUri uri) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(uri.connectTimeout())
                .build();
        return new QuackHttpTransport(uri.httpUri(), client, uri.requestTimeout(),
                uri.extraHttpHeaders(), uri.decodeLimits(), uri.requiresResultMetadata());
    }

    Duration requestTimeout() {
        return requestTimeout;
    }

    Optional<Duration> connectTimeout() {
        return httpClient.connectTimeout();
    }

    @Override
    public QuackMessage send(QuackMessage request) {
        byte[] body = MessageCodec.encode(request);

        URI[] attempts = endpointCandidates();
        IOException lastFailure = null;
        for (URI attempt : attempts) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(attempt)
                    .timeout(requestTimeout);
            // Extra headers first, protocol headers second: Content-Type
            // and Accept must win (QuackUri validation also rejects them).
            for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                if (METADATA_HEADER.equalsIgnoreCase(header.getKey())
                        || KIND_HEADER.equalsIgnoreCase(header.getKey())) continue;
                try {
                    builder.header(header.getKey(), header.getValue());
                } catch (IllegalArgumentException e) {
                    // JDK validation errors include the raw name or credential-bearing value.
                    throw new QuackException("Invalid or restricted HTTP header in extra headers");
                }
            }
            if (requiresResultMetadata) builder.setHeader(METADATA_HEADER, "1");
            HttpRequest httpRequest = builder
                    .setHeader("Content-Type", QuackConstants.DUCKDB_MIME_TYPE)
                    .setHeader("Accept", QuackConstants.DUCKDB_MIME_TYPE)
                    .POST(BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(httpRequest, BodyHandlers.ofInputStream());
            } catch (ConnectException | HttpConnectTimeoutException | ClosedChannelException e) {
                lastFailure = e;
                continue;
            } catch (IOException e) {
                throw new QuackException(buildErrorMessage(e, attempt), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new QuackException("Quack HTTP request was interrupted", e);
            }

            // A response means the POST may have executed. Body failures must
            // never enter the connect-only address fallback above.
            try (InputStream responseBody = response.body()) {
                if (response.statusCode() / 100 != 2) {
                    StringBuilder message = new StringBuilder("Quack HTTP returned status ")
                            .append(response.statusCode()).append(" from ").append(attempt);
                    // The quack server reports the underlying failure (e.g. a
                    // serialization mismatch) in this header, with an empty body.
                    response.headers().firstValue("EXCEPTION_WHAT")
                            .filter(detail -> !detail.isEmpty())
                            .ifPresent(detail -> message.append(": ").append(detail));
                    throw new QuackException(message.toString());
                }
                if (response.headers().firstValueAsLong("Content-Length").orElse(-1)
                        > decodeLimits.maxResponseBytes()) {
                    throw new QuackException("Quack HTTP response exceeds maxResponseBytes limit of "
                            + decodeLimits.maxResponseBytes() + " bytes");
                }
                QuackMessage decoded = MessageCodec.decode(readResponseBody(responseBody), decodeLimits);
                try {
                    decoded = annotateMetadata(decoded, response.headers());
                } catch (QuackProtocolException e) {
                    // The session cannot see a connection rejected during sideband parsing.
                    if (request instanceof QuackMessage.ConnectionRequest
                            && decoded instanceof QuackMessage.ConnectionResponse connection
                            && connection.header().connectionId().isPresent()) {
                        try {
                            send(new QuackMessage.DisconnectMessage(MessageHeader.of(MessageType.DISCONNECT_MESSAGE)
                                    .withConnectionId(connection.header().connectionId().get())
                                    .withClientQueryId(request.header().clientQueryId().orElse(0L) + 1)));
                        } catch (RuntimeException ignored) {
                            // Best-effort cleanup must not hide the original protocol rejection.
                        }
                    }
                    throw e;
                }
                if (decoded instanceof QuackMessage.ErrorResponse err) {
                    throw new QuackServerException(err.message());
                }
                return decoded;
            } catch (IOException e) {
                throw new QuackException(buildErrorMessage(e, attempt), e);
            }
        }

        throw new QuackException(buildExhaustedMessage(attempts, lastFailure), lastFailure);
    }

    private static QuackMessage annotateMetadata(QuackMessage decoded, HttpHeaders headers) {
        var versions = headers.allValues(METADATA_HEADER);
        var kinds = headers.allValues(KIND_HEADER);
        if (versions.isEmpty() && kinds.isEmpty()) return decoded;
        if (versions.size() != 1 || !"1".equals(versions.get(0))) {
            throw new QuackProtocolException("Invalid or unsupported X-Quack-Result-Metadata response header");
        }
        if (decoded instanceof QuackMessage.ConnectionResponse connection && kinds.isEmpty()) {
            return new QuackMessage.ConnectionResponse(connection.header(), connection.serverDuckdbVersion(),
                    connection.serverPlatform(), connection.quackVersion(), Optional.of(1L));
        }
        if (decoded instanceof QuackMessage.PrepareResponse prepare && kinds.size() == 1) {
            QuackMessage.ResultKind kind = switch (kinds.get(0)) {
                case "query" -> QuackMessage.ResultKind.QUERY;
                case "changed_rows" -> QuackMessage.ResultKind.CHANGED_ROWS;
                case "nothing" -> QuackMessage.ResultKind.NOTHING;
                default -> throw new QuackProtocolException("Invalid or unsupported X-Quack-Result-Kind response header");
            };
            return new QuackMessage.PrepareResponse(prepare.header(), prepare.resultTypes(), prepare.resultNames(),
                    prepare.needsMoreFetch(), prepare.results(), prepare.resultUuid(), Optional.of(kind));
        }
        throw new QuackProtocolException("Missing, duplicate or inconsistent Quack result metadata response headers");
    }

    private byte[] readResponseBody(InputStream body) throws IOException {
        int limit = decodeLimits.maxResponseBytes();
        byte[] bytes = new byte[Math.min(8192, limit)];
        int size = 0;
        while (true) {
            if (size == bytes.length) {
                // Probe before growing: exact-limit bodies are valid, and an
                // oversized body needs only one extra byte, never extra capacity.
                int next = body.read();
                if (next == -1) return bytes;
                if (size == limit) {
                    throw new QuackException("Quack HTTP response exceeds maxResponseBytes limit of "
                            + limit + " bytes");
                }
                bytes = Arrays.copyOf(bytes, (int) Math.min(limit, 2L * bytes.length));
                bytes[size++] = (byte) next;
            } else {
                int read = body.read(bytes, size, bytes.length - size);
                if (read == -1) return Arrays.copyOf(bytes, size);
                // The read is bounded by remaining capacity, so size cannot overflow.
                size += read;
            }
        }
    }

    URI[] endpointCandidates() {
        if ("https".equalsIgnoreCase(endpoint.getScheme())) {
            return new URI[]{endpoint};
        }
        InetAddress[] addresses = resolveCandidates();
        URI[] endpoints = new URI[addresses.length];
        for (int i = 0; i < addresses.length; i++) {
            endpoints[i] = endpointFor(addresses[i]);
        }
        return endpoints;
    }

    private InetAddress[] resolveCandidates() {
        String host = endpoint.getHost();
        if (host == null) {
            throw new QuackException("Quack endpoint has no host: " + endpoint);
        }
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new QuackException("Quack endpoint host could not be resolved: " + host, e);
        }
    }

    private URI endpointFor(InetAddress address) {
        String literal = address instanceof Inet6Address
                ? "[" + address.getHostAddress() + "]"
                : address.getHostAddress();
        try {
            return new URI(endpoint.getScheme(), null, literal, endpoint.getPort(),
                    endpoint.getPath(), endpoint.getQuery(), endpoint.getFragment());
        } catch (URISyntaxException e) {
            throw new QuackException("Failed to build address-specific Quack URI for " + address, e);
        }
    }

    private static String buildErrorMessage(Throwable cause, URI attempted) {
        String detail = cause.getMessage();
        if (detail == null || detail.isEmpty()) {
            detail = cause.getClass().getSimpleName();
        }
        return "Quack HTTP request to " + attempted + " failed: " + detail;
    }

    private String buildExhaustedMessage(URI[] attempts, Throwable cause) {
        StringBuilder sb = new StringBuilder("Quack HTTP connect failed for ")
                .append(endpoint.getHost()).append(":").append(endpoint.getPort())
                .append(" (tried ").append(attempts.length).append(" endpoint(s): ");
        for (int i = 0; i < attempts.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(attempts[i]);
        }
        sb.append(")");
        if (cause != null) {
            String detail = cause.getMessage();
            if (detail == null || detail.isEmpty()) detail = cause.getClass().getSimpleName();
            sb.append(": ").append(detail);
        }
        return sb.toString();
    }
}
