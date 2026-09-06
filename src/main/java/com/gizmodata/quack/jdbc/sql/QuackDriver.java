package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.codec.DecodeLimits;
import com.gizmodata.quack.jdbc.transport.QuackUri;
import com.gizmodata.quack.jdbc.transport.QuackTransportFactory;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

public final class QuackDriver implements Driver {

    public static final int MAJOR_VERSION = DriverVersion.MAJOR_VERSION;
    public static final int MINOR_VERSION = DriverVersion.MINOR_VERSION;
    public static final String DRIVER_NAME = "quack-jdbc";

    static {
        try {
            DriverManager.registerDriver(new QuackDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        return connect(url, info, QuackTransportFactory.http());
    }

    public Connection connect(String url, Properties info,
                              QuackTransportFactory transportFactory) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        try {
            QuackUri parsed = QuackUri.parse(url, info != null ? info : new Properties());
            return new QuackConnection(parsed, transportFactory);
        } catch (RuntimeException e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    @Override
    public boolean acceptsURL(String url) {
        return QuackUri.acceptsUrl(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        DriverPropertyInfo token = new DriverPropertyInfo("token", info != null ? info.getProperty("token") : null);
        token.description = "Quack authentication token. Prefer tokenEnv or tokenFile for shared configs.";
        token.required = false;

        DriverPropertyInfo password = new DriverPropertyInfo("password",
                info != null ? info.getProperty("password") : null);
        password.description = "Quack authentication token alias for tools that provide a password field.";
        password.required = false;

        DriverPropertyInfo tokenEnv = new DriverPropertyInfo("tokenEnv",
                info != null ? info.getProperty("tokenEnv") : null);
        tokenEnv.description = "Environment variable containing the Quack authentication token.";
        tokenEnv.required = false;

        DriverPropertyInfo tokenFile = new DriverPropertyInfo("tokenFile",
                info != null ? info.getProperty("tokenFile") : null);
        tokenFile.description = "Path to a local file containing the Quack authentication token.";
        tokenFile.required = false;

        DriverPropertyInfo tls = new DriverPropertyInfo("tls", info != null ? info.getProperty("tls", "false") : "false");
        tls.description = "Use HTTPS for the Quack transport (default: false).";
        tls.choices = new String[]{"true", "false"};
        tls.required = false;

        DriverPropertyInfo connectTimeout = new DriverPropertyInfo("connectTimeout",
                info != null ? info.getProperty("connectTimeout") : null);
        connectTimeout.description = "HTTP connect timeout as seconds or ISO-8601 duration (default: 10 seconds).";
        connectTimeout.required = false;

        DriverPropertyInfo requestTimeout = new DriverPropertyInfo("requestTimeout",
                info != null ? info.getProperty("requestTimeout") : null);
        requestTimeout.description = "Per-request HTTP timeout as seconds or ISO-8601 duration (default: 60 seconds).";
        requestTimeout.required = false;

        DriverPropertyInfo maxResponseBytes = new DriverPropertyInfo("maxResponseBytes",
                info != null ? info.getProperty("maxResponseBytes") : null);
        maxResponseBytes.description = "Maximum HTTP response body size in bytes (positive integer, default: "
                + DecodeLimits.DEFAULT.maxResponseBytes() + ").";
        maxResponseBytes.required = false;

        DriverPropertyInfo maxDecodedBytes = new DriverPropertyInfo("maxDecodedBytes",
                info != null ? info.getProperty("maxDecodedBytes") : null);
        maxDecodedBytes.description = "Maximum decoded allocation budget in bytes (positive long, default: "
                + DecodeLimits.DEFAULT.maxDecodedBytes() + ").";
        maxDecodedBytes.required = false;

        DriverPropertyInfo maxNestingDepth = new DriverPropertyInfo("maxNestingDepth",
                info != null ? info.getProperty("maxNestingDepth") : null);
        maxNestingDepth.description = "Maximum decode nesting depth (integer 1..128, default: "
                + DecodeLimits.DEFAULT.maxNestingDepth() + ").";
        maxNestingDepth.required = false;

        DriverPropertyInfo resultMetadata = new DriverPropertyInfo("resultMetadata",
                info != null ? info.getProperty("resultMetadata", "required") : "required");
        resultMetadata.choices = new String[]{"required", "legacy"};
        resultMetadata.description = "Require patched-server result metadata (default: required). "
                + "Explicit legacy supports stock v1 with ambiguous alias-based result classification.";
        resultMetadata.required = false;

        return new DriverPropertyInfo[]{
                token, password, tokenEnv, tokenFile, tls, connectTimeout, requestTimeout,
                maxResponseBytes, maxDecodedBytes, maxNestingDepth, resultMetadata
        };
    }

    @Override
    public int getMajorVersion() {
        return MAJOR_VERSION;
    }

    @Override
    public int getMinorVersion() {
        return MINOR_VERSION;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}
