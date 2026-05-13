package com.gizmodata.quack.jdbc.transport;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.QuackServerException;
import com.gizmodata.quack.jdbc.codec.QuackConstants;
import com.gizmodata.quack.jdbc.message.MessageCodec;
import com.gizmodata.quack.jdbc.message.QuackMessage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

/**
 * HTTP transport for the Quack protocol. Sends Quack messages as
 * {@code application/duckdb} request bodies to {@code POST /quack} and
 * returns the decoded server response (or raises a
 * {@link QuackServerException} for {@code ERROR_RESPONSE}).
 */
public final class QuackHttpTransport {

    private final URI endpoint;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public QuackHttpTransport(URI endpoint) {
        this(endpoint, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(),
                Duration.ofSeconds(60));
    }

    public QuackHttpTransport(URI endpoint, HttpClient httpClient, Duration requestTimeout) {
        this.endpoint = endpoint;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
    }

    public QuackMessage send(QuackMessage request) {
        byte[] body = MessageCodec.encode(request);
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", QuackConstants.DUCKDB_MIME_TYPE)
                .header("Accept", QuackConstants.DUCKDB_MIME_TYPE)
                .POST(BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(httpRequest, BodyHandlers.ofByteArray());
        } catch (java.io.IOException e) {
            throw new QuackException("Quack HTTP request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QuackException("Quack HTTP request was interrupted", e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new QuackException("Quack HTTP returned status " + response.statusCode()
                    + " from " + endpoint);
        }
        QuackMessage decoded = MessageCodec.decode(response.body());
        if (decoded instanceof QuackMessage.ErrorResponse err) {
            throw new QuackServerException(err.message());
        }
        return decoded;
    }
}
