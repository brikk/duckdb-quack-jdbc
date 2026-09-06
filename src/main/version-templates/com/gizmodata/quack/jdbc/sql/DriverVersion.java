package com.gizmodata.quack.jdbc.sql;

/** Generated from the Maven project version; all identity fields are compile-time constants. */
final class DriverVersion {

    static final String VERSION = "${project.version}";
    static final int MAJOR_VERSION = ${parsedVersion.majorVersion};
    static final int MINOR_VERSION = ${parsedVersion.minorVersion};
    static final String CLIENT_VERSION = QuackDriver.DRIVER_NAME + "/" + VERSION;

    private DriverVersion() {}
}
